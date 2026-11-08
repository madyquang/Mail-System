// Gói "security": các cơ chế bảo mật phía Server.
package com.mailsystem.server.security;

// IOException: lỗi vào/ra khi ghi tệp log.
import java.io.IOException;
// StandardCharsets.UTF_8: ghi tệp theo mã UTF-8.
import java.nio.charset.StandardCharsets;
// Files: tiện ích thao tác tệp.
import java.nio.file.Files;
// Path: đường dẫn tệp.
import java.nio.file.Path;
// StandardOpenOption: cách mở tệp (tạo nếu chưa có, ghi nối cuối).
import java.nio.file.StandardOpenOption;
// LocalDateTime: ngày giờ hiện tại.
import java.time.LocalDateTime;
// DateTimeFormatter: định dạng ngày giờ thành chuỗi.
import java.time.format.DateTimeFormatter;

/**
 * Nhật ký sự kiện bảo mật (đăng nhập sai, bị khoá, đổi mật khẩu, vượt giới
 * hạn...). Ghi ra console và data/logs/security.log để có thể điều tra sau.
 * KHÔNG bao giờ ghi mật khẩu hay nội dung thư.
 */
public final class SecurityLog {

    // Định dạng thời gian đầu mỗi dòng log, vd "2026-11-07 09:15:00".
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    // Tệp log (null nếu chưa init hoặc không tạo được thư mục); volatile để mọi thread thấy giá trị mới.
    private static volatile Path logFile;
    /** 100 sự kiện gần nhất cho bảng giám sát (cũ nhất bị bỏ khi đầy). */
    private static final java.util.Deque<String> RECENT = new java.util.ArrayDeque<>();
    // Số sự kiện tối đa giữ trong bộ nhớ.
    private static final int RECENT_LIMIT = 100;

    // Constructor private: lớp chỉ có hàm tĩnh.
    private SecurityLog() {
    }

    /**
     * Chuẩn bị nơi ghi log (gọi một lần khi Server khởi động).
     *
     * @param logsDir thư mục chứa tệp log
     */
    public static void init(Path logsDir) {
        try {
            // Tạo thư mục nếu chưa có.
            Files.createDirectories(logsDir);
            // Tệp log nằm trong thư mục đó.
            logFile = logsDir.resolve("security.log");
        } catch (IOException error) {
            // Không tạo được thư mục: vẫn chạy tiếp, chỉ ghi ra console.
            System.err.println("[SecurityLog] Khong tao duoc thu muc log: " + error.getMessage());
        }
    }

    /**
     * Ghi một sự kiện bảo mật.
     *
     * @param type   loại sự kiện, vd "LOGIN_FAILED"
     * @param ip     địa chỉ IP liên quan
     * @param detail chi tiết thêm (đã được làm sạch trước khi ghi)
     */
    public static void event(String type, String ip, String detail) {
        // Ghép một dòng log: thời gian + loại + ip + chi tiết đã làm sạch.
        String line = TIME.format(LocalDateTime.now()) + " " + type + " ip=" + ip + " " + sanitize(detail);
        // In ra console.
        System.out.println("[SECURITY] " + line);
        // Thêm vào danh sách gần nhất (khoá RECENT vì nhiều thread cùng ghi).
        synchronized (RECENT) {
            // Mới nhất ở đầu.
            RECENT.addFirst(line);
            // Quá giới hạn thì bỏ bớt cái cũ nhất ở cuối.
            while (RECENT.size() > RECENT_LIMIT) {
                RECENT.removeLast();
            }
        }
        // Đọc tệp log một lần vào biến cục bộ.
        Path file = logFile;
        // Chưa có tệp log thì dừng ở đây.
        if (file == null) {
            return;
        }
        // Khoá theo lớp để hai thread không ghi xen kẽ vào cùng một dòng.
        synchronized (SecurityLog.class) {
            try {
                // Ghi nối dòng log vào cuối tệp (tạo tệp nếu chưa có).
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException error) {
                // Ghi tệp lỗi: báo ra console, không làm hỏng việc đang xử lý.
                System.err.println("[SecurityLog] Khong ghi duoc log: " + error.getMessage());
            }
        }
    }

    /** Sự kiện gần nhất trước (cho bảng giám sát). */
    public static java.util.List<String> recent() {
        // Trả bản sao để nơi gọi đọc thoải mái mà không cần khoá.
        synchronized (RECENT) {
            return new java.util.ArrayList<>(RECENT);
        }
    }

    /** Chặn log injection: dữ liệu từ client không được tạo dòng log giả. */
    private static String sanitize(String text) {
        // Thay ký tự xuống dòng (\r, \n) và tab bằng "_"; null thành chuỗi rỗng.
        return text == null ? "" : text.replaceAll("[\\r\\n\\t]", "_");
    }
}
