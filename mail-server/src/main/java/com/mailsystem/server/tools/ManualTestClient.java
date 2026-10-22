package com.mailsystem.server.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.FrameReader;
import com.mailsystem.common.protocol.ProtocolConstants;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.RequestMessage;

import com.mailsystem.common.security.CertificateFingerprints;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.UUID;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * CÔNG CỤ TEST SERVER ĐỘC LẬP - KHÔNG CẦN CLIENT JAVAFX (kết nối qua TLS 1.3).
 *
 * Cách dùng:
 *  1. Chạy MailServer trước.
 *  2. Chạy class này, nhập host/port (Enter = mặc định).
 *  3. Gõ tên command (VD: LOGIN), Enter, rồi gõ payload JSON trên 1 dòng.
 *  4. Gõ 'help' để xem danh sách command, 'exit' để thoát.
 *
 * Kết nối TCP là song công (full-duplex): một thread nền đọc liên tục mọi frame
 * Server gửi về (cả RESPONSE lẫn EVENT), thread chính đọc bàn phím và gửi
 * REQUEST. Mở 2 cửa sổ, đăng nhập 2 tài khoản, gửi thư từ cửa sổ này sẽ thấy
 * EVENT NEW_MAIL hiện ở cửa sổ kia.
 */
public class ManualTestClient {

    public static void main(String[] args) throws IOException {
        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);

        System.out.println("=== Mail System - Manual Test Client ===");
        System.out.print("Host Server (Enter = localhost): ");
        String host = scanner.nextLine().trim();
        if (host.isEmpty()) {
            host = "localhost";
        }
        System.out.print("Port Server (Enter = " + ProtocolConstants.DEFAULT_PORT + "): ");
        String portText = scanner.nextLine().trim();
        int port = portText.isEmpty() ? ProtocolConstants.DEFAULT_PORT : Integer.parseInt(portText);

        try (Socket socket = connectTls(host, port, scanner);
                Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
            socket.setTcpNoDelay(true);
            System.out.println("Đã kết nối tới " + socket.getRemoteSocketAddress()
                    + " từ cổng cục bộ " + socket.getLocalPort());
            startReceiver(socket);
            printHelp();

            while (true) {
                System.out.print("\n> Command: ");
                if (!scanner.hasNextLine()) {
                    break;
                }
                String commandText = scanner.nextLine().trim();
                if (commandText.equalsIgnoreCase("exit")) {
                    break;
                }
                if (commandText.equalsIgnoreCase("help")) {
                    printHelp();
                    continue;
                }
                if (commandText.isEmpty()) {
                    continue;
                }

                Command command;
                try {
                    command = Command.valueOf(commandText.toUpperCase());
                } catch (IllegalArgumentException error) {
                    System.out.println("!! Command không hợp lệ. Gõ 'help' để xem danh sách.");
                    continue;
                }

                System.out.print("> Payload JSON (Enter nếu không cần): ");
                String payloadText = scanner.nextLine().trim();
                JsonElement payload = null;
                if (!payloadText.isEmpty()) {
                    try {
                        payload = JsonParser.parseString(payloadText);
                    } catch (RuntimeException error) {
                        System.out.println("!! JSON không hợp lệ: " + error.getMessage());
                        continue;
                    }
                }

                String requestId = UUID.randomUUID().toString().substring(0, 8);
                String frame = ProtocolUtil.toJson(new RequestMessage(requestId, command, payload));
                ProtocolUtil.writeFrame(out, frame);
                System.out.println(">> ĐÃ GỬI : " + frame);
            }
        }
        System.out.println("Đã đóng kết nối. Tạm biệt.");
    }

    /**
     * Kết nối TLS. Server dùng chứng chỉ tự ký nên công cụ in dấu vân tay và
     * hỏi người dùng có tin hay không (so với dòng "[TLS] Dau van tay" Server in ra).
     */
    private static Socket connectTls(String host, int port, Scanner scanner) throws IOException {
        X509TrustManager askUser = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws java.security.cert.CertificateException {
                String fingerprint = CertificateFingerprints.sha256(chain[0]);
                System.out.println("Chung chi Server: " + chain[0].getSubjectX500Principal().getName());
                System.out.println("Dau van tay SHA-256: " + fingerprint);
                System.out.print("Tin chung chi nay? (y/N): ");
                if (!scanner.nextLine().trim().equalsIgnoreCase("y")) {
                    throw new java.security.cert.CertificateException("Nguoi dung tu choi chung chi");
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        try {
            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(null, new TrustManager[] {askUser}, new SecureRandom());
            SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(host, port);
            socket.setEnabledProtocols(ProtocolConstants.TLS_PROTOCOLS);
            socket.startHandshake();
            System.out.println("TLS: " + socket.getSession().getProtocol() + " / " + socket.getSession().getCipherSuite());
            return socket;
        } catch (java.security.GeneralSecurityException error) {
            throw new IOException("Khong khoi tao duoc TLS: " + error.getMessage(), error);
        }
    }

    private static void startReceiver(Socket socket) throws IOException {
        FrameReader reader = new FrameReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8),
                ProtocolConstants.MAX_FRAME_CHARS);
        Thread receiver = new Thread(() -> {
            try {
                String frame;
                while ((frame = reader.readFrame()) != null) {
                    String type;
                    try {
                        type = ProtocolUtil.peekType(frame).name();
                    } catch (RuntimeException error) {
                        type = "???";
                    }
                    String shown = frame.length() > 2000 ? frame.substring(0, 2000) + "...(cắt bớt)" : frame;
                    System.out.println("\n<< " + type + " : " + shown);
                }
                System.out.println("\n<< Server đã đóng kết nối.");
            } catch (IOException error) {
                if (!socket.isClosed()) {
                    System.out.println("\n<< Mất kết nối: " + error.getMessage());
                }
            }
        }, "manual-test-receiver");
        receiver.setDaemon(true);
        receiver.start();
    }

    private static void printHelp() {
        System.out.println("\nDanh sách Command:");
        for (Command command : Command.values()) {
            System.out.println("  - " + command.name());
        }
        System.out.println("\nVí dụ payload:");
        System.out.println("  PING          : (bỏ trống)");
        System.out.println("  REGISTER      : {\"email\":\"a@mail.local\",\"password\":\"matkhau2026\",\"displayName\":\"Nguyen Van A\"}");
        System.out.println("  LOGIN         : {\"email\":\"a@mail.local\",\"password\":\"matkhau2026\"}");
        System.out.println("  GET_MAIL_LIST : {\"folderId\":1}");
        System.out.println("  SEND_MAIL     : {\"to\":[\"b@mail.local\"],\"subject\":\"Hi\",\"body\":\"Xin chao\"}");
    }
}
