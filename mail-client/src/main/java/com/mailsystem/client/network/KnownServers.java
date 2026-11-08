// Gói "network": kết nối mạng tới Server.
package com.mailsystem.client.network;

// Vào/ra tệp, bảng mã, đường dẫn, chữ thường, Properties (tệp key=value).
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Danh sách máy chủ đã tin (giống ~/.ssh/known_hosts): "host:port" → dấu vân
 * tay SHA-256 của chứng chỉ. Lưu tại ~/.mailsystem/known_servers.properties.
 */
public class KnownServers {

    // Tệp lưu danh sách; bảng host:port -> dấu vân tay.
    private final Path file;
    private final Properties entries = new Properties();

    /** Nạp danh sách từ tệp (chưa có tệp thì bắt đầu rỗng). */
    public KnownServers(Path file) {
        this.file = file;
        // Tệp có thì đọc.
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                entries.load(reader);
            } catch (IOException error) {
                System.err.println("[KnownServers] Khong doc duoc " + file + ": " + error.getMessage());
            }
        }
    }

    /** Danh sách ở ~/.mailsystem/known_servers.properties trong thư mục người dùng. */
    public static KnownServers inUserHome() {
        return new KnownServers(Path.of(System.getProperty("user.home"), ".mailsystem", "known_servers.properties"));
    }

    /** Dấu vân tay đã tin của host:port, hoặc null nếu chưa biết. */
    public synchronized String fingerprint(String host, int port) {
        return entries.getProperty(key(host, port));
    }

    /** Ghi nhớ (hoặc ghi đè) dấu vân tay cho host:port rồi lưu tệp. */
    public synchronized void trust(String host, int port, String fingerprint) {
        entries.setProperty(key(host, port), fingerprint);
        save();
    }

    /** Quên host:port rồi lưu tệp. */
    public synchronized void forget(String host, int port) {
        entries.remove(key(host, port));
        save();
    }

    // Ghi toàn bộ danh sách ra tệp (tạo thư mục nếu chưa có).
    private void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                entries.store(writer, "Mail System - may chu da tin (host:port = SHA-256 fingerprint)");
            }
        } catch (IOException error) {
            System.err.println("[KnownServers] Khong luu duoc " + file + ": " + error.getMessage());
        }
    }

    // Khoá "host:port" (host chữ thường).
    private static String key(String host, int port) {
        return host.toLowerCase(Locale.ROOT) + ":" + port;
    }
}
