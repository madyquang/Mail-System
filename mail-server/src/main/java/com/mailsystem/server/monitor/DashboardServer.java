// Gói "monitor": số liệu vận hành và trang giám sát.
package com.mailsystem.server.monitor;

// Gson dùng chung; các thành phần Server cần đọc số liệu.
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.server.db.DatabaseConnection;
import com.mailsystem.server.net.MailServer;
import com.mailsystem.server.net.ServerThreads;
import com.mailsystem.server.security.DeviceRegistry;
import com.mailsystem.server.security.SecurityLog;
import com.mailsystem.server.session.ResumeTokens;
import com.mailsystem.server.transfer.UploadRegistry;
// Máy chủ HTTP đơn giản có sẵn trong JDK.
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

// Vào/ra, địa chỉ mạng, URI, bảng mã.
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
// So sánh thời gian hằng, sinh ngẫu nhiên, Base64.
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
// Map giữ thứ tự; pool thread.
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Bảng giám sát Server qua HTTP (com.sun.net.httpserver có sẵn trong JDK).
 *
 * Minh hoạ một Server phục vụ HAI giao thức: giao thức JSON tự thiết kế trên
 * TLS cho Client (cổng 5000), và HTTP chuẩn cho quản trị (cổng 8090). Bảng giám
 * sát chỉ nghe trên 127.0.0.1 (máy khác không truy cập được) và mọi API cần mã
 * truy cập ngẫu nhiên in ra lúc khởi động.
 *
 * GET  /                         trang HTML (tự gọi /api/stats mỗi 2 giây)
 * GET  /api/stats                số liệu JSON
 * POST /api/devices/approve?fp=  phê duyệt thiết bị (mutual TLS)
 * POST /api/devices/revoke?fp=   thu hồi thiết bị
 * POST /api/connections/kick?id= ngắt một kết nối
 */
public final class DashboardServer {

    // Server thư (để đọc danh sách kết nối, ngắt kết nối).
    private final MailServer mailServer;
    // Mã truy cập API.
    private final String token;
    // Thông tin cố định (cổng, dấu vân tay...).
    private final Map<String, Object> staticInfo;
    // Máy chủ HTTP (null khi chưa start).
    private HttpServer http;

    /**
     * Tạo trang giám sát.
     *
     * @param mailServer      Server thư
     * @param configuredToken mã truy cập trong cấu hình (trống thì sinh ngẫu nhiên)
     * @param staticInfo      thông tin cố định hiển thị
     */
    public DashboardServer(MailServer mailServer, String configuredToken, Map<String, Object> staticInfo) {
        this.mailServer = mailServer;
        this.staticInfo = staticInfo;
        // Dùng mã trong cấu hình nếu có.
        if (configuredToken != null && !configuredToken.isBlank()) {
            this.token = configuredToken;
        } else {
            // Không có: sinh 18 byte ngẫu nhiên, mã hoá Base64 URL-safe.
            byte[] random = new byte[18];
            new SecureRandom().nextBytes(random);
            this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        }
    }

