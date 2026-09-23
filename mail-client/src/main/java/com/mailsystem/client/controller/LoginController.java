package com.mailsystem.client.controller;

import com.google.gson.JsonObject;
import com.mailsystem.client.MailClientApp;
import com.mailsystem.client.network.ServerConnection;
import com.mailsystem.common.protocol.Command;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/**
 * TODO (TV3): noi cac @FXML field nay voi fx:id tuong ung trong login.fxml.
 * TODO (TV4): hoan thien handleLogin() - hien dang la vi du mau cach goi
 * ServerConnection.sendRequest() dung chuan, cac Controller khac lam tuong tu.
 */
public class LoginController {

    @FXML private TextField emailField;
    @FXML private PasswordField passwordField;
    @FXML private Label errorLabel;

    @FXML
    public void handleLogin() {
        JsonObject payload = new JsonObject();
        payload.addProperty("email", emailField.getText());
        payload.addProperty("password", passwordField.getText());

        ServerConnection.getInstance()
                .sendRequest(Command.LOGIN, payload)
                .thenAccept(response -> Platform.runLater(() -> {
                    if (response.isOk()) {
                        // TODO: luu accountId/displayName vao 1 noi dung chung (VD: UserSession class)
                        MailClientApp.switchScene("/fxml/inbox.fxml");
                    } else {
                        errorLabel.setText(response.getMessage());
                    }
                }));
    }

    @FXML
    public void handleGoToRegister() {
        // TODO (TV3): tao them register.fxml + RegisterController tuong tu Login
    }
}
