package com.mailsystem.client.controller;

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

    @FXML
    public void initialize() {
        serverField.setText(Session.serverAddress());
        serverToggle.setText("Máy chủ: " + Session.serverAddress());
        emailField.setText(Session.lastEmail());
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
        String notice = Navigator.consumeNotice();
        if (notice != null) {
            show(noticeLabel, notice);
        }
        Platform.runLater(() -> {
            if (emailField.getText().isBlank()) {
                emailField.requestFocus();
            } else {
                passwordField.requestInputFocus();
            }
        });
    }

    @FXML
    public void handleLogin() {
        String email = emailField.getText() == null ? "" : emailField.getText().trim();
        String password = passwordField.getText();
        if (email.isBlank() || password.isBlank()) {
            showError("Nhập email và mật khẩu để tiếp tục.");
            return;
        }
        String addressError = Session.setServerAddress(serverField.getText());
        if (addressError != null) {
            serverBox.setVisible(true);
            serverBox.setManaged(true);
            showError(addressError);
            return;
        }
        String totp = totpBox.isVisible() ? totpField.getText().trim() : null;
        if (totp != null && totp.length() != 6) {
            showError("Nhập mã 6 chữ số trong ứng dụng xác thực.");
            totpField.requestFocus();
            return;
        }
        serverToggle.setText("Máy chủ: " + Session.serverAddress());
        hide(errorLabel);
        setBusy(true);
        ServerConnection.getInstance().ensureConnectedAsync(Session.serverHost(), Session.serverPort())
                .thenCompose(ignored -> MailApi.get().login(email, password, totp))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    setBusy(false);
                    if (error == null) {
                        Session.signIn(result.account());
                        Session.setResumeToken(result.resumeToken());
                        Navigator.show(Navigator.MAIN);
                        return;
                    }
                    if (ErrorCodes.TOTP_REQUIRED.equals(Fx.code(error))) {
                        // Mật khẩu đúng, tài khoản bật xác minh 2 bước: hỏi thêm mã.
                        boolean firstAsk = !totpBox.isVisible();
                        totpBox.setVisible(true);
                        totpBox.setManaged(true);
                        totpField.clear();
                        totpField.requestFocus();
                        if (firstAsk) {
                            hide(errorLabel);
                            show(noticeLabel, Fx.message(error));
                            return;
                        }
                    }
                    showError(Fx.message(error));
                    if (totpBox.isVisible()) {
                        totpField.requestFocus();
                    } else {
                        passwordField.requestInputFocus();
                    }
                }));
    }

    @FXML
    public void handleGoToRegister() {
        Session.setServerAddress(serverField.getText());
        Navigator.show(Navigator.REGISTER);
    }

    @FXML
    public void handleToggleServer() {
        boolean show = !serverBox.isVisible();
        serverBox.setVisible(show);
        serverBox.setManaged(show);
        if (show) {
            serverField.requestFocus();
        }
    }

    private void setBusy(boolean busy) {
        loginButton.setDisable(busy);
        loginButton.setText(busy ? "Đang kết nối..." : "Đăng nhập");
        spinner.setVisible(busy);
        emailField.setDisable(busy);
        passwordField.setDisable(busy);
        totpField.setDisable(busy);
    }

    private void showError(String message) {
        hide(noticeLabel);
        show(errorLabel, message);
    }

    private static void show(Label label, String text) {
        label.setText(text);
        label.setVisible(true);
        label.setManaged(true);
    }

    private static void hide(Label label) {
        label.setVisible(false);
        label.setManaged(false);
    }
}
