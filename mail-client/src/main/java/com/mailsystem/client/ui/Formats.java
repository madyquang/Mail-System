// Gói "ui": các thành phần giao diện tự viết (JavaFX).
package com.mailsystem.client.ui;

// Ngày, ngày giờ, định dạng; danh sách.
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Định dạng ngày giờ, dung lượng, danh sách người nhận cho giao diện. */
public final class Formats {

    // Các mẫu định dạng: giờ, ngày tháng, ngày đầy đủ, giờ + ngày.
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd 'thg' M");
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("HH:mm, dd/MM/yyyy");

    // Constructor private: lớp chỉ có hàm tĩnh.
    private Formats() {
    }

    /** Kiểu Gmail: hôm nay "09:30", năm nay "12 thg 10", năm khác "12/10/2025". */
    public static String shortDate(String iso) {
        // Không đọc được: trả nguyên chuỗi.
        LocalDateTime time = parse(iso);
        if (time == null) {
            return iso == null ? "" : iso;
        }
        // Hôm nay: chỉ giờ; hôm qua: "Hôm qua"; năm nay: ngày tháng; năm khác: ngày đầy đủ.
        LocalDate today = LocalDate.now();
        if (time.toLocalDate().equals(today)) {
            return TIME.format(time);
        }
        if (time.toLocalDate().equals(today.minusDays(1))) {
            return "Hôm qua";
        }
        return time.getYear() == today.getYear() ? DAY_MONTH.format(time) : FULL_DATE.format(time);
    }

    /** "HH:mm, dd/MM/yyyy" (giờ 24h); không đọc được thì trả nguyên chuỗi. */
    public static String longDate(String iso) {
        LocalDateTime time = parse(iso);
        return time == null ? (iso == null ? "" : iso) : LONG.format(time);
    }

    /** Dung lượng dễ đọc: B, KB, MB (một chữ số thập phân). */
    public static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    /** Tách chuỗi người nhận "a@x.y, Nhom A" thành danh sách đã cắt khoảng trắng. */
    public static List<String> splitRecipients(String text) {
        List<String> values = new ArrayList<>();
        if (text == null) {
            return values;
        }
        // Tách theo dấu phẩy hoặc chấm phẩy, bỏ khoảng trắng, bỏ phần rỗng.
        for (String part : text.split("[,;]")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    // Đọc chuỗi ISO-8601 thành LocalDateTime; lỗi hoặc rỗng thì null.
    private static LocalDateTime parse(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(iso);
        } catch (RuntimeException error) {
            return null;
        }
    }
}
