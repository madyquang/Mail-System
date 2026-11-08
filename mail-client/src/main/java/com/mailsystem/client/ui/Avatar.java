// Gói "ui": các thành phần giao diện tự viết (JavaFX).
package com.mailsystem.client.ui;

// Nhãn chữ và khung xếp chồng.
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;

// Locale.ROOT: đổi chữ hoa/thường không phụ thuộc ngôn ngữ máy.
import java.util.Locale;

/** Ảnh đại diện tròn bằng chữ cái đầu tên, màu ổn định theo email (cùng người luôn cùng màu). */
public class Avatar extends StackPane {

    // Bảng màu nền (mỗi người một màu cố định).
    private static final String[] COLORS = {
            "#1a73e8", "#188038", "#a142f4", "#e8710a", "#d93025", "#12863a", "#9334e6", "#c5221f", "#007b83",
            "#5f6368" };

    // Nhãn chữ cái đầu.
    private final Label initials = new Label();

    /** @param size đường kính (px) */
    public Avatar(double size) {
        // Kiểu CSS.
        getStyleClass().add("avatar");
        initials.getStyleClass().add("avatar-text");
        // Cố định kích thước (min = pref = max).
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        // Cỡ chữ bằng 40% đường kính.
        initials.setStyle("-fx-font-size: " + Math.round(size * 0.4) + "px;");
        getChildren().add(initials);
    }

    /** Đặt người hiển thị: chữ cái đầu từ tên (hoặc email), màu theo email. */
    public void set(String name, String email) {
        // Nguồn chữ: tên, nếu trống thì email, nếu cả hai trống thì "?".
        String source = name == null || name.isBlank() ? (email == null ? "?" : email) : name;
        initials.setText(initials(source));
        // Khoá chọn màu: email chữ thường (ổn định).
        String key = email == null ? source : email.toLowerCase(Locale.ROOT);
        // floorMod: chia lấy dư không âm (hashCode có thể âm).
        String color = COLORS[Math.floorMod(key.hashCode(), COLORS.length)];
        // Nền màu, bo tròn bằng nửa bề rộng (thành hình tròn).
        setStyle("-fx-background-color: " + color + "; -fx-background-radius: " + getPrefWidth() / 2 + ";");
    }

    /** "Nguyễn Văn An" -> "NA"; "an@mail.local" -> "A". */
    public static String initials(String name) {
        // Email thì chỉ lấy phần trước @.
        String base = name.contains("@") ? name.substring(0, name.indexOf('@')) : name;
        // Chỉ lấy các từ bắt đầu bằng chữ/số: "Nguyễn Văn A (mới)" -> "NA", không phải "N(".
        java.util.List<String> words = java.util.Arrays.stream(base.trim().split("\\s+"))
                .filter(word -> !word.isEmpty() && Character.isLetterOrDigit(word.codePointAt(0)))
                .toList();
        // Không có từ hợp lệ.
        if (words.isEmpty()) {
            return "?";
        }
        // Chữ đầu của từ đầu tiên và từ cuối (codePoint: đúng cả ký tự ngoài bảng cơ bản).
        String first = new String(Character.toChars(words.get(0).codePointAt(0)));
        String last = words.size() > 1 ? new String(Character.toChars(words.get(words.size() - 1).codePointAt(0))) : "";
        // Viết hoa.
        return (first + last).toUpperCase(Locale.ROOT);
    }
}
