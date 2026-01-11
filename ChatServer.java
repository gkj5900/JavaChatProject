package 규정;
	


import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

public class ChatServer {
    private static final int PORT = 12345; // 서버 포트
    private static final String FILE_DIR = "server_files";

    private ServerSocket serverSocket;
    // 모든 클라이언트 출력 스트림 저장
    private final Set<ClientHandler> clientHandlers = ConcurrentHashMap.newKeySet();

    public ChatServer() throws IOException {
        File dir = new File(FILE_DIR);
        if (!dir.exists()) dir.mkdir();

        // 서버 시작 시 기존 파일 삭제 (초기화)
        for (File f : dir.listFiles()) {
            if (f.isFile()) {
                f.delete();
            }
        }

        serverSocket = new ServerSocket(PORT);
        System.out.println("서버 시작, 포트: " + PORT);
    }

    public void start() throws IOException {
        while (true) {
            Socket socket = serverSocket.accept();
            ClientHandler handler = new ClientHandler(socket);
            clientHandlers.add(handler);
            handler.start();
        }
    }

    // 모든 클라이언트에게 메시지 브로드캐스트
    private void broadcast(String msg) {
        for (ClientHandler ch : clientHandlers) {
            ch.sendMessage(msg);
        }
    }

    // 파일 저장
    private synchronized void saveFile(String filename, byte[] data) throws IOException {
        File file = new File(FILE_DIR, filename);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        }
    }

    // 서버내 파일 목록 반환
    private List<String> getFileList() {
        File dir = new File(FILE_DIR);
        String[] files = dir.list();
        if (files == null) return Collections.emptyList();
        return Arrays.asList(files);
    }

    // 파일 읽기
    private byte[] readFile(String filename) throws IOException {
        File file = new File(FILE_DIR, filename);
        return Files.readAllBytes(file.toPath());
    }

    // ClientHandler 클래스
    private class ClientHandler extends Thread {
        private Socket socket;
        private BufferedReader in;
        private BufferedWriter out;
        private int clientId;

        public ClientHandler(Socket socket) throws IOException {
            this.socket = socket;
            this.clientId = socket.getPort();
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        }

        public void sendMessage(String msg) {
            try {
                out.write(msg + "\n");
                out.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void run() {
            try {
                broadcast("[서버] 클라이언트 " + clientId + "님이 접속했습니다.");

                String line;
                while ((line = in.readLine()) != null) {
                    // 명령어 프로토콜: FILE_SEND|filename|filesize\n (파일 전송 시작)
                    // FILE_SEND 다음에 바이너리 받음
                    if (line.startsWith("FILE_SEND|")) {
                        String[] parts = line.split("\\|");
                        if (parts.length != 3) continue;

                        String filename = parts[1];
                        int fileSize = Integer.parseInt(parts[2]);

                        // 파일 받기
                        byte[] fileData = new byte[fileSize];
                        InputStream is = socket.getInputStream();
                        int readBytes = 0;
                        while (readBytes < fileSize) {
                            int r = is.read(fileData, readBytes, fileSize - readBytes);
                            if (r == -1) break;
                            readBytes += r;
                        }
                        saveFile(filename, fileData);
                        broadcast("[서버] 클라이언트 " + clientId + "님이 파일 '" + filename + "'을(를) 업로드했습니다.");
                    }
                    // 파일 목록 요청 명령어: FILE_LIST
                    else if (line.equals("FILE_LIST")) {
                        List<String> files = getFileList();
                        // 파일 리스트를 "FILE_LIST_RESP|파일1,파일2,파일3" 형태로 전송
                        String fileListStr = String.join(",", files);
                        sendMessage("FILE_LIST_RESP|" + fileListStr);
                    }
                    // 파일 다운로드 요청 명령어: FILE_DOWNLOAD|filename
                    else if (line.startsWith("FILE_DOWNLOAD|")) {
                        String[] parts = line.split("\\|");
                        if (parts.length != 2) continue;
                        String filename = parts[1];
                        File file = new File(FILE_DIR, filename);
                        if (!file.exists()) {
                            sendMessage("FILE_DOWNLOAD_ERR|파일이 서버에 없습니다.");
                            continue;
                        }
                        byte[] fileData = Files.readAllBytes(file.toPath());
                        // 파일 데이터 보내기 프로토콜: FILE_DOWNLOAD_RESP|filename|filesize\n바이너리
                        out.write("FILE_DOWNLOAD_RESP|" + filename + "|" + fileData.length + "\n");
                        out.flush();
                        socket.getOutputStream().write(fileData);
                        socket.getOutputStream().flush();
                    }
                    // 그냥 채팅 메시지
                    else {
                        broadcast("[" + clientId + "] " + line);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {}
                clientHandlers.remove(this);
                broadcast("[서버] 클라이언트 " + clientId + "님이 나갔습니다.");
            }
        }
    }

    public static void main(String[] args) {
        try {
            new ChatServer().start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
