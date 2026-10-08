package com.mailsystem.client.controller;

import com.google.gson.JsonObject;
import com.mailsystem.client.MailClientApp;
import com.mailsystem.client.network.ServerConnection;
import com.mailsystem.common.protocol.Command;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

public class RegisterController {

    @FXML
    private TextField emailField;
    @FXML
    private TextField displayNameField;
    @FXML
    private PasswordField passwordField;
    @FXML
    private PasswordField confirmPasswordField;
    @FXML
    private Label statusLabel;
    @FXML
    private Button registerButton;

    @FXML
    public void handleRegister() {
        String email = emailField.getText() == null ? "" : emailField.getText().trim();
        String displayName = displayNameField.getText() == null ? "" : displayNameField.getText().trim();
        String password = passwordField.getText() == null ? "" : passwordField.getText();
        String confirmPassword = confirmPasswordField.getText() == null
                ? ""
                : confirmPasswordField.getText();

        if (email.isBlank() || displayName.isBlank() || password.isBlank()) {
            showError("Điền đầy đủ các trường trước khi đăng ký.");
            return;
        }
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            showError("Địa chỉ email chưa đúng định dạng.");
            return;
        }
        if (password.length() < 6) {
            showError("Mật khẩu cần có ít nhất 6 ký tự.");
            return;
        }
        if (!password.equals(confirmPassword)) {
            showError("Hai mật khẩu chưa khớp.");
            return;
        }

        statusLabel.getStyleClass().remove("error-text");
        statusLabel.getStyleClass().add("success-text");
        statusLabel.setText("Đang tạo tài khoản...");
        registerButton.setDisable(true);

        JsonObject payload = new JsonObject();
        payload.addProperty("email", email);
        payload.addProperty("displayName", displayName);
        payload.addProperty("password", password);
        ServerConnection connection = ServerConnection.getInstance();
        connection.ensureConnectedAsync(MailClientApp.getServerHost(), MailClientApp.getServerPort())
                .thenCompose(ignored -> connection.sendRequest(Command.REGISTER, payload))
                .whenComplete((response, error) -> Platform.runLater(() -> {
                    registerButton.setDisable(false);
                    if (error != null) {
                        showError(LoginController.describe(error));
                    } else if (response.isOk()) {
                        statusLabel.setText("Tạo tài khoản thành công. Bạn có thể đăng nhập.");
                        passwordField.clear();
                        confirmPasswordField.clear();
                    } else {
                        showError(response.getMessage().isBlank()
                                ? "Đăng ký không thành công."
                                : response.getMessage());
                    }
                }));
    }

    @FXML
    public void handleBackToLogin() {
        MailClientApp.switchScene("/fxml/login.fxml");
    }

    private void showError(String message) {
        statusLabel.getStyleClass().remove("success-text");
        if (!statusLabel.getStyleClass().contains("error-text")) {
            statusLabel.getStyleClass().add("error-text");
        }
        statusLabel.setText(message);
    }
}