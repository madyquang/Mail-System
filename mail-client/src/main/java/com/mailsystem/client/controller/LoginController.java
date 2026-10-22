package com.mailsystem.client.controller;

import com.mailsystem.client.api.MailApi;
import com.mailsystem.client.app.Navigator;
import com.mailsystem.client.app.Session;
import com.mailsystem.client.network.ServerConnection;
import com.mailsystem.client.ui.Fx;
import com.mailsystem.client.ui.PasswordField2;
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
        serverToggle.setText("Máy chủ: " + Session.serverAddress());
        hide(errorLabel);
        setBusy(true);
        Fx.then(ServerConnection.getInstance().ensureConnectedAsync(Session.serverHost(), Session.serverPort())
                        .thenCompose(ignored -> MailApi.get().login(email, password)),
                account -> {
                    setBusy(false);
                    Session.signIn(account);
                    Navigator.show(Navigator.MAIN);
                },
                error -> {
                    setBusy(false);
                    showError(error);
                    passwordField.requestInputFocus();
                });
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
