// Gói "network": kết nối mạng tới Server.
package com.mailsystem.client.network;

// Gson; các lớp giao thức dùng chung (lệnh, EVENT, đọc frame, kiểu tin, hằng số, REQUEST, RESPONSE).
import com.google.gson.JsonElement;
import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.EventMessage;
import com.mailsystem.common.protocol.FrameReader;
import com.mailsystem.common.protocol.FrameTooLargeException;
import com.mailsystem.common.protocol.MessageType;
import com.mailsystem.common.protocol.ProtocolConstants;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.RequestMessage;
import com.mailsystem.common.protocol.ResponseMessage;

// Vào/ra, socket, bảng mã, danh sách, Map, UUID, CompletableFuture, bảng đồng thời, lịch chạy, Consumer.
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * LỚP DUY NHẤT giao tiếp Socket với Server. Các Controller không tự mở socket.
 *
 * Mô hình bất đồng bộ trên MỘT kết nối TCP:
 * - sendRequest() gắn requestId (UUID), cất CompletableFuture vào
 *   pendingRequests, ghi frame rồi trả future về ngay (không chặn UI thread).
 * - Thread nghe (listener) đọc liên tục từng frame:
 *     RESPONSE -> tìm future theo requestId và complete();
 *     EVENT    -> chuyển cho eventListener (Controller tự Platform.runLater).
 *   Nhờ requestId, response có thể về theo thứ tự bất kỳ và EVENT có thể chen
 *   vào giữa mà không lẫn lộn.
 * - Mất kết nối: mọi request đang chờ bị completeExceptionally ngay (UI không
 *   treo), rồi disconnectListener được gọi (MailClientApp cho Reconnector tự kết nối
 *   lại bằng token; không được thì quay về màn hình đăng nhập).
 * - Heartbeat: PING định kỳ để phát hiện kết nối "chết" mà TCP chưa báo.
 */
public class ServerConnection {

    // Chờ kết nối tối đa 5 giây; chờ RESPONSE tối đa 30 giây; chờ trả lời PING tối đa 10 giây.
    public static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    public static final long REQUEST_TIMEOUT_SECONDS = 30;
    private static final long HEARTBEAT_TIMEOUT_SECONDS = 10;
    /** Frame Server gửi về lớn nhất là 1 khối tệp tải xuống; để dư gấp đôi. */
    private static final int MAX_INBOUND_FRAME_CHARS = 2 * ProtocolConstants.MAX_FRAME_CHARS;

    // Đối tượng duy nhất của ứng dụng.
    private static final ServerConnection INSTANCE = new ServerConnection();

    // Khoá bảo vệ các field kết nối.
    private final Object lock = new Object();
    // Các request đang chờ RESPONSE: requestId -> future.
    private final Map<String, CompletableFuture<ResponseMessage>> pendingRequests = new ConcurrentHashMap<>();
    // Thread nền (daemon) gửi PING định kỳ.
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "mail-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    // Các field dưới đây chỉ đọc/ghi khi giữ lock.
    // Socket; bộ ghi; host và cổng; số thế hệ kết nối; tác vụ PING; thông báo cấp kết nối từ Server;
    // thông tin bảo mật TLS.
    private Socket socket;
    private Writer out;
    private String host;
    private int port;
    private long generation;
    private ScheduledFuture<?> heartbeatTask;
    private String serverNotice;
    private Connector.SecurityInfo security;

    // Bộ nghe EVENT và bộ nghe mất kết nối (volatile: đọc từ thread nghe, ghi từ luồng giao diện).
    private volatile Consumer<EventMessage> eventListener;
    private volatile Consumer<String> disconnectListener;
    // Chu kỳ PING (giây); cách mở kết nối (TLS hoặc TCP thường khi kiểm thử).
    private final int heartbeatIntervalSeconds;
    private final Connector connector;

