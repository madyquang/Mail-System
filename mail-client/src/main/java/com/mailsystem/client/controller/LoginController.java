// Gói "controller": xử lý sự kiện của từng màn hình (gắn với tệp .fxml).
package com.mailsystem.client.controller;

// Gọi Server, điều hướng, phiên, kết nối, tiện ích, mã lỗi; JavaFX.
import com.mailsystem.client.api.MailApi;
import com.mailsystem.client.app.Navigator;
import com.mailsystem.client.app.Session;
import com.mailsystem.client.network.ServerConnection;
import com.mailsystem.client.ui.Fx;
import com.mailsystem.client.ui.PasswordField2;
import com.mailsystem.common.protocol.ErrorCodes;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Màn hình đăng nhập. Kết nối TLS được mở (hoặc mở lại) ngay trước khi gửi LOGIN. */
public class LoginController {

    // Các thành phần giao diện; @FXML: JavaFX tự gán theo fx:id trong login.fxml.
    @FXML
    private StackPane root;
    @FXML
    private TextField emailField;
    @FXML
    private PasswordField2 passwordField;
    @FXML
    private TextField serverField;
    @FXML
    private VBox serverBox;
    @FXML
    private Hyperlink serverToggle;
    @FXML
    private VBox totpBox;
    @FXML
    private TextField totpField;
    @FXML
    private Label errorLabel;
    @FXML
    private Label noticeLabel;
    @FXML
    private Button loginButton;
    @FXML
    private ProgressIndicator spinner;

    /** JavaFX gọi sau khi nạp FXML. */
    @FXML
    public void initialize() {
        // Điền sẵn địa chỉ máy chủ và email lần trước.
        serverField.setText(Session.serverAddress());
        serverToggle.setText("Máy chủ: " + Session.serverAddress());
        emailField.setText(Session.lastEmail());
        // Enter ở ô mật khẩu = Đăng nhập.
        passwordField.setOnAction(event -> handleLogin());
        // Chỉ nhận chữ số, tối đa 6 ký tự.
        totpField.textProperty().addListener((obs, old, value) -> {
            String digits = value.replaceAll("\\D", "");
            if (digits.length() > 6) {
                digits = digits.substring(0, 6);
            }
            if (!digits.equals(value)) {
                totpField.setText(digits);
            }
        });
        // Thông báo do màn khác gửi sang (vd "phiên đã hết hạn").
        String notice = Navigator.consumeNotice();
        if (notice != null) {
            show(noticeLabel, notice);
        }
        // Đặt con trỏ: ô email nếu trống, không thì ô mật khẩu.
        Platform.runLater(() -> {
            if (emailField.getText().isBlank()) {
                emailField.requestFocus();
            } else {
                passwordField.requestInputFocus();
            }
        });
    }

    /** Bấm "Đăng nhập". */
    @FXML
    public void handleLogin() {
        // Đọc email (bỏ khoảng trắng) và mật khẩu; thiếu thì báo lỗi.
        String email = emailField.getText() == null ? "" : emailField.getText().trim();
        String password = passwordField.getText();
        if (email.isBlank() || password.isBlank()) {
            showError("Nhập email và mật khẩu để tiếp tục.");
            return;
        }
        // Kiểm tra và lưu địa chỉ máy chủ; sai thì mở ô địa chỉ và báo lỗi.
        String addressError = Session.setServerAddress(serverField.getText());
        if (addressError != null) {
            serverBox.setVisible(true);
            serverBox.setManaged(true);
            showError(addressError);
            return;
        }
        // Mã 2 bước (chỉ khi ô mã đang hiện) phải đủ 6 chữ số.
        String totp = totpBox.isVisible() ? totpField.getText().trim() : null;
        if (totp != null && totp.length() != 6) {
            showError("Nhập mã 6 chữ số trong ứng dụng xác thực.");
            totpField.requestFocus();
            return;
        }
        // Cập nhật nhãn máy chủ, ẩn lỗi, khoá giao diện.
        serverToggle.setText("Máy chủ: " + Session.serverAddress());
        hide(errorLabel);
        setBusy(true);
        // Kết nối (nếu chưa) rồi gửi LOGIN; kết quả xử lý trên luồng giao diện.
        ServerConnection.getInstance().ensureConnectedAsync(Session.serverHost(), Session.serverPort())
                .thenCompose(ignored -> MailApi.get().login(email, password, totp))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    setBusy(false);
                    // Thành công: lưu tài khoản và token, mở màn hình chính.
                    if (error == null) {
                        Session.signIn(result.account());
                        Session.setResumeToken(result.resumeToken());
                        Navigator.show(Navigator.MAIN);
                        return;
                    }
                    // Server trả TOTP_REQUIRED.
                    if (ErrorCodes.TOTP_REQUIRED.equals(Fx.code(error))) {
                        // Mật khẩu đúng, tài khoản bật xác minh 2 bước: hỏi thêm mã.
                        boolean firstAsk = !totpBox.isVisible();
                        totpBox.setVisible(true);
                        totpBox.setManaged(true);
                        totpField.clear();
                        totpField.requestFocus();
                        // Lần đầu hỏi mã: chỉ hiện lời nhắc (không phải lỗi).
                        if (firstAsk) {
                            hide(errorLabel);
                            show(noticeLabel, Fx.message(error));
                            return;
                        }
                    }
                    // Lỗi do không kết nối được máy chủ.
                    if (!ServerConnection.getInstance().isConnected()) {
                        // Không tới được máy chủ: mở sẵn ô địa chỉ để người dùng thấy và sửa ngay
                        // (địa chỉ được nhớ từ lần trước, có thể đã cũ).
                        serverBox.setVisible(true);
                        serverBox.setManaged(true);
                        showError(Fx.message(error) + " Kiểm tra địa chỉ máy chủ bên dưới và chắc chắn Server đang chạy.");
                        serverField.requestFocus();
                        serverField.selectAll();
                        return;
                    }
                    // Lỗi khác (sai mật khẩu, bị khoá...): báo lỗi và đặt con trỏ để nhập lại.
                    showError(Fx.message(error));
                    if (totpBox.isVisible()) {
                        totpField.requestFocus();
                    } else {
                        passwordField.requestInputFocus();
                    }
                }));
    }

    /** Bấm "Tạo tài khoản": lưu địa chỉ máy chủ rồi mở màn đăng ký. */
    @FXML
    public void handleGoToRegister() {
        Session.setServerAddress(serverField.getText());
        Navigator.show(Navigator.REGISTER);
    }

    /** Bấm nhãn "Máy chủ: ...": ẩn / hiện ô sửa địa chỉ. */
    @FXML
    public void handleToggleServer() {
        boolean show = !serverBox.isVisible();
        serverBox.setVisible(show);
        serverBox.setManaged(show);
        if (show) {
            serverField.requestFocus();
        }
    }

    // Bật / tắt trạng thái đang xử lý: khoá nút và các ô, đổi chữ, hiện vòng xoay.
    private void setBusy(boolean busy) {
        loginButton.setDisable(busy);
        loginButton.setText(busy ? "Đang kết nối..." : "Đăng nhập");
        spinner.setVisible(busy);
        emailField.setDisable(busy);
        passwordField.setDisable(busy);
        totpField.setDisable(busy);
    }

    // Hiện lỗi (ẩn lời nhắc nếu có).
    private void showError(String message) {
        hide(noticeLabel);
        show(errorLabel, message);
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
