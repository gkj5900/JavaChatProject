package 규정;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.List;

public class ChatClient extends JFrame {
    private static final String SERVER_IP = "userIP";
    private static final int SERVER_PORT = 12345;

    private Socket socket;
    private BufferedReader in;
    private BufferedWriter out;

    private JTextArea chatArea;
    private JTextField inputField;
    private JButton sendButton, ftpButton, fileListButton;

    private DefaultListModel<String> fileListModel;
    private JDialog fileListDialog;

    public ChatClient() {
        setTitle("멀티 클라이언트 채팅 및 파일 전송");
        setSize(600, 400);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        chatArea = new JTextArea();
        chatArea.setEditable(false);
        JScrollPane chatScroll = new JScrollPane(chatArea);

        inputField = new JTextField();
        sendButton = new JButton("전송");
        ftpButton = new JButton("FTP");
        fileListButton = new JButton("파일목록");

        JPanel bottomPanel = new JPanel(new BorderLayout());
        JPanel buttonPanel = new JPanel();
        buttonPanel.add(sendButton);
        buttonPanel.add(ftpButton);
        buttonPanel.add(fileListButton);
        bottomPanel.add(inputField, BorderLayout.CENTER);
        bottomPanel.add(buttonPanel, BorderLayout.EAST);

        add(chatScroll, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        // 이벤트 처리
        sendButton.addActionListener(e -> sendMessage());
        inputField.addActionListener(e -> sendMessage());

        ftpButton.addActionListener(e -> sendFile());
        fileListButton.addActionListener(e -> showFileListDialog());

        try {
            socket = new Socket(SERVER_IP, SERVER_PORT);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

            new Thread(this::listenServer).start();
        } catch (IOException e) {
            showError("서버 연결 실패: " + e.getMessage());
            System.exit(0);
        }
    }

    private void listenServer() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                // 파일 리스트 응답 처리
                if (line.startsWith("FILE_LIST_RESP|")) {
                    String listStr = line.substring("FILE_LIST_RESP|".length());
                    SwingUtilities.invokeLater(() -> updateFileList(listStr));
                }
                // 파일 다운로드 응답 처리
                else if (line.startsWith("FILE_DOWNLOAD_RESP|")) {
                    String[] parts = line.split("\\|");
                    if (parts.length != 3) continue;
                    String filename = parts[1];
                    int fileSize = Integer.parseInt(parts[2]);

                    byte[] fileData = new byte[fileSize];
                    InputStream is = socket.getInputStream();
                    int readBytes = 0;
                    while (readBytes < fileSize) {
                        int r = is.read(fileData, readBytes, fileSize - readBytes);
                        if (r == -1) break;
                        readBytes += r;
                    }
                    File receivedFile = new File(System.getProperty("java.io.tmpdir"), filename);
                    Files.write(receivedFile.toPath(), fileData);
                    SwingUtilities.invokeLater(() -> {
                        showMessage("[서버] 파일 다운로드 완료: " + filename);
                        openFile(receivedFile);
                    });
                }
                // 파일 다운로드 에러
                else if (line.startsWith("FILE_DOWNLOAD_ERR|")) {
                    String errMsg = line.substring("FILE_DOWNLOAD_ERR|".length());
                    SwingUtilities.invokeLater(() -> showMessage("[서버] 오류: " + errMsg));
                }
                else {
                    // 일반 메시지
                    String msg = line;
                    SwingUtilities.invokeLater(() -> showMessage(msg));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            showError("서버와 연결이 끊겼습니다.");
            System.exit(0);
        }
    }

    private void sendMessage() {
        String msg = inputField.getText().trim();
        if (msg.isEmpty()) return;

        try {
            out.write(msg + "\n");
            out.flush();
            inputField.setText("");
        } catch (IOException e) {
            showError("메시지 전송 실패: " + e.getMessage());
        }
    }

    private void sendFile() {
        JFileChooser chooser = new JFileChooser();
        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            try {
                byte[] data = Files.readAllBytes(file.toPath());
                // 프로토콜: FILE_SEND|filename|filesize\n바이너리 데이터
                out.write("FILE_SEND|" + file.getName() + "|" + data.length + "\n");
                out.flush();
                socket.getOutputStream().write(data);
                socket.getOutputStream().flush();
                showMessage("[클라이언트] 파일 전송 완료: " + file.getName());
            } catch (IOException e) {
                showError("파일 전송 실패: " + e.getMessage());
            }
        }
    }

    private void showFileListDialog() {
        try {
            // 서버에 파일 목록 요청
            out.write("FILE_LIST\n");
            out.flush();
        } catch (IOException e) {
            showError("파일 목록 요청 실패: " + e.getMessage());
            return;
        }

        if (fileListDialog == null) {
            fileListModel = new DefaultListModel<>();
            JList<String> fileList = new JList<>(fileListModel);
            fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

            JButton openButton = new JButton("확인");
            openButton.addActionListener(e -> {
                String selected = fileList.getSelectedValue();
                if (selected == null) {
                    JOptionPane.showMessageDialog(fileListDialog, "파일을 선택하세요.");
                    return;
                }
                downloadAndOpenFile(selected);
                fileListDialog.setVisible(false);
            });

            JPanel panel = new JPanel(new BorderLayout());
            panel.add(new JScrollPane(fileList), BorderLayout.CENTER);
            panel.add(openButton, BorderLayout.SOUTH);

            fileListDialog = new JDialog(this, "서버 파일 목록", true);
            fileListDialog.setSize(300, 400);
            fileListDialog.setLocationRelativeTo(this);
            fileListDialog.setContentPane(panel);
        }
        fileListDialog.setVisible(true);
    }

    private void updateFileList(String listStr) {
        fileListModel.clear();
        if (!listStr.isEmpty()) {
            String[] files = listStr.split(",");
            for (String f : files) {
                fileListModel.addElement(f);
            }
        }
    }

    private void downloadAndOpenFile(String filename) {
        try {
            // 서버에 다운로드 요청
            out.write("FILE_DOWNLOAD|" + filename + "\n");
            out.flush();
        } catch (IOException e) {
            showError("파일 다운로드 요청 실패: " + e.getMessage());
        }
    }

    private void showMessage(String msg) {
        chatArea.append(msg + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    private void showError(String err) {
        JOptionPane.showMessageDialog(this, err, "오류", JOptionPane.ERROR_MESSAGE);
    }

    private void openFile(File file) {
        try {
            if (!Desktop.isDesktopSupported()) {
                showMessage("파일 열기 지원되지 않음.");
                return;
            }
            Desktop.getDesktop().open(file);
        } catch (IOException e) {
            showMessage("파일 열기 실패: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ChatClient client = new ChatClient();
            client.setVisible(true);
        });
    }
}
