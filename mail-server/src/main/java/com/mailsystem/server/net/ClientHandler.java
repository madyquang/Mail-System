package com.mailsystem.server.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.EventMessage;
import com.mailsystem.common.protocol.EventName;
import com.mailsystem.common.protocol.FrameReader;
import com.mailsystem.common.protocol.FrameTooLargeException;
import com.mailsystem.common.protocol.MessageType;
import com.mailsystem.common.protocol.ProtocolConstants;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.RequestMessage;
import com.mailsystem.common.protocol.ResponseMessage;
import com.mailsystem.server.config.ServerConfig;
import com.mailsystem.server.security.RateLimiter;
import com.mailsystem.server.security.SecurityLog;
import com.mailsystem.server.session.ClientSession;
import com.mailsystem.server.session.SessionManager;
import com.mailsystem.server.transfer.UploadManager;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;

/**
 * Xử lý MỘT kết nối Client (đã bọc TLS). Mỗi kết nối dùng hai thread:
 *
 * - Thread đọc (run()): đọc từng frame JSON (1 frame = 1 REQUEST), giao cho
 *   CommandRouter và xếp RESPONSE vào hàng đợi gửi. Các request của một client
 *   được xử lý tuần tự theo đúng thứ tự gửi.
 * - Thread ghi (writeLoop()): lấy frame từ hàng đợi và ghi xuống socket.
 *
 * Vì sao cần hàng đợi gửi: EVENT được đẩy từ thread của client KHÁC (VD: A gửi
 * thư thì thread của A đẩy NEW_MAIL tới B). Nếu thread của A ghi thẳng vào
 * socket của B mà B mạng chậm (bộ đệm gửi TCP đầy), thread của A bị chặn theo.
 * Với hàng đợi, sendEvent() chỉ offer() rồi trả về ngay; nếu hàng đợi đầy
 * (client không đọc kịp) thì kết nối đó bị đóng thay vì làm nghẽn cả Server.
 */
public class ClientHandler implements Runnable, ClientSession, ConnectionContext {

    private static final int OUTBOUND_QUEUE_CAPACITY = 256;
    /** Frame đặc biệt báo thread ghi: gửi nốt hàng đợi rồi đóng socket. */
    private static final String END_OF_STREAM = "\u0000END";

    private final Socket socket;
    private final String remoteAddress;
    private final String remoteIp;
    private final CommandRouter router;
    private final RateLimiter rateLimiter;
    private final Runnable onClose;
    private final BlockingQueue<String> outbound = new LinkedBlockingQueue<>(OUTBOUND_QUEUE_CAPACITY);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final UploadManager uploads = new UploadManager(ServerConfig.uploadTempDir());

    private volatile Integer loggedInAccountId; // null nghĩa là chưa LOGIN
    private int rateLimitStrikes;

    public ClientHandler(Socket socket, CommandRouter router, RateLimiter rateLimiter, Runnable onClose) {
        this.socket = socket;
        this.router = router;
        this.rateLimiter = rateLimiter;
        this.onClose = onClose;
        this.remoteAddress = socket.getRemoteSocketAddress() == null
                ? "unknown"
                : socket.getRemoteSocketAddress().toString();
        this.remoteIp = socket.getRemoteSocketAddress() instanceof InetSocketAddress address
                && address.getAddress() != null
                ? address.getAddress().getHostAddress()
                : "unknown";
    }

