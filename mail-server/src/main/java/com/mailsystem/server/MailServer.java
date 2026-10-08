package com.mailsystem.server;

import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.ResponseMessage;
import com.mailsystem.server.transfer.UploadManager;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ENTRY POINT của Server: mở ServerSocket, chấp nhận kết nối và giao mỗi kết
 * nối cho một ClientHandler chạy trên thread pool.
 *
 * Mô hình: blocking I/O + thread-per-connection (mỗi kết nối giữ 1 thread đọc
 * và 1 thread ghi). Đơn giản, dễ hiểu, đủ cho vài trăm client. Số kết nối đồng
 * thời bị giới hạn bằng Semaphore để Server không tạo thread vô hạn.
 *
 * KHÔNG viết business logic ở đây: ClientHandler -> Service -> DAO.
 */
public class MailServer {

    private static final int BACKLOG = 50;

    private final int port;
    private final int maxClients;
    private final int idleTimeoutMillis;
    private final Semaphore connectionSlots;
    private final Set<ClientHandler> connections = ConcurrentHashMap.newKeySet();
    private final ExecutorService clientThreads;

    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public MailServer(int port, int maxClients, int idleTimeoutMillis) {
        this.port = port;
        this.maxClients = maxClients;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.connectionSlots = new Semaphore(maxClients);
        AtomicInteger threadNumber = new AtomicInteger();
        this.clientThreads = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "mail-client-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public static void main(String[] args) {
        MailServer server = new MailServer(ServerConfig.port(), ServerConfig.maxClients(),
                ServerConfig.idleTimeoutSeconds() * 1000);
        // Ctrl+C / tắt tiến trình: đóng ServerSocket và mọi kết nối một cách gọn gàng.
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "mail-shutdown"));
        try {
            server.bind();
            server.serve();
        } catch (IOException error) {
            System.err.println("[MailServer] Khong mo duoc cong " + server.port + ": " + error.getMessage());
            System.exit(1);
        }
    }

    /** Mở cổng lắng nghe. Trả về cổng thực tế (hữu ích khi port = 0 trong test). */
    public int bind() throws IOException {
        UploadManager.cleanTempDirectory(ServerConfig.uploadTempDir());
        Files.createDirectories(ServerConfig.uploadTempDir());

        ServerSocket socket = new ServerSocket();
        // Cho phép bind lại cổng ngay sau khi Server cũ vừa tắt (cổng còn ở TIME_WAIT).
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port), BACKLOG);
        serverSocket = socket;
        running = true;
        System.out.println("[MailServer] Dang lang nghe tai cong " + socket.getLocalPort()
                + " (toi da " + maxClients + " ket noi)");
        return socket.getLocalPort();
    }

    /** Vòng lặp accept(): chặn cho tới khi stop() được gọi. */
    public void serve() {
        while (running) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException error) {
                if (running) {
                    System.err.println("[MailServer] accept() loi: " + error.getMessage());
                    continue;
                }
                break; // stop() đã đóng ServerSocket
            }
            handleNewConnection(client);
        }
    }

    private void handleNewConnection(Socket client) {
        try {
            // JSON frame nhỏ, tương tác qua lại: tắt thuật toán Nagle để
            // response không bị giữ lại chờ gộp gói.
            client.setTcpNoDelay(true);
            // TCP keep-alive: hệ điều hành thăm dò kết nối im lặng quá lâu.
            client.setKeepAlive(true);
            // read() quá thời gian này sẽ ném SocketTimeoutException -> đóng kết nối "chết".
            client.setSoTimeout(idleTimeoutMillis);
        } catch (SocketException error) {
            System.err.println("[MailServer] Khong cau hinh duoc socket: " + error.getMessage());
        }

        if (!connectionSlots.tryAcquire()) {
            rejectBusy(client);
            return;
        }
        System.out.println("[MailServer] Client moi: " + client.getRemoteSocketAddress()
                + " (dang mo " + (maxClients - connectionSlots.availablePermits()) + " ket noi)");

        ClientHandler[] holder = new ClientHandler[1];
        ClientHandler handler = new ClientHandler(client, () -> {
            connections.remove(holder[0]);
            connectionSlots.release();
        });
        holder[0] = handler;
        connections.add(handler);
        clientThreads.execute(handler);
    }

    /** Báo lỗi cho client bằng một frame rồi đóng, thay vì để client chờ vô ích. */
    private void rejectBusy(Socket client) {
        System.err.println("[MailServer] Tu choi " + client.getRemoteSocketAddress() + ": da du "
                + maxClients + " ket noi.");
        try (client; Writer writer = new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8)) {
            ProtocolUtil.writeFrame(writer, ProtocolUtil.toJson(ResponseMessage.error(null,
                    "Máy chủ đang quá tải, vui lòng thử lại sau.")));
        } catch (IOException ignored) {
        }
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        System.out.println("[MailServer] Dang dung Server...");
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        for (ClientHandler handler : connections) {
            handler.forceClose();
        }
        clientThreads.shutdown();
        try {
            if (!clientThreads.awaitTermination(5, TimeUnit.SECONDS)) {
                clientThreads.shutdownNow();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    public int activeConnections() {
        return connections.size();
    }
}
