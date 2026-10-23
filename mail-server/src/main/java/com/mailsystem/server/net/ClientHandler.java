package com.mailsystem.server.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.ErrorCodes;
import com.mailsystem.common.protocol.EventMessage;
import com.mailsystem.common.protocol.EventName;
import com.mailsystem.common.protocol.FrameReader;
import com.mailsystem.common.protocol.FrameTooLargeException;
import com.mailsystem.common.protocol.MessageType;
import com.mailsystem.common.protocol.ProtocolConstants;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.RequestMessage;
import com.mailsystem.common.protocol.ResponseMessage;
import com.mailsystem.common.security.CertificateFingerprints;
import com.mailsystem.server.config.ServerConfig;
import com.mailsystem.server.monitor.ServerMetrics;
import com.mailsystem.server.security.DeviceRegistry;
import com.mailsystem.server.security.RateLimiter;
import com.mailsystem.server.security.SecurityLog;
import com.mailsystem.server.session.ClientSession;
import com.mailsystem.server.session.ResumeTokens;
import com.mailsystem.server.session.SessionManager;
import com.mailsystem.server.transfer.UploadManager;
import com.mailsystem.server.transfer.UploadRegistry;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;

/**
 * Xử lý MỘT kết nối Client (đã bọc TLS). Mỗi kết nối dùng hai tác vụ (virtual
 * thread mặc định, xem ServerThreads):
 *
 * - Đọc (run()): bắt tay TLS, kiểm tra thiết bị (mutual TLS), rồi đọc từng
 *   frame JSON (1 frame = 1 REQUEST), giao cho CommandRouter và xếp RESPONSE
 *   vào hàng đợi gửi. Các request của một client được xử lý tuần tự.
 * - Ghi (writeLoop()): lấy frame từ hàng đợi và ghi xuống socket.
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
    private static final AtomicInteger IDS = new AtomicInteger();

    private final int id = IDS.incrementAndGet();
    private final Socket socket;
    private final String remoteAddress;
    private final String remoteIp;
    private final CommandRouter router;
    private final RateLimiter rateLimiter;
    private final Executor executor;
    private final String clientAuthMode;
    private final Runnable onClose;
    private final BlockingQueue<String> outbound = new LinkedBlockingQueue<>(OUTBOUND_QUEUE_CAPACITY);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LocalDateTime connectedAt = LocalDateTime.now().withNano(0);
    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicLong bytesIn = new AtomicLong();
    private final AtomicLong bytesOut = new AtomicLong();

    private volatile Integer loggedInAccountId; // null nghĩa là chưa LOGIN
    private volatile String resumeTokenHash;
    private volatile String deviceFingerprint;
    private volatile boolean deviceApproved;
    private volatile String cipherSuite;
    private int rateLimitStrikes;

    public ClientHandler(Socket socket, CommandRouter router, RateLimiter rateLimiter, Executor executor,
            String clientAuthMode, Runnable onClose) {
        this.socket = socket;
        this.router = router;
        this.rateLimiter = rateLimiter;
        this.executor = executor;
        this.clientAuthMode = clientAuthMode;
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
        executor.execute(this::writeLoop);

        String reason = "client dong ket noi";
        try {
            if (socket instanceof SSLSocket ssl) {
                // Bắt tay TLS ngay để biết chứng chỉ thiết bị TRƯỚC khi nhận bất kỳ request nào.
                ssl.startHandshake();
                cipherSuite = ssl.getSession().getCipherSuite();
                if (!checkDevice(ssl)) {
                    return;
                }
            }
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
                long size = frame.length() + 1L;
                bytesIn.addAndGet(size);
                ServerMetrics.get().addBytesIn(size);
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

    /**
     * Mutual TLS: ghi nhận chứng chỉ thiết bị (nếu có). Ở chế độ "need", thiết bị
     * chưa được phê duyệt trên bảng giám sát bị từ chối ngay.
     */
    private boolean checkDevice(SSLSocket ssl) {
        if ("off".equals(clientAuthMode)) {
            return true;
        }
        try {
            Certificate[] chain = ssl.getSession().getPeerCertificates();
            if (chain.length > 0 && chain[0] instanceof X509Certificate certificate) {
                deviceFingerprint = CertificateFingerprints.sha256(certificate);
                deviceApproved = DeviceRegistry.recordSeen(deviceFingerprint,
                        certificate.getSubjectX500Principal().getName(), remoteIp);
            }
        } catch (SSLPeerUnverifiedException noCertificate) {
            deviceFingerprint = null;
        }
        if ("need".equals(clientAuthMode) && !deviceApproved) {
            SecurityLog.event("DEVICE_REJECTED", remoteIp, "fingerprint=" + deviceFingerprint);
            enqueue(ResponseMessage.error(null, ErrorCodes.DEVICE_NOT_APPROVED, deviceFingerprint == null
                    ? "Máy chủ yêu cầu chứng chỉ thiết bị. Hãy dùng ứng dụng Mail System chính thức."
                    : "Thiết bị này chưa được quản trị viên phê duyệt. Dấu vân tay thiết bị: " + deviceFingerprint));
            close();
            return false;
        }
        return true;
    }

    private void handleFrame(String frame) {
        if (!rateLimiter.tryAcquire()) {
            ServerMetrics.get().recordRateLimited();
            // Không đọc nội dung frame: trả lỗi nhanh, đóng kết nối nếu cố tình spam.
            if (++rateLimitStrikes == 1 || rateLimitStrikes % 100 == 0) {
                SecurityLog.event("RATE_LIMITED", remoteIp, "strikes=" + rateLimitStrikes);
            }
            if (rateLimitStrikes > 1_000) {
                forceClose();
                return;
            }
            enqueue(ResponseMessage.error(requestIdOf(frame), ErrorCodes.RATE_LIMITED,
                    "Bạn gửi yêu cầu quá nhanh, vui lòng chậm lại."));
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

        long started = System.nanoTime();
        ResponseMessage response;
        try {
            response = router.dispatch(command, requestId, request.getPayload(), this);
        } catch (RuntimeException error) {
            // Không trả chi tiết lỗi nội bộ về client (tránh lộ thông tin hệ thống).
            System.err.println("[ClientHandler] " + command + " loi (requestId=" + requestId + "): " + error);
            error.printStackTrace(System.err);
            response = ResponseMessage.error(requestId, "Lỗi xử lý yêu cầu trên máy chủ.");
        }
        requestCount.incrementAndGet();
        ServerMetrics.get().recordRequest(command, System.nanoTime() - started, response.isOk());
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
        Integer account = loggedInAccountId;
        if (account == null) {
            throw new IllegalStateException("Chưa đăng nhập");
        }
        return UploadRegistry.forAccount(account);
    }

    @Override
    public ClientSession session() {
        return this;
    }

    @Override
    public void signIn(int accountId) {
        switchAccount(accountId);
        if (deviceFingerprint != null) {
            DeviceRegistry.recordAccount(deviceFingerprint, accountId);
        }
    }

    @Override
    public void signOut() {
        Integer previous = loggedInAccountId;
        switchAccount(null);
        if (previous != null) {
            // Đăng xuất chủ động: bỏ tệp dở dang và token tiếp tục phiên của kết nối này.
            UploadRegistry.discard(previous);
            ResumeTokens.get().revoke(resumeTokenHash);
            resumeTokenHash = null;
        }
    }

    @Override
    public void setResumeTokenHash(String hash) {
        this.resumeTokenHash = hash;
    }

    @Override
    public String deviceFingerprint() {
        return deviceFingerprint;
    }

    /** Đổi tài khoản của phiên (đăng nhập/đăng xuất) và cập nhật SessionManager. */
    private synchronized void switchAccount(Integer newAccountId) {
        SessionManager.getInstance().unregister(loggedInAccountId, this);
        loggedInAccountId = newAccountId;
        if (newAccountId != null) {
            SessionManager.getInstance().register(newAccountId, this);
        }
    }

    // ------------------------------------------------------------------ ClientSession

    /** Gửi EVENT chủ động (gọi từ SessionManager, có thể từ thread của client khác). */
    @Override
    public void sendEvent(EventMessage event) {
        ServerMetrics.get().recordEventPushed();
        enqueue(event);
    }

    @Override
    public void revoke(String reason) {
        JsonObject data = new JsonObject();
        data.addProperty("reason", reason);
        enqueue(new EventMessage(EventName.SESSION_REVOKED, data));
        switchAccount(null);
        close();
    }

    // ------------------------------------------------------------------ giám sát

    public int id() {
        return id;
    }

    /** Thông tin kết nối cho bảng giám sát. */
    public Map<String, Object> snapshot() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", id);
        info.put("remote", remoteAddress);
        info.put("accountId", loggedInAccountId);
        info.put("connectedAt", connectedAt.toString());
        info.put("requests", requestCount.get());
        info.put("bytesIn", bytesIn.get());
        info.put("bytesOut", bytesOut.get());
        info.put("cipher", cipherSuite);
        info.put("device", deviceFingerprint);
        info.put("deviceApproved", deviceApproved);
        return info;
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
                bytesOut.addAndGet(frame.length() + 1L);
                ServerMetrics.get().addBytesOut(frame.length() + 1L);
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
     * Đóng kết nối "lịch sự": gỡ phiên rồi để thread ghi gửi nốt các frame đang
     * chờ trước khi đóng socket. Tệp đang tải lên và token tiếp tục phiên được
     * GIỮ LẠI để Client kết nối lại và làm tiếp (xem UploadRegistry, ResumeTokens).
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        SessionManager.getInstance().unregister(loggedInAccountId, this);
        if (!outbound.offer(END_OF_STREAM)) {
            closeSocketQuietly();
        }
        onClose.run();
    }

    /** Đóng ngay lập tức (dừng Server, client không đọc kịp, quản trị viên ngắt). */
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
