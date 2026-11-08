// Gói "controller": xử lý sự kiện của từng màn hình (gắn với tệp .fxml).
package com.mailsystem.client.controller;

// Gọi Server, điều hướng, phiên, kết nối, tiện ích giao diện, giới hạn dùng chung; JavaFX.
import com.mailsystem.client.api.MailApi;
import com.mailsystem.client.app.Navigator;
import com.mailsystem.client.app.Session;
import com.mailsystem.client.network.ServerConnection;
import com.mailsystem.client.ui.Fx;
import com.mailsystem.client.ui.PasswordField2;
import com.mailsystem.client.ui.PasswordStrength;
import com.mailsystem.common.protocol.ProtocolConstants;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;

/** Đăng ký tài khoản. Kiểm tra phía Client chỉ để phản hồi nhanh; Server kiểm tra lại toàn bộ. */
public class RegisterController {

    // Mẫu email đơn giản (giống Server).
    private static final String EMAIL_PATTERN = "[^\\s@]+@[^\\s@]+\\.[^\\s@]+";

    // Các thành phần giao diện; @FXML: JavaFX tự gán theo fx:id trong register.fxml.
    @FXML
    private TextField displayNameField;
    @FXML
    private TextField emailField;
    @FXML
    private PasswordField2 passwordField;
    @FXML
    private PasswordField2 confirmField;
    @FXML
    private PasswordStrength strength;
    @FXML
    private Label errorLabel;
    @FXML
    private Label successLabel;
    @FXML
    private Label serverLabel;
    @FXML
    private Button registerButton;
    @FXML
    private ProgressIndicator spinner;

    /** JavaFX gọi sau khi nạp FXML: hiện địa chỉ máy chủ, cập nhật thanh độ mạnh khi gõ, Enter ở ô xác nhận = đăng ký. */
    @FXML
    public void initialize() {
        // Hiện máy chủ đang dùng.
        serverLabel.setText("Máy chủ: " + Session.serverAddress());
        // Gõ mật khẩu: cập nhật thanh độ mạnh.
        passwordField.textProperty().addListener((observable, old, value) -> strength.update(value));
        // Nhấn Enter ở ô xác nhận mật khẩu = bấm Tạo tài khoản.
        confirmField.setOnAction(event -> handleRegister());
        // Đặt con trỏ vào ô tên sau khi giao diện hiện.
        Platform.runLater(displayNameField::requestFocus);
    }

    /** Bấm "Tạo tài khoản". */
    @FXML
    public void handleRegister() {
        // Đọc dữ liệu (bỏ khoảng trắng hai đầu).
        String displayName = displayNameField.getText() == null ? "" : displayNameField.getText().trim();
        String email = emailField.getText() == null ? "" : emailField.getText().trim();
        String password = passwordField.getText();
        // Ẩn thông báo thành công cũ.
        hide(successLabel);

        // Kiểm tra nhanh phía Client (Server kiểm tra lại).
        if (displayName.isBlank() || email.isBlank() || password.isBlank()) {
            showError("Điền đầy đủ tên hiển thị, email và mật khẩu.");
            return;
        }
        if (displayName.length() > ProtocolConstants.MAX_DISPLAY_NAME_CHARS) {
            showError("Tên hiển thị tối đa 100 ký tự.");
            return;
        }
        if (email.length() > 150 || !email.matches(EMAIL_PATTERN)) {
            showError("Địa chỉ email chưa đúng định dạng (VD: ten@mail.local).");
            return;
        }
        if (PasswordStrength.score(password) == 0) {
            showError("Mật khẩu cần ít nhất 8 ký tự, gồm cả chữ cái và chữ số.");
            return;
        }
        if (!password.equals(confirmField.getText())) {
            showError("Hai mật khẩu chưa khớp.");
            confirmField.setInvalid(true);
            return;
        }
        // Hợp lệ: bỏ viền đỏ, ẩn lỗi, khoá nút.
        confirmField.setInvalid(false);
        hide(errorLabel);
        setBusy(true);
        // Kết nối tới máy chủ (nếu chưa) rồi gửi REGISTER; kết quả chạy trên luồng giao diện.
        Fx.then(ServerConnection.getInstance().ensureConnectedAsync(Session.serverHost(), Session.serverPort())
                        .thenCompose(ignored -> MailApi.get().register(email, displayName, password)),
                // Thành công: xoá mật khẩu đã gõ, báo thành công.
                account -> {
                    setBusy(false);
                    passwordField.clear();
                    confirmField.clear();
                    show(successLabel, "Tạo tài khoản thành công. Bạn có thể đăng nhập ngay.");
                },
                // Lỗi: báo lỗi; nếu do không kết nối được thì nhắc đổi địa chỉ máy chủ.
                error -> {
                    setBusy(false);
                    showError(ServerConnection.getInstance().isConnected() ? error
                            : error + " Đổi địa chỉ máy chủ ở mục Máy chủ trên màn hình Đăng nhập.");
                });
    }

    /** Bấm "Quay lại đăng nhập". */
    @FXML
    public void handleBackToLogin() {
        Navigator.show(Navigator.LOGIN);
    }

    // Bật / tắt trạng thái đang xử lý: khoá nút, đổi chữ, hiện vòng xoay.
    private void setBusy(boolean busy) {
        registerButton.setDisable(busy);
        registerButton.setText(busy ? "Đang tạo tài khoản..." : "Tạo tài khoản");
        spinner.setVisible(busy);
    }

    // Hiện thông báo lỗi.
    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    // Hiện nhãn với nội dung cho trước.
    private static void show(Label label, String text) {
        label.setText(text);
        label.setVisible(true);
        label.setManaged(true);
    }

    // Ẩn nhãn và không chiếm chỗ.
    private static void hide(Label label) {
        label.setVisible(false);
        label.setManaged(false);
    }
}
