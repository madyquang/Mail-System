// Gói "ui": các thành phần giao diện tự viết (JavaFX).
package com.mailsystem.client.ui;

// Các lớp JavaFX: căn lề, nhãn, cột dọc.
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** Trạng thái rỗng: icon lớn + tiêu đề + mô tả (VD: "Không có thư nào"). */
public class EmptyState extends VBox {

    // Chỗ đặt icon, tiêu đề, mô tả.
    private final javafx.scene.layout.StackPane iconHolder = new javafx.scene.layout.StackPane();
    private final Label title = new Label();
    private final Label subtitle = new Label();

    /** Tạo khung trống (nội dung gán sau bằng show). */
    public EmptyState() {
        // Cột dọc, các phần tử cách nhau 10px.
        super(10);
        // Kiểu CSS và căn giữa.
        getStyleClass().add("empty-state");
        setAlignment(Pos.CENTER);
        // Khung icon: kiểu CSS riêng, kích thước vừa nội dung.
        iconHolder.getStyleClass().add("empty-icon");
        iconHolder.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE);
        // Kiểu chữ tiêu đề và mô tả.
        title.getStyleClass().add("empty-title");
        subtitle.getStyleClass().add("empty-subtitle");
        // Mô tả xuống dòng, rộng tối đa 320px, căn giữa.
        subtitle.setWrapText(true);
        subtitle.setMaxWidth(320);
        subtitle.setAlignment(Pos.CENTER);
        subtitle.setStyle("-fx-text-alignment: center;");
        // Thêm các phần tử vào cột.
        getChildren().addAll(iconHolder, title, subtitle);
    }

    /** Đặt nội dung hiển thị. @return chính đối tượng này (để gọi nối tiếp) */
    public EmptyState show(String icon, String titleText, String subtitleText) {
        // Icon 44px.
        iconHolder.getChildren().setAll(Icons.of(icon, 44));
        title.setText(titleText);
        // Mô tả null thành chuỗi rỗng; rỗng thì ẩn và không chiếm chỗ.
        subtitle.setText(subtitleText == null ? "" : subtitleText);
        subtitle.setVisible(subtitleText != null && !subtitleText.isBlank());
        subtitle.setManaged(subtitle.isVisible());
        return this;
    }
}
