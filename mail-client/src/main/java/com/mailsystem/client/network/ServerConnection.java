package com.mailsystem.client.network;

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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
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
 *   treo), rồi disconnectListener được gọi để ứng dụng quay về màn hình đăng nhập.
 * - Heartbeat: PING định kỳ để phát hiện kết nối "chết" mà TCP chưa báo.
 */
public class ServerConnection {

    public static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    public static final long REQUEST_TIMEOUT_SECONDS = 30;
    private static final long HEARTBEAT_TIMEOUT_SECONDS = 10;
    /** Frame Server gửi về lớn nhất là 1 khối tệp tải xuống; để dư gấp đôi. */
    private static final int MAX_INBOUND_FRAME_CHARS = 2 * ProtocolConstants.MAX_FRAME_CHARS;

    private static final ServerConnection INSTANCE = new ServerConnection();

    private final Object lock = new Object();
    private final Map<String, CompletableFuture<ResponseMessage>> pendingRequests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "mail-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    // Các field dưới đây chỉ đọc/ghi khi giữ lock.
    private Socket socket;
    private Writer out;
    private String host;
    private int port;
    private long generation;
    private ScheduledFuture<?> heartbeatTask;
    private String serverNotice;

    private volatile Consumer<EventMessage> eventListener;
    private volatile Consumer<String> disconnectListener;
    private final int heartbeatIntervalSeconds;

    ServerConnection(int heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
    }

    private ServerConnection() {
        this(ProtocolConstants.HEARTBEAT_INTERVAL_SECONDS);
    }

    public static ServerConnection getInstance() {
        return INSTANCE;
    }

    /** Kết nối (chặn tối đa CONNECT_TIMEOUT_MILLIS). Không gọi trên UI thread. */
    public void connect(String host, int port) throws IOException {
        disconnect();

        Socket newSocket = new Socket();
        newSocket.setTcpNoDelay(true);
        newSocket.setKeepAlive(true);
        try {
            newSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
        } catch (IOException error) {
            newSocket.close();
            throw error;
        }
        FrameReader reader = new FrameReader(
                new InputStreamReader(newSocket.getInputStream(), StandardCharsets.UTF_8),
                MAX_INBOUND_FRAME_CHARS);
        Writer writer = new BufferedWriter(
                new OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8));

        long connectionGeneration;
        synchronized (lock) {
            socket = newSocket;
            out = writer;
            this.host = host;
            this.port = port;
            serverNotice = null;
            connectionGeneration = ++generation;
            heartbeatTask = heartbeatScheduler.scheduleWithFixedDelay(this::sendHeartbeat,
                    heartbeatIntervalSeconds, heartbeatIntervalSeconds, TimeUnit.SECONDS);
        }

        Thread listener = new Thread(() -> listenLoop(reader, connectionGeneration), "mail-listener");
        listener.setDaemon(true);
        listener.start();
    }

    /** Kết nối trên thread nền; hoàn tất ngay nếu đã kết nối đúng địa chỉ. */
    public CompletableFuture<Void> ensureConnectedAsync(String host, int port) {
        if (isConnectedTo(host, port)) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                connect(host, port);
            } catch (IOException error) {
                throw new java.util.concurrent.CompletionException(
                        new ConnectionLostException("Không kết nối được tới " + host + ":" + port
                                + " (" + describe(error) + ")"));
            }
        });
    }

    public boolean isConnected() {
        synchronized (lock) {
            return socket != null && !socket.isClosed();
        }
    }

    public boolean isConnectedTo(String host, int port) {
        synchronized (lock) {
            return socket != null && !socket.isClosed() && host.equals(this.host) && port == this.port;
        }
    }

    /** Chủ động đóng kết nối (đổi máy chủ, thoát ứng dụng). Không gọi disconnectListener. */
    public void disconnect() {
        long current;
        synchronized (lock) {
            current = generation;
        }
        closeConnection(current, "Đã ngắt kết nối khỏi máy chủ.", false);
    }

    private void listenLoop(FrameReader reader, long connectionGeneration) {
        String reason = "Máy chủ đã đóng kết nối.";
        try {
            String frame;
            while ((frame = reader.readFrame()) != null) {
                try {
                    dispatchIncomingFrame(frame);
                } catch (RuntimeException error) {
                    // Một frame hỏng không được làm chết thread nghe.
                    System.err.println("[ServerConnection] Bo qua frame khong hop le: " + error.getMessage());
                }
            }
        } catch (FrameTooLargeException error) {
            reason = "Máy chủ gửi gói tin quá lớn.";
        } catch (IOException error) {
            reason = "Mất kết nối tới máy chủ (" + describe(error) + ").";
        }
        synchronized (lock) {
            if (serverNotice != null) {
                reason = serverNotice;
            }
        }
        closeConnection(connectionGeneration, reason, true);
    }

    private void dispatchIncomingFrame(String frame) {
        MessageType type = ProtocolUtil.peekType(frame);
        if (type == MessageType.RESPONSE) {
            ResponseMessage response = ProtocolUtil.fromJson(frame, ResponseMessage.class);
            if (response.getRequestId() == null) {
                // Thông báo cấp kết nối (VD: Server quá tải, gói tin sai định dạng).
                System.err.println("[ServerConnection] Thong bao tu may chu: " + response.getMessage());
                synchronized (lock) {
                    serverNotice = response.getMessage();
                }
                return;
            }
            CompletableFuture<ResponseMessage> future = pendingRequests.remove(response.getRequestId());
            if (future != null) {
                future.complete(response);
            }
        } else if (type == MessageType.EVENT) {
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
            if (connectionGeneration != generation || socket == null) {
                return;
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            socket = null;
            out = null;
            if (heartbeatTask != null) {
                heartbeatTask.cancel(false);
                heartbeatTask = null;
            }
        }

        List<CompletableFuture<ResponseMessage>> waiting = new ArrayList<>(pendingRequests.values());
        pendingRequests.clear();
        ConnectionLostException cause = new ConnectionLostException(reason);
        for (CompletableFuture<ResponseMessage> future : waiting) {
            future.completeExceptionally(cause);
        }

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
        String requestId = UUID.randomUUID().toString();
        String frame = ProtocolUtil.toJson(new RequestMessage(requestId, command, payload));
        CompletableFuture<ResponseMessage> future = new CompletableFuture<>();
        long connectionGeneration;

        synchronized (lock) {
            if (out == null) {
                return CompletableFuture.failedFuture(new ConnectionLostException("Chưa kết nối tới máy chủ."));
            }
            connectionGeneration = generation;
            // Đăng ký TRƯỚC khi gửi: response có thể về trước khi write() trả về.
            pendingRequests.put(requestId, future);
            try {
                ProtocolUtil.writeFrame(out, frame);
            } catch (IOException error) {
                pendingRequests.remove(requestId);
                future.completeExceptionally(new ConnectionLostException("Không gửi được yêu cầu tới máy chủ."));
                closeSocketQuietly(); // thread nghe sẽ phát hiện và dọn dẹp
                return future;
            }
        }

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
        sendRequest(Command.PING, null)
                .orTimeout(HEARTBEAT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((response, error) -> {
                    if (error instanceof TimeoutException
                            || (error != null && error.getCause() instanceof TimeoutException)) {
                        // Kết nối "chết" (half-open): đóng socket, thread nghe sẽ báo mất kết nối.
                        System.err.println("[ServerConnection] May chu khong tra loi PING, dong ket noi.");
                        closeSocketQuietly();
                    }
                });
    }

    private void closeSocketQuietly() {
        synchronized (lock) {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String describe(IOException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