    /** Constructor dùng cho kiểm thử (chu kỳ PING và Connector tuỳ chọn). */
    ServerConnection(int heartbeatIntervalSeconds, Connector connector) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        this.connector = connector;
    }

    /** Constructor mặc định: chu kỳ PING chuẩn, kết nối TLS với danh sách máy chủ đã tin và chứng chỉ thiết bị. */
    private ServerConnection() {
        this(ProtocolConstants.HEARTBEAT_INTERVAL_SECONDS, new TlsConnector(KnownServers.inUserHome(), DeviceIdentity::inUserHome));
    }

    /** Bộ kết nối TLS (để giao diện gắn hộp thoại xác nhận chứng chỉ). */
    public TlsConnector tlsConnector() {
        // Chỉ trả về khi đang dùng TLS.
        return connector instanceof TlsConnector tls ? tls : null;
    }

    /** Thông tin kênh mã hoá của kết nối hiện tại (null nếu chưa kết nối). */
    public Connector.SecurityInfo securityInfo() {
        synchronized (lock) {
            // Chưa kết nối thì null.
            return socket == null ? null : security;
        }
    }

    /** "host:port" đang kết nối, hoặc null. */
    public String connectedAddress() {
        synchronized (lock) {
            return socket == null ? null : host + ":" + port;
        }
    }

    /** @return đối tượng duy nhất */
    public static ServerConnection getInstance() {
        return INSTANCE;
    }

    /** Kết nối (chặn tối đa CONNECT_TIMEOUT_MILLIS). Không gọi trên UI thread. */
    public void connect(String host, int port) throws IOException {
        // Đóng kết nối cũ (nếu có).
        disconnect();

        // Mở kết nối mới (TCP + bắt tay TLS).
        Connector.Connected connected = connector.connect(host, port, CONNECT_TIMEOUT_MILLIS);
        Socket newSocket = connected.socket();
        // Gửi ngay gói nhỏ (tắt Nagle); bật keep-alive của TCP.
        newSocket.setTcpNoDelay(true);
        newSocket.setKeepAlive(true);
        // Bộ đọc frame UTF-8, giới hạn kích thước frame.
        FrameReader reader = new FrameReader(
                new InputStreamReader(newSocket.getInputStream(), StandardCharsets.UTF_8),
                MAX_INBOUND_FRAME_CHARS);
        // Bộ ghi UTF-8 có đệm.
        Writer writer = new BufferedWriter(
                new OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8));

        // Ghi nhận kết nối mới trong khoá.
        long connectionGeneration;
        synchronized (lock) {
            socket = newSocket;
            out = writer;
            this.host = host;
            this.port = port;
            // Xoá thông báo cũ, lưu thông tin bảo mật, tăng số thế hệ.
            serverNotice = null;
            security = connected.security();
            connectionGeneration = ++generation;
            // Bắt đầu gửi PING định kỳ.
            heartbeatTask = heartbeatScheduler.scheduleWithFixedDelay(this::sendHeartbeat,
                    heartbeatIntervalSeconds, heartbeatIntervalSeconds, TimeUnit.SECONDS);
        }

        // Thread nghe (daemon) cho kết nối thế hệ này.
        Thread listener = new Thread(() -> listenLoop(reader, connectionGeneration), "mail-listener");
        listener.setDaemon(true);
        listener.start();
    }

    /** Kết nối trên thread nền; hoàn tất ngay nếu đã kết nối đúng địa chỉ. */
    public CompletableFuture<Void> ensureConnectedAsync(String host, int port) {
        // Đã kết nối đúng địa chỉ: xong ngay.
        if (isConnectedTo(host, port)) {
            return CompletableFuture.completedFuture(null);
        }
        // Kết nối trên thread nền.
        return CompletableFuture.runAsync(() -> {
            try {
                connect(host, port);
            } catch (IOException error) {
                // Lỗi: bọc thành ConnectionLostException kèm địa chỉ và lý do.
                throw new java.util.concurrent.CompletionException(error instanceof ConnectionLostException
                        ? error
                        : new ConnectionLostException("Không kết nối được tới " + host + ":" + port
                                + " (" + describe(error) + ")"));
            }
        });
    }

    /** Socket còn mở? */
    public boolean isConnected() {
        synchronized (lock) {
            return socket != null && !socket.isClosed();
        }
    }

    /** Đang kết nối đúng host:port này? */
    public boolean isConnectedTo(String host, int port) {
        synchronized (lock) {
            return socket != null && !socket.isClosed() && host.equals(this.host) && port == this.port;
        }
    }

    /** Chủ động đóng kết nối (đổi máy chủ, thoát ứng dụng). Không gọi disconnectListener. */
    public void disconnect() {
        long current;
        // Đóng kết nối của thế hệ hiện tại (notify = false).
        synchronized (lock) {
            current = generation;
        }
        closeConnection(current, "Đã ngắt kết nối khỏi máy chủ.", false);
    }

    // Vòng nghe: đọc frame tới khi hết luồng hoặc lỗi.
    private void listenLoop(FrameReader reader, long connectionGeneration) {
        // Lý do mặc định khi Server đóng kết nối.
        String reason = "Máy chủ đã đóng kết nối.";
        try {
            String frame;
            // readFrame trả null khi Server đóng kết nối.
            while ((frame = reader.readFrame()) != null) {
                try {
                    dispatchIncomingFrame(frame);
                } catch (RuntimeException error) {
                    // Một frame hỏng không được làm chết thread nghe.
                    System.err.println("[ServerConnection] Bo qua frame khong hop le: " + error.getMessage());
                }
            }
        // Frame quá lớn.
        } catch (FrameTooLargeException error) {
            reason = "Máy chủ gửi gói tin quá lớn.";
        // Lỗi mạng.
        } catch (IOException error) {
            reason = "Mất kết nối tới máy chủ (" + describe(error) + ").";
        }
        // Server đã gửi thông báo trước khi đóng: dùng làm lý do.
        synchronized (lock) {
            if (serverNotice != null) {
                reason = serverNotice;
            }
        }
        // Dọn dẹp và báo mất kết nối (notify = true).
        closeConnection(connectionGeneration, reason, true);
    }

    private void dispatchIncomingFrame(String frame) {
        // Xem kiểu tin mà chưa phân tích toàn bộ.
        MessageType type = ProtocolUtil.peekType(frame);
        if (type == MessageType.RESPONSE) {
            // RESPONSE: phân tích JSON.
            ResponseMessage response = ProtocolUtil.fromJson(frame, ResponseMessage.class);
            if (response.getRequestId() == null) {
                // Thông báo cấp kết nối (VD: Server quá tải, gói tin sai định dạng).
                System.err.println("[ServerConnection] Thong bao tu may chu: " + response.getMessage());
                synchronized (lock) {
                    serverNotice = response.getMessage();
                }
                return;
            }
            // Lấy và xoá future chờ theo requestId; còn thì hoàn tất bằng response.
            CompletableFuture<ResponseMessage> future = pendingRequests.remove(response.getRequestId());
            if (future != null) {
                future.complete(response);
            }
        } else if (type == MessageType.EVENT) {
            // EVENT: phân tích JSON và chuyển cho bộ nghe hiện tại.
            EventMessage event = ProtocolUtil.fromJson(frame, EventMessage.class);
            Consumer<EventMessage> listener = eventListener;
            if (listener != null) {
                listener.accept(event); // listener tự bọc Platform.runLater nếu cập nhật UI
            }
        }
    }

    /**
     * Đóng kết nối thuộc thế hệ connectionGeneration. Thế hệ giúp thread nghe
     * của một kết nối CŨ (đã bị thay bằng kết nối mới) không đóng nhầm kết nối mới.
     */
    private void closeConnection(long connectionGeneration, String reason, boolean notify) {
        synchronized (lock) {
            // Không phải kết nối hiện tại hoặc đã đóng: bỏ qua.
            if (connectionGeneration != generation || socket == null) {
                return;
            }
            try {
                // Đóng socket (lỗi khi đóng thì bỏ qua).
                socket.close();
            } catch (IOException ignored) {
            }
            // Xoá socket, bộ ghi; huỷ tác vụ PING.
            socket = null;
            out = null;
            if (heartbeatTask != null) {
                heartbeatTask.cancel(false);
                heartbeatTask = null;
            }
        }

        // Lấy và xoá mọi request đang chờ, báo lỗi mất kết nối cho từng cái.
        List<CompletableFuture<ResponseMessage>> waiting = new ArrayList<>(pendingRequests.values());
        pendingRequests.clear();
        ConnectionLostException cause = new ConnectionLostException(reason);
        for (CompletableFuture<ResponseMessage> future : waiting) {
            future.completeExceptionally(cause);
        }

        // Mất kết nối ngoài ý muốn: gọi bộ nghe (MailClientApp: tự kết nối lại).
        Consumer<String> listener = disconnectListener;
        if (notify && listener != null) {
            listener.accept(reason);
        }
    }

    /** Đăng ký callback nhận EVENT; Controller đang hiển thị tự set lại hàm này. */
    public void setEventListener(Consumer<EventMessage> listener) {
        this.eventListener = listener;
    }

    /** Callback khi kết nối mất ngoài ý muốn (nhận lý do để hiển thị). */
    public void setDisconnectListener(Consumer<String> listener) {
        this.disconnectListener = listener;
    }

    /**
     * Gửi 1 REQUEST, trả về CompletableFuture để Controller xử lý bất đồng bộ.
     * Future hoàn tất ngoại lệ khi: chưa kết nối, mất kết nối, hoặc quá
     * REQUEST_TIMEOUT_SECONDS mà chưa có RESPONSE. Không bao giờ ném exception.
     */
    public CompletableFuture<ResponseMessage> sendRequest(Command command, JsonElement payload) {
        // Tạo requestId ngẫu nhiên; đóng gói REQUEST thành JSON; tạo future.
        String requestId = UUID.randomUUID().toString();
        String frame = ProtocolUtil.toJson(new RequestMessage(requestId, command, payload));
        CompletableFuture<ResponseMessage> future = new CompletableFuture<>();
        long connectionGeneration;

        synchronized (lock) {
            // Chưa kết nối: trả future lỗi ngay.
            if (out == null) {
                return CompletableFuture.failedFuture(new ConnectionLostException("Chưa kết nối tới máy chủ."));
            }
            // Ghi lại thế hệ (dùng cho log).
            connectionGeneration = generation;
            // Đăng ký TRƯỚC khi gửi: response có thể về trước khi write() trả về.
            pendingRequests.put(requestId, future);
            try {
                // Ghi frame (một dòng JSON) và flush.
                ProtocolUtil.writeFrame(out, frame);
            } catch (IOException error) {
                // Ghi lỗi: bỏ đăng ký, báo lỗi, đóng socket.
                pendingRequests.remove(requestId);
                future.completeExceptionally(new ConnectionLostException("Không gửi được yêu cầu tới máy chủ."));
                closeSocketQuietly(); // thread nghe sẽ phát hiện và dọn dẹp
                return future;
            }
        }

        // Giới hạn thời gian chờ; xong (kể cả lỗi / hết giờ) thì bỏ đăng ký; hết giờ thì ghi log.
        return future.orTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((response, error) -> {
                    pendingRequests.remove(requestId);
                    if (error instanceof TimeoutException) {
                        System.err.println("[ServerConnection] " + command + " qua thoi gian cho (the he "
                                + connectionGeneration + ")");
                    }
                });
    }

    private void sendHeartbeat() {
        // Gửi PING, chờ tối đa HEARTBEAT_TIMEOUT_SECONDS.
        sendRequest(Command.PING, null)
                .orTimeout(HEARTBEAT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((response, error) -> {
                    // Hết giờ (trực tiếp hoặc bọc trong lỗi khác).
                    if (error instanceof TimeoutException
                            || (error != null && error.getCause() instanceof TimeoutException)) {
                        // Kết nối "chết" (half-open): đóng socket, thread nghe sẽ báo mất kết nối.
                        System.err.println("[ServerConnection] May chu khong tra loi PING, dong ket noi.");
                        closeSocketQuietly();
                    }
                });
    }

    private void closeSocketQuietly() {
        // Đóng socket nếu còn (không dọn dẹp: thread nghe sẽ làm).
        synchronized (lock) {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // Mô tả lỗi: câu báo, hoặc tên lớp lỗi nếu không có câu báo.
    private static String describe(IOException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
