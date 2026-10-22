package com.mailsystem.client.controller;

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

    private static final String EMAIL_PATTERN = "[^\\s@]+@[^\\s@]+\\.[^\\s@]+";

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

    @FXML
    public void initialize() {
        serverLabel.setText("Máy chủ: " + Session.serverAddress());
        passwordField.textProperty().addListener((observable, old, value) -> strength.update(value));
        confirmField.setOnAction(event -> handleRegister());
        Platform.runLater(displayNameField::requestFocus);
    }

    @FXML
    public void handleRegister() {
        String displayName = displayNameField.getText() == null ? "" : displayNameField.getText().trim();
        String email = emailField.getText() == null ? "" : emailField.getText().trim();
        String password = passwordField.getText();
        hide(successLabel);

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
        confirmField.setInvalid(false);
        hide(errorLabel);
        setBusy(true);
        Fx.then(ServerConnection.getInstance().ensureConnectedAsync(Session.serverHost(), Session.serverPort())
                        .thenCompose(ignored -> MailApi.get().register(email, displayName, password)),
                account -> {
                    setBusy(false);
                    passwordField.clear();
                    confirmField.clear();
                    show(successLabel, "Tạo tài khoản thành công. Bạn có thể đăng nhập ngay.");
                },
                error -> {
                    setBusy(false);
                    showError(error);
                });
    }

    @FXML
    public void handleBackToLogin() {
        Navigator.show(Navigator.LOGIN);
    }

    private void setBusy(boolean busy) {
        registerButton.setDisable(busy);
        registerButton.setText(busy ? "Đang tạo tài khoản..." : "Tạo tài khoản");
        spinner.setVisible(busy);
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
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
