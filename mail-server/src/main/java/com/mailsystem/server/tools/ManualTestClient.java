package com.mailsystem.server.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.RequestMessage;

import java.io.*;
import java.net.Socket;
import java.util.Scanner;
import java.util.UUID;

/**
 * CONG CU TEST SERVER DOC LAP - KHONG CAN CLIENT THAT.
 *
 * TV2 dung class nay de tu kiem tra Server minh dang code co chay dung khong,
 * MA KHONG CAN CHO TV3/TV4 lam xong Client JavaFX.
 *
 * CACH DUNG:
 *  1. Chay MailServer truoc (1 terminal/1 lan chay rieng).
 *  2. Chay class nay (terminal/lan chay khac).
 *  3. Go ten command (VD: REGISTER) roi Enter.
 *  4. Go payload dang JSON tren 1 dong (VD: {"email":"a@mail.local","password":"123456","displayName":"A"}) roi Enter.
 *  5. Xem RESPONSE server tra ve ngay tren console.
 *  6. Go 'help' de xem lai danh sach command, go 'exit' de thoat.
 *
 * LUU Y: day la cong cu test THU CONG, don gian (gui 1 lenh - doi 1 dong tra loi),
 * KHONG mo phong duoc truong hop Server chu dong gui EVENT xen ngang (vi luc test
 * mot minh chua co client khac nao online de sinh EVENT). Muc dich chinh la kiem
 * tra tung Command/Service/DAO co hoat dong dung logic hay khong.
 */
public class ManualTestClient {

    public static void main(String[] args) throws IOException {
        Scanner scanner = new Scanner(System.in);

        System.out.println("=== Mail System - Manual Test Client ===");
        System.out.print("Host Server (Enter = localhost): ");
        String host = scanner.nextLine().trim();
        if (host.isEmpty()) host = "localhost";

        System.out.print("Port Server (Enter = 5000): ");
        String portStr = scanner.nextLine().trim();
        int port = portStr.isEmpty() ? 5000 : Integer.parseInt(portStr);

        try (Socket socket = new Socket(host, port);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true)) {

            System.out.println("Da ket noi toi " + host + ":" + port);
            printHelp();

            while (true) {
                System.out.print("\n> Command (go 'help' hoac 'exit'): ");
                String commandStr = scanner.nextLine().trim();

                if (commandStr.equalsIgnoreCase("exit")) break;
                if (commandStr.equalsIgnoreCase("help")) {
                    printHelp();
                    continue;
                }

                Command command;
                try {
                    command = Command.valueOf(commandStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    System.out.println("!! Command khong hop le. Go 'help' de xem danh sach.");
                    continue;
                }

                System.out.print("> Payload JSON (Enter neu khong can payload): ");
                String payloadStr = scanner.nextLine().trim();

                JsonElement payload = null;
                if (!payloadStr.isEmpty()) {
                    try {
                        payload = JsonParser.parseString(payloadStr);
                    } catch (Exception e) {
                        System.out.println("!! JSON khong hop le: " + e.getMessage());
                        continue;
                    }
                }

                String requestId = UUID.randomUUID().toString().substring(0, 8);
                RequestMessage request = new RequestMessage(requestId, command, payload);
                String requestLine = ProtocolUtil.toJson(request);

                out.println(requestLine);
                System.out.println(">> DA GUI   : " + requestLine);

                String responseLine = in.readLine();
                System.out.println("<< NHAN VE  : " + responseLine);
            }
        }

        System.out.println("Da dong ket noi. Tam biet.");
    }

    private static void printHelp() {
        System.out.println("\nDanh sach Command co the test:");
        for (Command c : Command.values()) {
            System.out.println("  - " + c.name());
        }
        System.out.println("\nVi du payload cho mot so command:");
        System.out.println("  REGISTER : {\"email\":\"a@mail.local\",\"password\":\"123456\",\"displayName\":\"Nguyen Van A\"}");
        System.out.println("  LOGIN    : {\"email\":\"a@mail.local\",\"password\":\"123456\"}");
        System.out.println("  GET_MAIL_LIST : {\"folderId\":1}");
    }
}