    @Override
    public void run() {
        Thread writer = new Thread(this::writeLoop, "mail-writer-" + remoteAddress);
        writer.setDaemon(true);
        writer.start();

        String reason = "client dong ket noi";
        try {
            // Với SSLSocket, lần đọc đầu tiên kích hoạt bắt tay TLS (handshake).
            FrameReader reader = new FrameReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8),
                    ProtocolConstants.MAX_FRAME_CHARS);
            while (!closed.get()) {
                String frame;
                try {
                    frame = reader.readFrame();
                } catch (FrameTooLargeException error) {
                    enqueue(ResponseMessage.error(null, "Gói tin vượt quá giới hạn "
                            + ProtocolConstants.MAX_FRAME_CHARS + " ký tự và đã bị bỏ qua."));
                    continue;
                }
                if (frame == null) {
                    break; // nhận FIN / close_notify: client đã đóng
                }
                if (!frame.isBlank()) {
                    handleFrame(frame);
                }
            }
        } catch (SocketTimeoutException error) {
            reason = "khong nhan duoc du lieu trong " + ServerConfig.idleTimeoutSeconds() + " giay";
        } catch (SSLException error) {
            reason = "TLS that bai: " + error.getMessage();
            SecurityLog.event("TLS_HANDSHAKE_FAILED", remoteIp, error.getMessage());
        } catch (IOException error) {
            reason = closed.get() ? "server dong ket noi" : "loi mang: " + error.getMessage();
        } finally {
            System.out.println("[ClientHandler] Dong ket noi " + remoteAddress + " (" + reason + ")");
            close();
        }
    }

    private void handleFrame(String frame) {
        if (!rateLimiter.tryAcquire()) {
            // Không đọc nội dung frame: trả lỗi nhanh, đóng kết nối nếu cố tình spam.
            if (++rateLimitStrikes == 1 || rateLimitStrikes % 100 == 0) {
                SecurityLog.event("RATE_LIMITED", remoteIp, "strikes=" + rateLimitStrikes);
            }
            if (rateLimitStrikes > 1_000) {
                forceClose();
                return;
            }
            enqueue(ResponseMessage.error(requestIdOf(frame), "Bạn gửi yêu cầu quá nhanh, vui lòng chậm lại."));
            return;
        }

        RequestMessage request;
        try {
            request = ProtocolUtil.fromJson(frame, RequestMessage.class);
        } catch (JsonParseException error) {
            enqueue(ResponseMessage.error(null, "Gói tin không phải JSON hợp lệ."));
            return;
        }
        if (request == null || !MessageType.REQUEST.name().equals(request.getType())) {
            enqueue(ResponseMessage.error(null, "Gói tin phải có type = REQUEST."));
            return;
        }

        String requestId = request.getRequestId();
        if (requestId != null && requestId.length() > 64) {
            enqueue(ResponseMessage.error(null, "requestId quá dài."));
            return;
        }
        Command command;
        try {
            command = request.getCommandEnum();
        } catch (IllegalArgumentException | NullPointerException error) {
            enqueue(ResponseMessage.error(requestId, "Command không được hỗ trợ: " + request.getCommand()));
            return;
        }

        ResponseMessage response;
        try {
            response = router.dispatch(command, requestId, request.getPayload(), this);
        } catch (RuntimeException error) {
            // Không trả chi tiết lỗi nội bộ về client (tránh lộ thông tin hệ thống).
            System.err.println("[ClientHandler] " + command + " loi (requestId=" + requestId + "): " + error);
            error.printStackTrace(System.err);
            response = ResponseMessage.error(requestId, "Lỗi xử lý yêu cầu trên máy chủ.");
        }
        enqueue(response);
    }

    private static String requestIdOf(String frame) {
        try {
            RequestMessage request = ProtocolUtil.fromJson(frame, RequestMessage.class);
            return request == null ? null : request.getRequestId();
        } catch (RuntimeException error) {
            return null;
        }
    }

    // ------------------------------------------------------------------ ConnectionContext

    @Override
    public Integer accountId() {
        return loggedInAccountId;
    }

    @Override
    public String remoteIp() {
        return remoteIp;
    }

    @Override
    public UploadManager uploads() {
        return uploads;
    }

    @Override
    public ClientSession session() {
        return this;
    }

    @Override
    public void signIn(int accountId) {
        switchAccount(accountId);
    }

    @Override
    public void signOut() {
        switchAccount(null);
    }

    /** Đổi tài khoản của phiên (đăng nhập/đăng xuất) và cập nhật SessionManager. */
    private synchronized void switchAccount(Integer newAccountId) {
        SessionManager.getInstance().unregister(loggedInAccountId, this);
        uploads.discardAll();
        loggedInAccountId = newAccountId;
        if (newAccountId != null) {
            SessionManager.getInstance().register(newAccountId, this);
        }
    }

    // ------------------------------------------------------------------ ClientSession

    /** Gửi EVENT chủ động (gọi từ SessionManager, có thể từ thread của client khác). */
    @Override
    public void sendEvent(EventMessage event) {
        enqueue(event);
    }

    @Override
    public void revoke(String reason) {
        JsonObject data = new JsonObject();
        data.addProperty("reason", reason);
        enqueue(new EventMessage(EventName.SESSION_REVOKED, data));
        signOut();
        close();
    }

    // ------------------------------------------------------------------ gửi & đóng

    private void enqueue(Object message) {
        if (closed.get()) {
            return;
        }
        if (!outbound.offer(ProtocolUtil.toJson(message))) {
            System.err.println("[ClientHandler] Hang doi gui cua " + remoteAddress
                    + " day (client khong doc kip), dong ket noi.");
            forceClose();
        }
    }

    private void writeLoop() {
        try (Writer writer = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            while (true) {
                String frame = outbound.take();
                if (END_OF_STREAM.equals(frame)) {
                    break;
                }
                writer.write(frame);
                writer.write('\n');
                // Gộp nhiều frame đang chờ vào một lần flush (ít bản ghi TLS / segment TCP hơn).
                if (outbound.isEmpty()) {
                    writer.flush();
                }
            }
            writer.flush();
        } catch (IOException error) {
            if (!closed.get()) {
                System.err.println("[ClientHandler] Khong ghi duoc toi " + remoteAddress + ": " + error.getMessage());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            close();
            closeSocketQuietly();
        }
    }

    /**
     * Đóng kết nối "lịch sự": gỡ phiên, xoá upload dở dang, rồi để thread ghi
     * gửi nốt các frame đang chờ trước khi đóng socket.
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        SessionManager.getInstance().unregister(loggedInAccountId, this);
        uploads.discardAll();
        if (!outbound.offer(END_OF_STREAM)) {
            closeSocketQuietly();
        }
        onClose.run();
    }

    /** Đóng ngay lập tức (dừng Server hoặc client không đọc kịp). */
    public void forceClose() {
        close();
        closeSocketQuietly();
    }

    private void closeSocketQuietly() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
