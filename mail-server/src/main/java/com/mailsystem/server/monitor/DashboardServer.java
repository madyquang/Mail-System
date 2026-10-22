package com.mailsystem.server.monitor;

import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.server.db.DatabaseConnection;
import com.mailsystem.server.net.MailServer;
import com.mailsystem.server.net.ServerThreads;
import com.mailsystem.server.security.DeviceRegistry;
import com.mailsystem.server.security.SecurityLog;
import com.mailsystem.server.session.ResumeTokens;
import com.mailsystem.server.transfer.UploadRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
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

    private final MailServer mailServer;
    private final String token;
    private final Map<String, Object> staticInfo;
    private HttpServer http;

    public DashboardServer(MailServer mailServer, String configuredToken, Map<String, Object> staticInfo) {
        this.mailServer = mailServer;
        this.staticInfo = staticInfo;
        if (configuredToken != null && !configuredToken.isBlank()) {
            this.token = configuredToken;
        } else {
            byte[] random = new byte[18];
            new SecureRandom().nextBytes(random);
            this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        }
    }

    public String start(int port) throws IOException {
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
        http.setExecutor(Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "mail-dashboard");
            thread.setDaemon(true);
            return thread;
        }));
        http.createContext("/", this::handle);
        http.start();
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/?token=" + token;
    }

    public void stop() {
        if (http != null) {
            http.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            URI uri = exchange.getRequestURI();
            String path = uri.getPath();
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
            if ("/".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                // Trang HTML không chứa dữ liệu nhạy cảm; dữ liệu chỉ lấy được qua API có mã truy cập.
                exchange.getResponseHeaders().add("Content-Security-Policy",
                        "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'");
                send(exchange, 200, "text/html; charset=utf-8", page());
                return;
            }
            if (!path.startsWith("/api/")) {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!authorized(exchange)) {
                send(exchange, 401, "application/json", "{\"error\":\"Sai ma truy cap\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            Map<String, String> query = query(uri.getRawQuery());
            boolean post = "POST".equals(exchange.getRequestMethod());
            Object result;
            if ("/api/stats".equals(path)) {
                result = stats();
            } else if (post && "/api/devices/approve".equals(path)) {
                result = Map.of("ok", DeviceRegistry.setApproved(query.getOrDefault("fp", ""), true));
                SecurityLog.event("DEVICE_APPROVED", "127.0.0.1", "fingerprint=" + query.get("fp"));
            } else if (post && "/api/devices/revoke".equals(path)) {
                result = Map.of("ok", DeviceRegistry.setApproved(query.getOrDefault("fp", ""), false));
                SecurityLog.event("DEVICE_REVOKED", "127.0.0.1", "fingerprint=" + query.get("fp"));
            } else if (post && "/api/connections/kick".equals(path)) {
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
            send(exchange, 200, "application/json; charset=utf-8",
                    ProtocolUtil.toJson(result).getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            System.err.println("[Dashboard] Loi: " + error);
        }
    }

    private Map<String, Object> stats() {
        Map<String, Object> data = new LinkedHashMap<>(staticInfo);
        data.put("metrics", ServerMetrics.get().snapshot());
        data.put("connections", mailServer.connectionSnapshot());
        data.put("maxClients", mailServer.maxClients());
        data.put("threadMode", ServerThreads.activeMode());
        DatabaseConnection.PoolStats pool = DatabaseConnection.poolStats();
        data.put("dbPool", Map.of("active", pool.active(), "idle", pool.idle(), "total", pool.total(),
                "waiting", pool.waiting(), "max", pool.max()));
        data.put("resumeTokens", ResumeTokens.get().size());
        data.put("pendingUploads", UploadRegistry.pendingUploads());
        data.put("devices", DeviceRegistry.list());
        data.put("securityEvents", SecurityLog.recent());
        return data;
    }

    private boolean authorized(HttpExchange exchange) {
        String provided = exchange.getRequestHeaders().getFirst("X-Admin-Token");
        if (provided == null) {
            return false;
        }
        // So sánh thời gian hằng: không lộ mã qua thời gian phản hồi.
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                values.put(pair.substring(0, eq),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static byte[] page() throws IOException {
        try (InputStream in = DashboardServer.class.getResourceAsStream("/monitor/dashboard.html")) {
            if (in == null) {
                return "<h1>Thieu dashboard.html</h1>".getBytes(StandardCharsets.UTF_8);
            }
            return in.readAllBytes();
        }
    }

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
