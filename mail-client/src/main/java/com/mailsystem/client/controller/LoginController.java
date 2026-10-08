package com.mailsystem.client.controller;

import com.google.gson.JsonObject;
import com.mailsystem.client.MailClientApp;
import com.mailsystem.client.network.ServerConnection;
import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.ResponseMessage;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

import java.util.concurrent.CompletionException;

/**
 * Login screen validation and presentation. Authentication remains
 * server-owned. Kết nối TCP được mở (hoặc mở lại) ngay trước khi gửi LOGIN.
 */
public class LoginController {

    @FXML
    private TextField serverField;
    @FXML
    private TextField emailField;
    @FXML
    private PasswordField passwordField;
    @FXML
    private Label errorLabel;
    @FXML
    private Button loginButton;

    @FXML
    public void initialize() {
        serverField.setText(MailClientApp.getServerAddress());
        String reason = MailClientApp.consumeDisconnectReason();
        if (reason != null) {
            errorLabel.setText(reason);
        }
    }

    @FXML
    public void handleLogin() {
        String email = emailField.getText() == null ? "" : emailField.getText().trim();
        String password = passwordField.getText() == null ? "" : passwordField.getText();
        if (email.isBlank() || password.isBlank()) {
            errorLabel.setText("Nhập email và mật khẩu để tiếp tục.");
            return;
        }
        String addressError = MailClientApp.setServerAddress(serverField.getText());
        if (addressError != null) {
            errorLabel.setText(addressError);
            return;
        }

        errorLabel.setText("Đang kết nối tới " + MailClientApp.getServerAddress() + "...");
        loginButton.setDisable(true);
        JsonObject payload = new JsonObject();
        payload.addProperty("email", email);
        payload.addProperty("password", password);

        ServerConnection connection = ServerConnection.getInstance();
        connection.ensureConnectedAsync(MailClientApp.getServerHost(), MailClientApp.getServerPort())
                .thenCompose(ignored -> connection.sendRequest(Command.LOGIN, payload))
                .whenComplete((response, error) -> Platform.runLater(() -> showResult(response, error)));
    }

    private void showResult(ResponseMessage response, Throwable error) {
        loginButton.setDisable(false);
        if (error != null) {
            errorLabel.setText(describe(error));
            return;
        }
        if (response.isOk()) {
            errorLabel.setText("");
            MailClientApp.switchScene("/fxml/inbox.fxml");
        } else {
            errorLabel.setText(response.getMessage().isBlank()
                    ? "Đăng nhập không thành công."
                    : response.getMessage());
        }
    }

    @FXML
    public void handleGoToRegister() {
        MailClientApp.setServerAddress(serverField.getText());
        MailClientApp.switchScene("/fxml/register.fxml");
    }

    /** Lấy thông báo dễ hiểu từ lỗi của một chuỗi CompletableFuture. */
    static String describe(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof java.io.UncheckedIOException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof java.util.concurrent.TimeoutException) {
            return "Máy chủ không phản hồi kịp, vui lòng thử lại.";
        }
        return cause.getMessage() == null || cause.getMessage().isBlank()
                ? "Không nhận được phản hồi từ máy chủ."
                : cause.getMessage();
    }
}