    /**
     * Mở trang giám sát.
     *
     * @param port cổng HTTP
     * @return đường dẫn để mở trên trình duyệt (kèm mã truy cập)
     */
    public String start(int port) throws IOException {
        // Chỉ nghe trên địa chỉ loopback 127.0.0.1; hàng đợi kết nối 16.
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
        // 2 thread daemon xử lý yêu cầu HTTP.
        http.setExecutor(Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "mail-dashboard");
            thread.setDaemon(true);
            return thread;
        }));
        // Mọi đường dẫn đều đi qua hàm handle.
        http.createContext("/", this::handle);
        http.start();
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/?token=" + token;
    }

    /** Dừng trang giám sát. */
    public void stop() {
        if (http != null) {
            // 0: dừng ngay, không chờ yêu cầu đang xử lý.
            http.stop(0);
        }
    }

    // Xử lý một yêu cầu HTTP.
    private void handle(HttpExchange exchange) throws IOException {
        // try-with-resources: luôn đóng exchange khi xong.
        try (exchange) {
            URI uri = exchange.getRequestURI();
            String path = uri.getPath();
            // Không cho trình duyệt lưu đệm; không đoán loại nội dung.
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
            // GET / : trả trang HTML.
            if ("/".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                // Trang HTML không chứa dữ liệu nhạy cảm; dữ liệu chỉ lấy được qua API có mã truy cập.
                exchange.getResponseHeaders().add("Content-Security-Policy",
                        "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'");
                send(exchange, 200, "text/html; charset=utf-8", page());
                return;
            }
            // Đường dẫn khác ngoài /api/...: 404.
            if (!path.startsWith("/api/")) {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            // API cần mã truy cập trong header X-Admin-Token.
            if (!authorized(exchange)) {
                send(exchange, 401, "application/json", "{\"error\":\"Sai ma truy cap\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            // Tham số trên URL (?fp=..., ?id=...).
            Map<String, String> query = query(uri.getRawQuery());
            boolean post = "POST".equals(exchange.getRequestMethod());
            Object result;
            if ("/api/stats".equals(path)) {
                // Số liệu tổng hợp.
                result = stats();
            } else if (post && "/api/devices/approve".equals(path)) {
                // Duyệt thiết bị theo dấu vân tay, ghi nhật ký bảo mật.
                result = Map.of("ok", DeviceRegistry.setApproved(query.getOrDefault("fp", ""), true));
                SecurityLog.event("DEVICE_APPROVED", "127.0.0.1", "fingerprint=" + query.get("fp"));
            } else if (post && "/api/devices/revoke".equals(path)) {
                // Thu hồi duyệt thiết bị.
                result = Map.of("ok", DeviceRegistry.setApproved(query.getOrDefault("fp", ""), false));
                SecurityLog.event("DEVICE_REVOKED", "127.0.0.1", "fingerprint=" + query.get("fp"));
            } else if (post && "/api/connections/kick".equals(path)) {
                // Ngắt một kết nối theo mã.
                boolean kicked;
                try {
                    kicked = mailServer.kick(Integer.parseInt(query.getOrDefault("id", "-1")));
                } catch (NumberFormatException error) {
                    kicked = false;
                }
                result = Map.of("ok", kicked);
            } else {
                send(exchange, 404, "application/json", "{\"error\":\"Khong co API nay\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            // Trả kết quả dạng JSON.
            send(exchange, 200, "application/json; charset=utf-8",
                    ProtocolUtil.toJson(result).getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            System.err.println("[Dashboard] Loi: " + error);
        }
    }

    // Gom số liệu cho /api/stats.
    private Map<String, Object> stats() {
        // Bắt đầu từ thông tin cố định.
        Map<String, Object> data = new LinkedHashMap<>(staticInfo);
        // Bộ đếm vận hành.
        data.put("metrics", ServerMetrics.get().snapshot());
        // Danh sách kết nối và giới hạn.
        data.put("connections", mailServer.connectionSnapshot());
        data.put("maxClients", mailServer.maxClients());
        // Loại thread đang dùng.
        data.put("threadMode", ServerThreads.activeMode());
        // Tình trạng pool MySQL.
        DatabaseConnection.PoolStats pool = DatabaseConnection.poolStats();
        data.put("dbPool", Map.of("active", pool.active(), "idle", pool.idle(), "total", pool.total(),
                "waiting", pool.waiting(), "max", pool.max()));
        // Số token tiếp tục phiên, số tệp tải dở, thiết bị, sự kiện bảo mật gần nhất.
        data.put("resumeTokens", ResumeTokens.get().size());
        data.put("pendingUploads", UploadRegistry.pendingUploads());
        data.put("devices", DeviceRegistry.list());
        data.put("securityEvents", SecurityLog.recent());
        return data;
    }

    // Kiểm tra header X-Admin-Token có đúng mã truy cập không.
    private boolean authorized(HttpExchange exchange) {
        String provided = exchange.getRequestHeaders().getFirst("X-Admin-Token");
        if (provided == null) {
            return false;
        }
        // So sánh thời gian hằng: không lộ mã qua thời gian phản hồi.
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    // Tách chuỗi "a=1&b=2" thành Map (giá trị được giải mã URL).
    private static Map<String, String> query(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            // Bỏ qua cặp không có '=' hoặc tên rỗng.
            if (eq > 0) {
                values.put(pair.substring(0, eq),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    // Đọc trang HTML từ tài nguyên /monitor/dashboard.html trong jar.
    private static byte[] page() throws IOException {
        try (InputStream in = DashboardServer.class.getResourceAsStream("/monitor/dashboard.html")) {
            if (in == null) {
                return "<h1>Thieu dashboard.html</h1>".getBytes(StandardCharsets.UTF_8);
            }
            return in.readAllBytes();
        }
    }

    // Gửi phản hồi HTTP: header Content-Type, mã trạng thái, độ dài, rồi nội dung.
    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** Thông tin cố định hiển thị trên bảng giám sát. */
    public static Map<String, Object> info(int port, String fingerprint, String clientAuth, String dbCipher) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("port", port);
        info.put("tlsFingerprint", fingerprint);
        info.put("clientAuth", clientAuth);
        info.put("dbTls", dbCipher == null ? "KHÔNG mã hoá" : dbCipher);
        info.put("javaVersion", System.getProperty("java.version"));
        return info;
    }
}
