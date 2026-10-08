package com.mailsystem.server.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.FrameReader;
import com.mailsystem.common.protocol.ProtocolConstants;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.RequestMessage;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.UUID;

/**
 * CÔNG CỤ TEST SERVER ĐỘC LẬP - KHÔNG CẦN CLIENT JAVAFX.
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

        try (Socket socket = new Socket(host, port);
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
        System.out.println("  REGISTER      : {\"email\":\"a@mail.local\",\"password\":\"123456\",\"displayName\":\"Nguyen Van A\"}");
        System.out.println("  LOGIN         : {\"email\":\"a@mail.local\",\"password\":\"123456\"}");
        System.out.println("  GET_MAIL_LIST : {\"folderId\":1}");
        System.out.println("  SEND_MAIL     : {\"to\":[\"b@mail.local\"],\"subject\":\"Hi\",\"body\":\"Xin chao\"}");
    }
}
