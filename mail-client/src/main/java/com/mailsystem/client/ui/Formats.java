package com.mailsystem.client.ui;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Định dạng ngày giờ, dung lượng, danh sách người nhận cho giao diện. */
public final class Formats {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd 'thg' M");
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("HH:mm, dd/MM/yyyy");

    private Formats() {
    }

    /** Kiểu Gmail: hôm nay "09:30", năm nay "12 thg 10", năm khác "12/10/2025". */
    public static String shortDate(String iso) {
        LocalDateTime time = parse(iso);
        if (time == null) {
            return iso == null ? "" : iso;
        }
        LocalDate today = LocalDate.now();
        if (time.toLocalDate().equals(today)) {
            return TIME.format(time);
        }
        if (time.toLocalDate().equals(today.minusDays(1))) {
            return "Hôm qua";
        }
        return time.getYear() == today.getYear() ? DAY_MONTH.format(time) : FULL_DATE.format(time);
    }

    public static String longDate(String iso) {
        LocalDateTime time = parse(iso);
        return time == null ? (iso == null ? "" : iso) : LONG.format(time);
    }

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
        for (String part : text.split("[,;]")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

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
