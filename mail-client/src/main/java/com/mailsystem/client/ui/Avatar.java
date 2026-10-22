package com.mailsystem.client.ui;

import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;

import java.util.Locale;

/** Ảnh đại diện tròn bằng chữ cái đầu tên, màu ổn định theo email (cùng người luôn cùng màu). */
public class Avatar extends StackPane {

    private static final String[] COLORS = {
            "#1a73e8", "#188038", "#a142f4", "#e8710a", "#d93025", "#12863a", "#9334e6", "#c5221f", "#007b83",
            "#5f6368" };

    private final Label initials = new Label();

    public Avatar(double size) {
        getStyleClass().add("avatar");
        initials.getStyleClass().add("avatar-text");
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        initials.setStyle("-fx-font-size: " + Math.round(size * 0.4) + "px;");
        getChildren().add(initials);
    }

    public void set(String name, String email) {
        String source = name == null || name.isBlank() ? (email == null ? "?" : email) : name;
        initials.setText(initials(source));
        String key = email == null ? source : email.toLowerCase(Locale.ROOT);
        String color = COLORS[Math.floorMod(key.hashCode(), COLORS.length)];
        setStyle("-fx-background-color: " + color + "; -fx-background-radius: " + getPrefWidth() / 2 + ";");
    }

    /** "Nguyễn Văn An" -> "NA"; "an@mail.local" -> "A". */
    public static String initials(String name) {
        String base = name.contains("@") ? name.substring(0, name.indexOf('@')) : name;
        // Chỉ lấy các từ bắt đầu bằng chữ/số: "Nguyễn Văn A (mới)" -> "NA", không phải "N(".
        java.util.List<String> words = java.util.Arrays.stream(base.trim().split("\\s+"))
                .filter(word -> !word.isEmpty() && Character.isLetterOrDigit(word.codePointAt(0)))
                .toList();
        if (words.isEmpty()) {
            return "?";
        }
        String first = new String(Character.toChars(words.get(0).codePointAt(0)));
        String last = words.size() > 1 ? new String(Character.toChars(words.get(words.size() - 1).codePointAt(0))) : "";
        return (first + last).toUpperCase(Locale.ROOT);
    }
}
