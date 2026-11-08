// Gói "ui": các thành phần giao diện tự viết (JavaFX).
package com.mailsystem.client.ui;

// Các lớp JavaFX: căn lề, nhãn, nút bật/tắt, hàng ngang, co giãn, vùng trống.
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/** Mục điều hướng ở sidebar: icon + nhãn + số đếm (VD: số thư chưa đọc). */
public class NavButton extends ToggleButton {

    // Nhãn tên mục và nhãn số đếm.
    private final Label title = new Label();
    private final Label badge = new Label();

    /** @param icon tên biểu tượng (Icons) @param text tên mục */
    public NavButton(String icon, String text) {
        // Lớp CSS "nav-item" (xoá các lớp mặc định của ToggleButton).
        getStyleClass().setAll("nav-item");
        // Giãn hết bề ngang thanh bên.
        setMaxWidth(Double.MAX_VALUE);
        // Chữ của mục; màu chữ kế thừa từ nút (đổi màu khi được chọn).
        title.setText(text);
        title.setStyle("-fx-text-fill: inherit;");
        // Kiểu CSS cho số đếm.
        badge.getStyleClass().add("nav-badge");
        // Khoảng trống co giãn đẩy số đếm sang phải.
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        // Hàng ngang: icon, tên, khoảng trống, số đếm (cách nhau 16px).
        HBox content = new HBox(16, Icons.of(icon, 20), title, spacer, badge);
        content.setAlignment(Pos.CENTER_LEFT);
        // Dùng hàng ngang làm nội dung nút, bỏ chữ mặc định.
        setGraphic(content);
        setText(null);
        // Bề rộng nội dung = bề rộng nút trừ 32px lề.
        content.prefWidthProperty().bind(widthProperty().subtract(32));
    }

    /** Hiện số đếm (0 thì ẩn, quá 999 thì hiện "999+"). */
    public void setCount(int count) {
        badge.setText(count > 0 ? (count > 999 ? "999+" : Integer.toString(count)) : "");
    }
}
