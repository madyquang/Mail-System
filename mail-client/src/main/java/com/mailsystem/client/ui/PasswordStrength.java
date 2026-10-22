package com.mailsystem.client.ui;

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

    private final Region fill = new Region();
    private final Label label = new Label();

    public PasswordStrength() {
        super(10);
        setAlignment(Pos.CENTER_LEFT);
        StackPane bar = new StackPane(fill);
        bar.getStyleClass().add("strength-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setMaxHeight(6);
        fill.getStyleClass().add("strength-fill");
        fill.setMaxHeight(6);
        HBox.setHgrow(bar, Priority.ALWAYS);
        label.getStyleClass().add("hint");
        label.setMinWidth(150);
        getChildren().addAll(bar, label);
        update("");
        bar.widthProperty().addListener((observable, old, width) -> update(lastPassword));
    }

    private String lastPassword = "";

    public void update(String password) {
        lastPassword = password == null ? "" : password;
        int score = score(lastPassword);
        fill.getStyleClass().removeIf(style -> style.startsWith("strength-") && !style.equals("strength-fill"));
        fill.getStyleClass().add("strength-" + Math.max(0, score));
        double width = getChildren().isEmpty() ? 0 : ((Region) getChildren().get(0)).getWidth();
        fill.setMaxWidth(lastPassword.isEmpty() ? 0 : width * (score + 1) / 4.0);
        label.setText(lastPassword.isEmpty() ? "Ít nhất 8 ký tự, có chữ và số" : switch (score) {
            case 0 -> "Yếu: chưa đạt yêu cầu";
            case 1 -> "Tạm được";
            case 2 -> "Khá mạnh";
            default -> "Rất mạnh";
        });
    }

    /** 0 = chưa đạt yêu cầu tối thiểu, 1..3 = càng cao càng mạnh. */
    public static int score(String password) {
        if (password == null || password.length() < ProtocolConstants.MIN_PASSWORD_LENGTH
                || password.chars().noneMatch(Character::isLetter) || password.chars().noneMatch(Character::isDigit)) {
            return 0;
        }
        int score = 1;
        if (password.length() >= 12) {
            score++;
        }
        boolean mixedCase = password.chars().anyMatch(Character::isUpperCase)
                && password.chars().anyMatch(Character::isLowerCase);
        boolean symbol = password.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch));
        if (mixedCase || symbol) {
            score++;
        }
        return Math.min(3, score);
    }
}
