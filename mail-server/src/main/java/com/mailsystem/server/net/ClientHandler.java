// Gói "net": phần mạng của Server.
package com.mailsystem.server.net;

// JSON.
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
// Giao thức dùng chung.
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
// Thành phần Server: cấu hình, số liệu, bảo mật, phiên, tải lên.
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

// Vào/ra và socket.
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
// Chứng chỉ.
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
// Thời gian, Map giữ thứ tự.
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
// Đồng thời: hàng đợi chặn, bộ chạy tác vụ, biến nguyên tử.
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
// Lỗi TLS và socket TLS.
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

    // Hàng đợi gửi chứa tối đa 256 frame.
    private static final int OUTBOUND_QUEUE_CAPACITY = 256;
    /** Frame đặc biệt báo thread ghi: gửi nốt hàng đợi rồi đóng socket. */
    // (Bắt đầu bằng ký tự NUL nên không thể trùng một frame JSON thật.)
    private static final String END_OF_STREAM = "\u0000END";
    // Bộ đếm để đánh số kết nối 1, 2, 3...
    private static final AtomicInteger IDS = new AtomicInteger();

    // Mã kết nối (hiển thị trên trang giám sát).
    private final int id = IDS.incrementAndGet();
    // Socket của kết nối.
    private final Socket socket;
    // "/IP:cổng" của client (để in log).
    private final String remoteAddress;
    // Chỉ IP của client (để chống dò mật khẩu, nhật ký bảo mật).
    private final String remoteIp;
    // Bộ định tuyến lệnh dùng chung.
    private final CommandRouter router;
    // Bộ giới hạn tốc độ riêng của kết nối.
    private final RateLimiter rateLimiter;
    // Nơi chạy thread ghi.
    private final Executor executor;
    // Chế độ chứng chỉ thiết bị: off / want / need.
    private final String clientAuthMode;
    // Việc cần làm khi đóng (MailServer: gỡ khỏi danh sách, trả "vé").
    private final Runnable onClose;
    // Hàng đợi gửi (frame JSON chờ ghi xuống socket).
    private final BlockingQueue<String> outbound = new LinkedBlockingQueue<>(OUTBOUND_QUEUE_CAPACITY);
    // Đã đóng chưa (nguyên tử: chỉ đóng một lần).
    private final AtomicBoolean closed = new AtomicBoolean();
    // Thời điểm kết nối (bỏ phần nano giây).
    private final LocalDateTime connectedAt = LocalDateTime.now().withNano(0);
    // Số request đã xử lý, số byte vào / ra (cho trang giám sát).
    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicLong bytesIn = new AtomicLong();
    private final AtomicLong bytesOut = new AtomicLong();

    private volatile Integer loggedInAccountId; // null nghĩa là chưa LOGIN
    // Hash của token tiếp tục phiên đã cấp cho kết nối này.
    private volatile String resumeTokenHash;
    // Dấu vân tay chứng chỉ thiết bị (null nếu client không gửi).
    private volatile String deviceFingerprint;
    // Thiết bị đã được duyệt chưa.
    private volatile boolean deviceApproved;
    // Bộ mã hoá TLS đã thoả thuận.
    private volatile String cipherSuite;
    // Số lần bị giới hạn tốc độ (chỉ thread đọc dùng nên không cần volatile).
    private int rateLimitStrikes;

    /**
     * Tạo handler cho một kết nối.
     *
     * @param socket         socket đã accept
     * @param router         bộ định tuyến lệnh
     * @param rateLimiter    bộ giới hạn tốc độ riêng của kết nối
     * @param executor       nơi chạy thread ghi
     * @param clientAuthMode chế độ chứng chỉ thiết bị
     * @param onClose        việc cần làm khi kết nối đóng
     */
    public ClientHandler(Socket socket, CommandRouter router, RateLimiter rateLimiter, Executor executor,
            String clientAuthMode, Runnable onClose) {
        this.socket = socket;
        this.router = router;
        this.rateLimiter = rateLimiter;
        this.executor = executor;
        this.clientAuthMode = clientAuthMode;
        this.onClose = onClose;
        // Địa chỉ đầy đủ của client, hoặc "unknown".
        this.remoteAddress = socket.getRemoteSocketAddress() == null
                ? "unknown"
                : socket.getRemoteSocketAddress().toString();
        // Chỉ lấy IP (pattern matching: nếu là InetSocketAddress thì gán vào biến address).
        this.remoteIp = socket.getRemoteSocketAddress() instanceof InetSocketAddress address
                && address.getAddress() != null
                ? address.getAddress().getHostAddress()
                : "unknown";
    }

    /** Thread đọc: chạy suốt vòng đời kết nối. */
    @Override
    public void run() {
        // Khởi động thread ghi song song.
        executor.execute(this::writeLoop);

        // Lý do đóng kết nối (để in log).
        String reason = "client dong ket noi";
        try {
            if (socket instanceof SSLSocket ssl) {
                // Bắt tay TLS ngay để biết chứng chỉ thiết bị TRƯỚC khi nhận bất kỳ request nào.
                ssl.startHandshake();
                cipherSuite = ssl.getSession().getCipherSuite();
                // Thiết bị bị từ chối (chế độ need): dừng.
                if (!checkDevice(ssl)) {
                    return;
                }
            }
            // Bộ đọc frame UTF-8, giới hạn 1 MiB mỗi frame.
            FrameReader reader = new FrameReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8),
                    ProtocolConstants.MAX_FRAME_CHARS);
            // Đọc cho tới khi kết nối đóng.
            while (!closed.get()) {
                String frame;
                try {
                    // Chặn chờ frame kế tiếp.
                    frame = reader.readFrame();
                } catch (FrameTooLargeException error) {
                    // Frame quá lớn đã bị bỏ qua: báo lỗi và đọc tiếp (luồng vẫn đồng bộ).
                    enqueue(ResponseMessage.error(null, "Gói tin vượt quá giới hạn "
                            + ProtocolConstants.MAX_FRAME_CHARS + " ký tự và đã bị bỏ qua."));
                    continue;
                }
                if (frame == null) {
                    break; // nhận FIN / close_notify: client đã đóng
                }
                // Cộng số byte vào (+1 cho ký tự '\n').
                long size = frame.length() + 1L;
                bytesIn.addAndGet(size);
                ServerMetrics.get().addBytesIn(size);
                // Bỏ qua dòng trống.
                if (!frame.isBlank()) {
                    handleFrame(frame);
                }
            }
        } catch (SocketTimeoutException error) {
            // Im lặng quá idleTimeout.
            reason = "khong nhan duoc du lieu trong " + ServerConfig.idleTimeoutSeconds() + " giay";
        } catch (SSLException error) {
            // Bắt tay / mã hoá TLS lỗi (vd client không tin chứng chỉ).
            reason = "TLS that bai: " + error.getMessage();
            SecurityLog.event("TLS_HANDSHAKE_FAILED", remoteIp, error.getMessage());
        } catch (IOException error) {
            reason = closed.get() ? "server dong ket noi" : "loi mang: " + error.getMessage();
        } finally {
            // Luôn in log và đóng.
            System.out.println("[ClientHandler] Dong ket noi " + remoteAddress + " (" + reason + ")");
            close();
        }
    }

    /**
     * Mutual TLS: ghi nhận chứng chỉ thiết bị (nếu có). Ở chế độ "need", thiết bị
     * chưa được phê duyệt trên bảng giám sát bị từ chối ngay.
     */
    private boolean checkDevice(SSLSocket ssl) {
        // Tắt xác thực thiết bị: luôn cho qua.
        if ("off".equals(clientAuthMode)) {
            return true;
        }
        try {
            // Chuỗi chứng chỉ client gửi (ném lỗi nếu client không gửi).
            Certificate[] chain = ssl.getSession().getPeerCertificates();
            if (chain.length > 0 && chain[0] instanceof X509Certificate certificate) {
                // Tính dấu vân tay, ghi nhận vào CSDL và đọc trạng thái duyệt.
                deviceFingerprint = CertificateFingerprints.sha256(certificate);
                deviceApproved = DeviceRegistry.recordSeen(deviceFingerprint,
                        certificate.getSubjectX500Principal().getName(), remoteIp);
            }
        } catch (SSLPeerUnverifiedException noCertificate) {
            // Client không gửi chứng chỉ.
            deviceFingerprint = null;
        }
        // Chế độ "need" mà thiết bị chưa duyệt: báo lỗi có mã DEVICE_NOT_APPROVED rồi đóng.
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

    // Xử lý một frame: giới hạn tốc độ, giải mã, kiểm tra, định tuyến, xếp câu trả lời vào hàng đợi.
    private void handleFrame(String frame) {
        // 1. Giới hạn tốc độ.
        if (!rateLimiter.tryAcquire()) {
            ServerMetrics.get().recordRateLimited();
            // Không đọc nội dung frame: trả lỗi nhanh, đóng kết nối nếu cố tình spam.
            // Ghi nhật ký ở lần đầu và mỗi 100 lần (tránh làm đầy nhật ký).
            if (++rateLimitStrikes == 1 || rateLimitStrikes % 100 == 0) {
                SecurityLog.event("RATE_LIMITED", remoteIp, "strikes=" + rateLimitStrikes);
            }
            // Quá 1000 lần: ngắt kết nối.
            if (rateLimitStrikes > 1_000) {
                forceClose();
                return;
            }
            enqueue(ResponseMessage.error(requestIdOf(frame), ErrorCodes.RATE_LIMITED,
                    "Bạn gửi yêu cầu quá nhanh, vui lòng chậm lại."));
            return;
        }

        // 2. Giải mã JSON thành RequestMessage.
        RequestMessage request;
        try {
            request = ProtocolUtil.fromJson(frame, RequestMessage.class);
        } catch (JsonParseException error) {
            enqueue(ResponseMessage.error(null, "Gói tin không phải JSON hợp lệ."));
            return;
        }
        // Phải là REQUEST.
        if (request == null || !MessageType.REQUEST.name().equals(request.getType())) {
            enqueue(ResponseMessage.error(null, "Gói tin phải có type = REQUEST."));
            return;
        }

        // 3. requestId tối đa 64 ký tự.
        String requestId = request.getRequestId();
        if (requestId != null && requestId.length() > 64) {
            enqueue(ResponseMessage.error(null, "requestId quá dài."));
            return;
        }
        // 4. Đổi tên lệnh thành enum (lệnh lạ hoặc thiếu thì báo lỗi).
        Command command;
        try {
            command = request.getCommandEnum();
        } catch (IllegalArgumentException | NullPointerException error) {
            enqueue(ResponseMessage.error(requestId, "Command không được hỗ trợ: " + request.getCommand()));
            return;
        }

        // 5. Định tuyến và đo thời gian xử lý.
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
        // Ghi số liệu rồi xếp câu trả lời vào hàng đợi gửi.
        requestCount.incrementAndGet();
        ServerMetrics.get().recordRequest(command, System.nanoTime() - started, response.isOk());
        enqueue(response);
    }

    // Cố đọc requestId của frame (để câu báo lỗi khớp đúng yêu cầu); lỗi thì null.
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
        // Đọc một lần vào biến cục bộ (trường volatile có thể đổi giữa chừng).
        Integer account = loggedInAccountId;
        if (account == null) {
            throw new IllegalStateException("Chưa đăng nhập");
        }
        // Bộ quản lý tải lên của tài khoản (giữ qua các lần kết nối lại).
        return UploadRegistry.forAccount(account);
    }

    @Override
    public ClientSession session() {
        return this;
    }

    @Override
    public void signIn(int accountId) {
        // Gắn tài khoản vào kết nối và đăng ký với SessionManager.
        switchAccount(accountId);
        // Ghi lại tài khoản đăng nhập trên thiết bị này.
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
        // Gỡ khỏi tài khoản cũ (nếu có).
        SessionManager.getInstance().unregister(loggedInAccountId, this);
        loggedInAccountId = newAccountId;
        // Đăng ký với tài khoản mới để nhận EVENT.
        if (newAccountId != null) {
            SessionManager.getInstance().register(newAccountId, this);
        }
    }

    // ------------------------------------------------------------------ ClientSession

    /** Gửi EVENT chủ động (gọi từ SessionManager, có thể từ thread của client khác). */
    @Override
    public void sendEvent(EventMessage event) {
        ServerMetrics.get().recordEventPushed();
        // Chỉ xếp vào hàng đợi, không chờ mạng.
        enqueue(event);
    }

    @Override
    public void revoke(String reason) {
        // Báo cho Client lý do bị thu hồi.
        JsonObject data = new JsonObject();
        data.addProperty("reason", reason);
        enqueue(new EventMessage(EventName.SESSION_REVOKED, data));
        // Bỏ đăng nhập rồi đóng (thread ghi gửi nốt EVENT trên trước khi đóng socket).
        switchAccount(null);
        close();
    }

    // ------------------------------------------------------------------ giám sát

    /** @return mã kết nối */
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

    // Đổi đối tượng thành JSON và xếp vào hàng đợi gửi; đầy thì đóng kết nối.
    private void enqueue(Object message) {
        // Đã đóng: bỏ qua.
        if (closed.get()) {
            return;
        }
        // offer() không chặn: trả false nếu hàng đợi đầy.
        if (!outbound.offer(ProtocolUtil.toJson(message))) {
            System.err.println("[ClientHandler] Hang doi gui cua " + remoteAddress
                    + " day (client khong doc kip), dong ket noi.");
            forceClose();
        }
    }

    // Thread ghi: lấy frame từ hàng đợi và ghi xuống socket.
    private void writeLoop() {
        // BufferedWriter gom dữ liệu, chỉ thật sự gửi khi flush().
        try (Writer writer = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            while (true) {
                // take() chặn chờ tới khi có frame.
                String frame = outbound.take();
                // Gặp frame kết thúc: thoát vòng.
                if (END_OF_STREAM.equals(frame)) {
                    break;
                }
                // Ghi frame + '\n'.
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
            // Lỗi ghi (client đã đi) mà chưa phải do mình đóng: ghi log.
            if (!closed.get()) {
                System.err.println("[ClientHandler] Khong ghi duoc toi " + remoteAddress + ": " + error.getMessage());
            }
        } catch (InterruptedException error) {
            // Bị ngắt (Server dừng): giữ cờ ngắt.
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
        // compareAndSet: chỉ lần gọi đầu tiên đi tiếp (đóng đúng một lần).
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Không nhận EVENT nữa.
        SessionManager.getInstance().unregister(loggedInAccountId, this);
        // Báo thread ghi kết thúc; hàng đợi đầy thì đóng socket luôn.
        if (!outbound.offer(END_OF_STREAM)) {
            closeSocketQuietly();
        }
        // Báo MailServer (trả "vé" kết nối).
        onClose.run();
    }

    /** Đóng ngay lập tức (dừng Server, client không đọc kịp, quản trị viên ngắt). */
    public void forceClose() {
        close();
        closeSocketQuietly();
    }

    // Đóng socket, bỏ qua lỗi.
    private void closeSocketQuietly() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
