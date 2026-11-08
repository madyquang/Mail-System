// Gói "ui": các thành phần giao diện tự viết (JavaFX).
package com.mailsystem.client.ui;

// Các lớp JavaFX: thuộc tính chuỗi, căn lề, nút, ô mật khẩu / ô chữ, chú thích, hàng, khung xếp chồng.
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;

/** Ô mật khẩu có nút ẩn/hiện (con mắt). Dùng được trực tiếp trong FXML. */
public class PasswordField2 extends StackPane {

    // Ô ẩn mật khẩu, ô hiện mật khẩu, nút con mắt.
    private final PasswordField hidden = new PasswordField();
    private final TextField visible = new TextField();
    private final Button toggle = new Button();

    /** Dựng ô (FXML gọi constructor không tham số). */
    public PasswordField2() {
        // Hai ô dùng chung nội dung (sửa ô này thì ô kia đổi theo).
        visible.textProperty().bindBidirectional(hidden.textProperty());
        // Hai ô chồng lên nhau trong StackPane và luôn nằm trong bố cục; ẩn/hiện mật khẩu chỉ đổi
        // ô nào được nhìn thấy nên kích thước không đổi.
        visible.setVisible(false);
        // Chữ gợi ý của ô hiện lấy theo ô ẩn; chừa lề phải 42px cho nút con mắt.
        visible.promptTextProperty().bind(hidden.promptTextProperty());
        hidden.setStyle("-fx-padding: 9px 42px 9px 12px;");
        visible.setStyle("-fx-padding: 9px 42px 9px 12px;");

        // Nút con mắt: không nhận Tab, icon mắt, chú thích; bấm thì đảo ẩn / hiện.
        toggle.getStyleClass().add("icon-button");
        toggle.setFocusTraversable(false);
        toggle.setGraphic(Icons.of(Icons.EYE, 18));
        toggle.setTooltip(new Tooltip("Hiện mật khẩu"));
        toggle.setOnAction(event -> setRevealed(!visible.isVisible()));
        // Lớp phủ chứa nút, căn phải, vùng trống không chặn chuột.
        HBox overlay = new HBox(toggle);
        overlay.setAlignment(Pos.CENTER_RIGHT);
        overlay.setPickOnBounds(false);
        overlay.setStyle("-fx-padding: 0 4px 0 0;");
        HBox.setHgrow(toggle, Priority.NEVER);
        // Xếp chồng: ô ẩn, ô hiện, lớp phủ nút.
        getChildren().addAll(hidden, visible, overlay);
    }

    // Đổi giữa ẩn và hiện mật khẩu.
    private void setRevealed(boolean reveal) {
        // Nhớ ô đang được focus trước khi đổi.
        boolean focused = hidden.isFocused() || visible.isFocused();
        // Hiện ô tương ứng, đổi icon và chú thích.
        visible.setVisible(reveal);
        hidden.setVisible(!reveal);
        toggle.setGraphic(Icons.of(reveal ? Icons.EYE_OFF : Icons.EYE, 18));
        toggle.getTooltip().setText(reveal ? "Ẩn mật khẩu" : "Hiện mật khẩu");
        // Giữ focus và đặt con trỏ cuối chữ trong ô vừa hiện.
        if (focused) {
            (reveal ? visible : hidden).requestFocus();
            (reveal ? visible : hidden).end();
        }
    }

    /** Thuộc tính nội dung (để FXML / controller ràng buộc). */
    public StringProperty textProperty() {
        return hidden.textProperty();
    }

    /** Nội dung (null thành chuỗi rỗng). */
    public String getText() {
        return hidden.getText() == null ? "" : hidden.getText();
    }

    /** Đặt nội dung. */
    public void setText(String text) {
        hidden.setText(text);
    }

    /** Xoá nội dung. */
    public void clear() {
        hidden.clear();
    }

    /** Chữ gợi ý (FXML dùng promptText="..."). */
    public String getPromptText() {
        return hidden.getPromptText();
    }

    public void setPromptText(String text) {
        hidden.setPromptText(text);
    }

    /** Gắn xử lý khi nhấn Enter cho cả hai ô. */
    public void setOnAction(javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        hidden.setOnAction(handler);
        visible.setOnAction(handler);
    }

    /** Đưa focus vào ô đang hiện. */
    public void requestInputFocus() {
        (visible.isVisible() ? visible : hidden).requestFocus();
    }

    /** Bật / tắt viền đỏ báo lỗi cho cả hai ô. */
    public void setInvalid(boolean invalid) {
        for (TextField field : new TextField[] {hidden, visible}) {
            field.getStyleClass().remove("field-invalid");
            if (invalid) {
                field.getStyleClass().add("field-invalid");
            }
        }
    }
}
