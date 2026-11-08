// Gói "net": phần mạng của Server.
package com.mailsystem.server.net;

// Hằng số giao thức, Gson dùng chung, câu trả lời (để báo quá tải).
import com.mailsystem.common.protocol.ProtocolConstants;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.ResponseMessage;
// Bộ giới hạn tốc độ cho mỗi kết nối.
import com.mailsystem.server.security.RateLimiter;

// Vào/ra và socket.
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
// Tập kết nối an toàn đa luồng, pool thread, semaphore giới hạn số kết nối.
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
// Supplier: hàm không tham số trả một giá trị (tạo RateLimiter mới).
import java.util.function.Supplier;
// Nhà máy tạo ServerSocket (thường hoặc TLS) và ServerSocket TLS.
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

    // Số kết nối tối đa chờ trong hàng đợi của hệ điều hành trước khi accept().
    private static final int BACKLOG = 50;

    // Cổng lắng nghe.
    private final int port;
    // Số kết nối đồng thời tối đa.
    private final int maxClients;
    // Thời gian chờ đọc tối đa (ms) trước khi coi kết nối là "chết".
    private final int idleTimeoutMillis;
    // Nhà máy tạo ServerSocket (TLS khi chạy thật).
    private final ServerSocketFactory socketFactory;
    // Bộ định tuyến lệnh dùng chung.
    private final CommandRouter router;
    // Cách tạo RateLimiter mới cho mỗi kết nối.
    private final Supplier<RateLimiter> rateLimiters;
    // Chế độ chứng chỉ thiết bị: off / want / need.
    private final String clientAuthMode;
    // "Vé" kết nối: hết vé thì từ chối kết nối mới.
    private final Semaphore connectionSlots;
    // Các kết nối đang mở.
    private final Set<ClientHandler> connections = ConcurrentHashMap.newKeySet();
    // Pool thread chạy các ClientHandler.
    private final ExecutorService clientThreads;

    // ServerSocket đang mở (volatile: thread khác thấy giá trị mới nhất).
    private volatile ServerSocket serverSocket;
    // true khi Server đang chạy.
    private volatile boolean running;

    /** Constructor với chế độ chứng chỉ thiết bị mặc định "want" (dùng trong test). */
    public MailServer(int port, int maxClients, int idleTimeoutMillis, ServerSocketFactory socketFactory,
            CommandRouter router, Supplier<RateLimiter> rateLimiters) {
        this(port, maxClients, idleTimeoutMillis, socketFactory, router, rateLimiters, "want");
    }

    /** clientAuthMode: off | want | need (mutual TLS, xem DeviceRegistry). */
    public MailServer(int port, int maxClients, int idleTimeoutMillis, ServerSocketFactory socketFactory,
            CommandRouter router, Supplier<RateLimiter> rateLimiters, String clientAuthMode) {
        // Lưu các tham số.
        this.clientAuthMode = clientAuthMode;
        this.port = port;
        this.maxClients = maxClients;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.socketFactory = socketFactory;
        this.router = router;
        this.rateLimiters = rateLimiters;
        // Số vé = số kết nối tối đa.
        this.connectionSlots = new Semaphore(maxClients);
        // Virtual thread mặc định hoặc platform thread theo cấu hình.
        this.clientThreads = ServerThreads.newConnectionExecutor();
    }

    /** Mở cổng lắng nghe. Trả về cổng thực tế (hữu ích khi port = 0 trong test). */
    public int bind() throws IOException {
        // Tạo ServerSocket chưa gắn cổng.
        ServerSocket socket = socketFactory.createServerSocket();
        // Là socket TLS thì cấu hình thêm.
        if (socket instanceof SSLServerSocket sslSocket) {
            // Chỉ cho phép TLS 1.3.
            sslSocket.setEnabledProtocols(ProtocolConstants.TLS_PROTOCOLS);
            // Chứng chỉ thiết bị (mutual TLS): "want" xin nhưng không bắt buộc ở bước bắt tay;
            // quyết định cho phép nằm ở ClientHandler (để báo lỗi rõ ràng thay vì cắt kết nối).
            sslSocket.setWantClientAuth(!"off".equals(clientAuthMode));
        }
        // Cho phép bind lại cổng ngay sau khi Server cũ vừa tắt (cổng còn ở TIME_WAIT).
        socket.setReuseAddress(true);
        // Gắn vào cổng trên mọi địa chỉ mạng của máy (0.0.0.0).
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
                // Chặn chờ một kết nối mới.
                client = serverSocket.accept();
            } catch (IOException error) {
                // Lỗi khi đang chạy: ghi log rồi chờ kết nối tiếp.
                if (running) {
                    System.err.println("[MailServer] accept() loi: " + error.getMessage());
                    continue;
                }
                break; // stop() đã đóng ServerSocket
            }
            // Xử lý kết nối mới.
            handleNewConnection(client);
        }
    }

    // Cấu hình socket và giao cho một ClientHandler (hoặc từ chối nếu quá tải).
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

        // Hết vé: từ chối.
        if (!connectionSlots.tryAcquire()) {
            com.mailsystem.server.monitor.ServerMetrics.get().recordConnection(false);
            // Gửi lỗi trên thread pool: với TLS, ghi dữ liệu cần bắt tay xong,
            // không được để client chậm chặn vòng accept().
            clientThreads.execute(() -> rejectBusy(client));
            return;
        }
        System.out.println("[MailServer] Client moi: " + client.getRemoteSocketAddress()
                + " (dang mo " + (maxClients - connectionSlots.availablePermits()) + " ket noi)");

        // Mảng 1 phần tử để lambda "khi đóng" bên dưới tham chiếu được handler (tạo sau lambda).
        ClientHandler[] holder = new ClientHandler[1];
        com.mailsystem.server.monitor.ServerMetrics.get().recordConnection(true);
        // Tạo handler; lambda cuối chạy khi kết nối đóng: gỡ khỏi danh sách và trả vé.
        ClientHandler handler = new ClientHandler(client, router, rateLimiters.get(), clientThreads, clientAuthMode, () -> {
            connections.remove(holder[0]);
            connectionSlots.release();
        });
        holder[0] = handler;
        connections.add(handler);
        // Chạy handler trên một thread của pool.
        clientThreads.execute(handler);
    }

    /** Báo lỗi cho client bằng một frame rồi đóng, thay vì để client chờ vô ích. */
    private void rejectBusy(Socket client) {
        System.err.println("[MailServer] Tu choi " + client.getRemoteSocketAddress() + ": da du "
                + maxClients + " ket noi.");
        // try-with-resources đóng cả socket và luồng ghi.
        try (client; Writer writer = new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8)) {
            // Chờ tối đa 5 giây (không để client chậm giữ thread lâu).
            client.setSoTimeout(5_000);
            // Frame lỗi không có requestId (thông báo cấp kết nối).
            ProtocolUtil.writeFrame(writer, ProtocolUtil.toJson(ResponseMessage.error(null,
                    "Máy chủ đang quá tải, vui lòng thử lại sau.")));
        } catch (IOException ignored) {
            // Client đã đi: không cần làm gì.
        }
    }

    /** Dừng Server: đóng cổng, đóng mọi kết nối, dừng pool thread. */
    public void stop() {
        // Đã dừng rồi thì thôi.
        if (!running) {
            return;
        }
        running = false;
        System.out.println("[MailServer] Dang dung Server...");
        try {
            // Đóng ServerSocket làm accept() ném lỗi và vòng serve() thoát.
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        // Đóng từng kết nối.
        for (ClientHandler handler : connections) {
            handler.forceClose();
        }
        // Không nhận tác vụ mới; chờ tối đa 5 giây rồi ép dừng.
        clientThreads.shutdown();
        try {
            if (!clientThreads.awaitTermination(5, TimeUnit.SECONDS)) {
                clientThreads.shutdownNow();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    /** @return số kết nối đang mở */
    public int activeConnections() {
        return connections.size();
    }

    /** @return số kết nối tối đa */
    public int maxClients() {
        return maxClients;
    }

    /** Danh sách kết nối cho bảng giám sát. */
    public java.util.List<java.util.Map<String, Object>> connectionSnapshot() {
        // Mỗi kết nối thành một Map, sắp theo mã kết nối tăng dần.
        return connections.stream().map(ClientHandler::snapshot)
                .sorted(java.util.Comparator.comparing(info -> (Integer) info.get("id")))
                .collect(java.util.stream.Collectors.toList());
    }

    /** Quản trị viên ngắt một kết nối. */
    public boolean kick(int connectionId) {
        // Tìm kết nối theo mã rồi đóng.
        for (ClientHandler handler : connections) {
            if (handler.id() == connectionId) {
                handler.forceClose();
                return true;
            }
        }
        return false;
    }
}
