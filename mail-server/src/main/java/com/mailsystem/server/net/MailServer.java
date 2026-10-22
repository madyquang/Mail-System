package com.mailsystem.server.net;

import com.mailsystem.common.protocol.ProtocolConstants;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.ResponseMessage;
import com.mailsystem.server.security.RateLimiter;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLServerSocket;

/**
 * Lớp mạng của Server: mở ServerSocket (TLS), chấp nhận kết nối và giao mỗi
 * kết nối cho một ClientHandler chạy trên thread pool.
 *
 * Mô hình: blocking I/O + thread-per-connection (mỗi kết nối giữ 1 thread đọc
 * và 1 thread ghi). Số kết nối đồng thời bị giới hạn bằng Semaphore để Server
 * không tạo thread vô hạn.
 */
public class MailServer {

    private static final int BACKLOG = 50;

    private final int port;
    private final int maxClients;
    private final int idleTimeoutMillis;
    private final ServerSocketFactory socketFactory;
    private final CommandRouter router;
    private final Supplier<RateLimiter> rateLimiters;
    private final String clientAuthMode;
    private final Semaphore connectionSlots;
    private final Set<ClientHandler> connections = ConcurrentHashMap.newKeySet();
    private final ExecutorService clientThreads;

    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public MailServer(int port, int maxClients, int idleTimeoutMillis, ServerSocketFactory socketFactory,
            CommandRouter router, Supplier<RateLimiter> rateLimiters) {
        this(port, maxClients, idleTimeoutMillis, socketFactory, router, rateLimiters, "want");
    }

    /** clientAuthMode: off | want | need (mutual TLS, xem DeviceRegistry). */
    public MailServer(int port, int maxClients, int idleTimeoutMillis, ServerSocketFactory socketFactory,
            CommandRouter router, Supplier<RateLimiter> rateLimiters, String clientAuthMode) {
        this.clientAuthMode = clientAuthMode;
        this.port = port;
        this.maxClients = maxClients;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.socketFactory = socketFactory;
        this.router = router;
        this.rateLimiters = rateLimiters;
        this.connectionSlots = new Semaphore(maxClients);
        // Virtual thread (Java 21+) hoặc thread pool thường, xem ServerThreads.
        this.clientThreads = ServerThreads.newConnectionExecutor();
    }

    /** Mở cổng lắng nghe. Trả về cổng thực tế (hữu ích khi port = 0 trong test). */
    public int bind() throws IOException {
        ServerSocket socket = socketFactory.createServerSocket();
        if (socket instanceof SSLServerSocket sslSocket) {
            sslSocket.setEnabledProtocols(ProtocolConstants.TLS_PROTOCOLS);
            // Chứng chỉ thiết bị (mutual TLS): "want" xin nhưng không bắt buộc ở bước bắt tay;
            // quyết định cho phép nằm ở ClientHandler (để báo lỗi rõ ràng thay vì cắt kết nối).
            sslSocket.setWantClientAuth(!"off".equals(clientAuthMode));
        }
        // Cho phép bind lại cổng ngay sau khi Server cũ vừa tắt (cổng còn ở TIME_WAIT).
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port), BACKLOG);
        serverSocket = socket;
        running = true;
        System.out.println("[MailServer] Dang lang nghe tai cong " + socket.getLocalPort()
                + (socket instanceof SSLServerSocket ? " (TLS 1.3)" : " (KHONG MA HOA)")
                + ", toi da " + maxClients + " ket noi, " + ServerThreads.activeMode() + " thread, "
                + "chung chi thiet bi: " + clientAuthMode);
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
            // Cũng giới hạn thời gian bắt tay TLS (chống client mở kết nối rồi im lặng).
            client.setSoTimeout(idleTimeoutMillis);
        } catch (SocketException error) {
            System.err.println("[MailServer] Khong cau hinh duoc socket: " + error.getMessage());
        }

        if (!connectionSlots.tryAcquire()) {
            com.mailsystem.server.monitor.ServerMetrics.get().recordConnection(false);
            // Gửi lỗi trên thread pool: với TLS, ghi dữ liệu cần bắt tay xong,
            // không được để client chậm chặn vòng accept().
            clientThreads.execute(() -> rejectBusy(client));
            return;
        }
        System.out.println("[MailServer] Client moi: " + client.getRemoteSocketAddress()
                + " (dang mo " + (maxClients - connectionSlots.availablePermits()) + " ket noi)");

        ClientHandler[] holder = new ClientHandler[1];
        com.mailsystem.server.monitor.ServerMetrics.get().recordConnection(true);
        ClientHandler handler = new ClientHandler(client, router, rateLimiters.get(), clientThreads, clientAuthMode, () -> {
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
            client.setSoTimeout(5_000);
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

    public int maxClients() {
        return maxClients;
    }

    /** Danh sách kết nối cho bảng giám sát. */
    public java.util.List<java.util.Map<String, Object>> connectionSnapshot() {
        return connections.stream().map(ClientHandler::snapshot)
                .sorted(java.util.Comparator.comparing(info -> (Integer) info.get("id")))
                .collect(java.util.stream.Collectors.toList());
    }

    /** Quản trị viên ngắt một kết nối. */
    public boolean kick(int connectionId) {
        for (ClientHandler handler : connections) {
            if (handler.id() == connectionId) {
                handler.forceClose();
                return true;
            }
        }
        return false;
    }
}
