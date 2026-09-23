package com.mailsystem.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Properties;

/**
 * ENTRY POINT của Server.
 * Trách nhiệm DUY NHẤT của class này: mở ServerSocket, lắng nghe kết nối,
 * mỗi khi có client mới kết nối -> tạo 1 ClientHandler chạy trên thread riêng
 * (mô hình Thread-per-client).
 *
 * KHÔNG viết business logic ở đây. Logic xử lý từng command nằm ở
 * ClientHandler -> Service -> DAO.
 */
public class MailServer {

    public static void main(String[] args) {
        int port = loadPort();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[MailServer] Dang lang nghe tai port " + port);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("[MailServer] Client moi ket noi: " + clientSocket.getInetAddress());

                // TODO (TV2): khoi tao ClientHandler va chay tren 1 Thread rieng.
                ClientHandler handler = new ClientHandler(clientSocket);
                Thread thread = new Thread(handler);
                thread.start();
            }
        } catch (IOException e) {
            System.err.println("[MailServer] Loi khi mo ServerSocket: " + e.getMessage());
        }
    }

    private static int loadPort() {
        Properties props = new Properties();
        try (InputStream in = MailServer.class.getClassLoader()
                .getResourceAsStream("db.properties")) {
            if (in != null) {
                props.load(in);
                return Integer.parseInt(props.getProperty("server.port", "5000"));
            }
        } catch (IOException e) {
            System.err.println("Khong doc duoc db.properties, dung port mac dinh 5000");
        }
        return 5000;
    }
}
