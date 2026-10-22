package com.mailsystem.server.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Nhật ký sự kiện bảo mật (đăng nhập sai, bị khoá, đổi mật khẩu, vượt giới
 * hạn...). Ghi ra console và data/logs/security.log để có thể điều tra sau.
 * KHÔNG bao giờ ghi mật khẩu hay nội dung thư.
 */
public final class SecurityLog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static volatile Path logFile;

    private SecurityLog() {
    }

    public static void init(Path logsDir) {
        try {
            Files.createDirectories(logsDir);
            logFile = logsDir.resolve("security.log");
        } catch (IOException error) {
            System.err.println("[SecurityLog] Khong tao duoc thu muc log: " + error.getMessage());
        }
    }

    public static void event(String type, String ip, String detail) {
        String line = TIME.format(LocalDateTime.now()) + " " + type + " ip=" + ip + " " + sanitize(detail);
        System.out.println("[SECURITY] " + line);
        Path file = logFile;
        if (file == null) {
            return;
        }
        synchronized (SecurityLog.class) {
            try {
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException error) {
                System.err.println("[SecurityLog] Khong ghi duoc log: " + error.getMessage());
            }
        }
    }

    /** Chặn log injection: dữ liệu từ client không được tạo dòng log giả. */
    private static String sanitize(String text) {
        return text == null ? "" : text.replaceAll("[\\r\\n\\t]", "_");
    }
}
