// Gói "tools": các công cụ thử nghiệm Server (không thuộc chương trình chính).
package com.mailsystem.server.tools;

// JSON: kiểu tổng quát và bộ phân tích chuỗi JSON.
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
// Giao thức dùng chung: lệnh, đọc frame, hằng số, Gson, gói REQUEST.
import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.FrameReader;
import com.mailsystem.common.protocol.ProtocolConstants;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.RequestMessage;

// Tính dấu vân tay chứng chỉ Server.
import com.mailsystem.common.security.CertificateFingerprints;

// Vào/ra, socket, bảng mã, đọc bàn phím, mã ngẫu nhiên.
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.UUID;
// Mật mã và TLS.
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

    /** Điểm vào của công cụ. */
    public static void main(String[] args) throws IOException {
        // Đọc bàn phím theo UTF-8 (gõ được tiếng Việt).
        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);

        System.out.println("=== Mail System - Manual Test Client ===");
        // Hỏi địa chỉ Server.
        System.out.print("Host Server (Enter = localhost): ");
        String host = scanner.nextLine().trim();
        if (host.isEmpty()) {
            host = "localhost";
        }
        // Hỏi cổng.
        System.out.print("Port Server (Enter = " + ProtocolConstants.DEFAULT_PORT + "): ");
        String portText = scanner.nextLine().trim();
        int port = portText.isEmpty() ? ProtocolConstants.DEFAULT_PORT : Integer.parseInt(portText);

        // Kết nối TLS và mở luồng ghi UTF-8 (tự đóng khi thoát khối try).
        try (Socket socket = connectTls(host, port, scanner);
                Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
            // Tắt thuật toán Nagle: gói nhỏ được gửi ngay, không chờ gom.
            socket.setTcpNoDelay(true);
            System.out.println("Đã kết nối tới " + socket.getRemoteSocketAddress()
                    + " từ cổng cục bộ " + socket.getLocalPort());
            // Thread nền in mọi frame Server gửi về.
            startReceiver(socket);
            printHelp();

            // Vòng nhập lệnh.
            while (true) {
                System.out.print("\n> Command: ");
                // Hết dữ liệu nhập (Ctrl+Z / Ctrl+D) thì thoát.
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

                // Đổi chữ người dùng gõ thành enum Command.
                Command command;
                try {
                    command = Command.valueOf(commandText.toUpperCase());
                } catch (IllegalArgumentException error) {
                    System.out.println("!! Command không hợp lệ. Gõ 'help' để xem danh sách.");
                    continue;
                }

                // Hỏi payload JSON (có thể bỏ trống).
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

                // Mã yêu cầu ngắn (8 ký tự đầu của UUID) cho dễ đọc.
                String requestId = UUID.randomUUID().toString().substring(0, 8);
                // Đóng gói REQUEST thành JSON và gửi một frame.
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
        // TrustManager tự viết: hỏi người dùng thay vì kiểm tra theo CA.
        X509TrustManager askUser = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // Không dùng (công cụ là client).
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws java.security.cert.CertificateException {
                // In thông tin và dấu vân tay chứng chỉ Server.
                String fingerprint = CertificateFingerprints.sha256(chain[0]);
                System.out.println("Chung chi Server: " + chain[0].getSubjectX500Principal().getName());
                System.out.println("Dau van tay SHA-256: " + fingerprint);
                // Không gõ "y" thì huỷ bắt tay.
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
            // Cấu hình TLS 1.3 dùng TrustManager ở trên, không có khoá riêng (null).
            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(null, new TrustManager[] {askUser}, new SecureRandom());
            // Mở socket TLS tới Server.
            SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(host, port);
            // Chỉ bật TLS 1.3.
            socket.setEnabledProtocols(ProtocolConstants.TLS_PROTOCOLS);
            // Bắt tay ngay (lúc này checkServerTrusted được gọi).
            socket.startHandshake();
            System.out.println("TLS: " + socket.getSession().getProtocol() + " / " + socket.getSession().getCipherSuite());
            return socket;
        } catch (java.security.GeneralSecurityException error) {
            throw new IOException("Khong khoi tao duoc TLS: " + error.getMessage(), error);
        }
    }

    // Tạo thread nền đọc và in mọi frame Server gửi về.
    private static void startReceiver(Socket socket) throws IOException {
        // Bộ đọc frame trên luồng vào UTF-8, giới hạn 1 MiB.
        FrameReader reader = new FrameReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8),
                ProtocolConstants.MAX_FRAME_CHARS);
        Thread receiver = new Thread(() -> {
            try {
                String frame;
                // Đọc tới khi Server đóng kết nối (readFrame trả null).
                while ((frame = reader.readFrame()) != null) {
                    // Loại frame (RESPONSE / EVENT), lỗi thì "???".
                    String type;
                    try {
                        type = ProtocolUtil.peekType(frame).name();
                    } catch (RuntimeException error) {
                        type = "???";
                    }
                    // Frame quá dài (vd khối tệp Base64) thì cắt bớt khi in.
                    String shown = frame.length() > 2000 ? frame.substring(0, 2000) + "...(cắt bớt)" : frame;
                    System.out.println("\n<< " + type + " : " + shown);
                }
                System.out.println("\n<< Server đã đóng kết nối.");
            } catch (IOException error) {
                // Lỗi đọc mà socket chưa bị chính mình đóng: báo mất kết nối.
                if (!socket.isClosed()) {
                    System.out.println("\n<< Mất kết nối: " + error.getMessage());
                }
            }
        }, "manual-test-receiver");
        // Daemon: không giữ chương trình chạy khi thread chính kết thúc.
        receiver.setDaemon(true);
        receiver.start();
    }

    // In danh sách lệnh và ví dụ payload.
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
