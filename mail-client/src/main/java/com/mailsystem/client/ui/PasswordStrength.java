// Gói "ui": các thành phần giao diện tự viết (JavaFX).
package com.mailsystem.client.ui;

// Độ dài tối thiểu mật khẩu (dùng chung với Server); các lớp JavaFX.
import com.mailsystem.common.protocol.ProtocolConstants;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * Thanh độ mạnh mật khẩu (gợi ý trực quan). Server vẫn là nơi quyết định cuối
 * cùng theo PasswordPolicy; quy tắc tối thiểu ở đây khớp với Server.
 */
public class PasswordStrength extends HBox {

    // Phần tô màu của thanh; nhãn chữ mô tả.
    private final Region fill = new Region();
    private final Label label = new Label();

    /** Dựng thanh độ mạnh. */
    public PasswordStrength() {
        // Hàng ngang cách 10px, căn giữa theo chiều dọc.
        super(10);
        setAlignment(Pos.CENTER_LEFT);
        // Thanh nền (cao 6px) chứa phần tô màu.
        StackPane bar = new StackPane(fill);
        bar.getStyleClass().add("strength-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setMaxHeight(6);
        fill.getStyleClass().add("strength-fill");
        fill.setMaxHeight(6);
        // Thanh giãn hết chỗ còn lại; nhãn rộng tối thiểu 150px.
        HBox.setHgrow(bar, Priority.ALWAYS);
        label.getStyleClass().add("hint");
        label.setMinWidth(150);
        getChildren().addAll(bar, label);
        // Trạng thái ban đầu; khi thanh đổi bề rộng thì tính lại bề rộng phần tô.
        update("");
        bar.widthProperty().addListener((observable, old, width) -> update(lastPassword));
    }

    // Mật khẩu gần nhất (để tính lại khi thanh đổi kích thước).
    private String lastPassword = "";

    /** Cập nhật thanh theo mật khẩu đang gõ. */
    public void update(String password) {
        lastPassword = password == null ? "" : password;
        // Điểm 0..3.
        int score = score(lastPassword);
        // Bỏ lớp màu cũ (strength-0..3), thêm lớp màu theo điểm.
        fill.getStyleClass().removeIf(style -> style.startsWith("strength-") && !style.equals("strength-fill"));
        fill.getStyleClass().add("strength-" + Math.max(0, score));
        // Bề rộng thanh nền; phần tô = (điểm + 1) / 4 bề rộng.
        double width = getChildren().isEmpty() ? 0 : ((Region) getChildren().get(0)).getWidth();
        fill.setMaxWidth(lastPassword.isEmpty() ? 0 : width * (score + 1) / 4.0);
        // Câu mô tả theo điểm (switch biểu thức).
        label.setText(lastPassword.isEmpty() ? "Ít nhất 8 ký tự, có chữ và số" : switch (score) {
            case 0 -> "Yếu: chưa đạt yêu cầu";
            case 1 -> "Tạm được";
            case 2 -> "Khá mạnh";
            default -> "Rất mạnh";
        });
    }

    /** 0 = chưa đạt yêu cầu tối thiểu, 1..3 = càng cao càng mạnh. */
    public static int score(String password) {
        // Chưa đủ độ dài tối thiểu hoặc thiếu chữ cái / chữ số: 0.
        if (password == null || password.length() < ProtocolConstants.MIN_PASSWORD_LENGTH
                || password.chars().noneMatch(Character::isLetter) || password.chars().noneMatch(Character::isDigit)) {
            return 0;
        }
        // Đạt tối thiểu: 1 điểm; dài >= 12 ký tự: +1.
        int score = 1;
        if (password.length() >= 12) {
            score++;
        }
        // Có cả hoa lẫn thường, hoặc có ký tự đặc biệt: +1.
        boolean mixedCase = password.chars().anyMatch(Character::isUpperCase)
                && password.chars().anyMatch(Character::isLowerCase);
        boolean symbol = password.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch));
        if (mixedCase || symbol) {
            score++;
        }
        // Tối đa 3.
        return Math.min(3, score);
    }
}
