package com.mailsystem.client.controller;

import com.mailsystem.client.api.MailApi;
import com.mailsystem.client.app.Navigator;
import com.mailsystem.client.app.Session;
import com.mailsystem.client.network.Connector;
import com.mailsystem.client.network.ServerConnection;
import com.mailsystem.client.service.DesktopNotifier;
import com.mailsystem.client.ui.Avatar;
import com.mailsystem.client.ui.Dialogs;
import com.mailsystem.client.ui.Fx;
import com.mailsystem.client.ui.PasswordField2;
import com.mailsystem.client.ui.PasswordStrength;
import com.mailsystem.client.ui.Toast;
import com.mailsystem.common.model.Account;
import com.mailsystem.common.protocol.ProtocolConstants;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/** Tài khoản & bảo mật: hồ sơ, đổi mật khẩu, đăng xuất mọi nơi, thông tin kênh TLS. */
public class AccountController {

    @FXML
    private StackPane root;
    @FXML
    private HBox headerBox;
    @FXML
    private Label nameLabel;
    @FXML
    private Label emailLabel;
    @FXML
    private TextField displayNameField;
    @FXML
    private PasswordField2 currentPasswordField;
    @FXML
    private PasswordField2 newPasswordField;
    @FXML
    private PasswordField2 confirmPasswordField;
    @FXML
    private PasswordStrength strength;
    @FXML
    private Button changePasswordButton;
    @FXML
    private HBox secureBadge;
    @FXML
    private Label secureLabel;
    @FXML
    private Label serverValue;
    @FXML
    private Label protocolValue;
    @FXML
    private Label cipherValue;
    @FXML
    private TextArea fingerprintValue;

    private final MailApi api = MailApi.get();
    private final Avatar avatar = new Avatar(56);

    @FXML
    public void initialize() {
        headerBox.getChildren().add(0, avatar);
        Account account = Session.account();
        if (account != null) {
            showAccount(account);
            displayNameField.setText(account.getDisplayName());
        }
        Session.accountProperty().addListener((observable, old, value) -> {
            if (value != null) {
                showAccount(value);
            }
        });
        newPasswordField.textProperty().addListener((observable, old, value) -> strength.update(value));

        Connector.SecurityInfo security = ServerConnection.getInstance().securityInfo();
        serverValue.setText(Session.serverAddress());
        boolean encrypted = security != null && security.encrypted();
        secureLabel.setText(encrypted ? "Kết nối được mã hoá đầu-cuối tới máy chủ" : "Kết nối KHÔNG được mã hoá");
        protocolValue.setText(encrypted ? security.protocol() : "—");
        cipherValue.setText(encrypted ? security.cipherSuite() : "—");
        fingerprintValue.setText(encrypted && security.fingerprint() != null ? security.fingerprint() : "—");
    }

    private void showAccount(Account account) {
        String name = account.getDisplayName() == null || account.getDisplayName().isBlank()
                ? account.getEmail() : account.getDisplayName();
        avatar.set(name, account.getEmail());
        nameLabel.setText(name);
        emailLabel.setText(account.getEmail());
    }

    @FXML
    public void handleSaveProfile() {
        String name = displayNameField.getText() == null ? "" : displayNameField.getText().trim();
        if (name.isEmpty() || name.length() > ProtocolConstants.MAX_DISPLAY_NAME_CHARS) {
            Toast.error(root, "Tên hiển thị phải từ 1 đến 100 ký tự.");
            return;
        }
        Fx.then(api.updateProfile(name), saved -> {
            Session.updateDisplayName(saved);
            Toast.success(root, "Đã cập nhật tên hiển thị.");
        }, error -> Toast.error(root, error));
    }

    @FXML
    public void handleChangePassword() {
        String current = currentPasswordField.getText();
        String next = newPasswordField.getText();
        if (current.isEmpty()) {
            Toast.error(root, "Nhập mật khẩu hiện tại.");
            return;
        }
        if (PasswordStrength.score(next) == 0) {
            Toast.error(root, "Mật khẩu mới cần ít nhất 8 ký tự, gồm cả chữ và số.");
            return;
        }
        if (!next.equals(confirmPasswordField.getText())) {
            confirmPasswordField.setInvalid(true);
            Toast.error(root, "Hai mật khẩu mới chưa khớp.");
            return;
        }
        confirmPasswordField.setInvalid(false);
        changePasswordButton.setDisable(true);
        Fx.then(api.changePassword(current, next), revoked -> {
            changePasswordButton.setDisable(false);
            currentPasswordField.clear();
            newPasswordField.clear();
            confirmPasswordField.clear();
            Toast.success(root, "Đã đổi mật khẩu." + (revoked > 0
                    ? " " + revoked + " phiên trên thiết bị khác đã bị đăng xuất." : ""));
        }, error -> {
            changePasswordButton.setDisable(false);
            Toast.error(root, error);
        });
    }

    @FXML
    public void handleLogoutAll() {
        if (!Dialogs.confirm(root.getScene().getWindow(), "Đăng xuất khỏi mọi thiết bị?",
                "Mọi phiên đăng nhập của tài khoản (kể cả máy này) sẽ kết thúc.", "Đăng xuất tất cả", true)) {
            return;
        }
        Fx.then(api.logoutAll(), revoked -> {
            ((Stage) root.getScene().getWindow()).close();
            DesktopNotifier.shutdown();
            Navigator.showLogin("Đã đăng xuất khỏi mọi thiết bị (" + revoked + " phiên khác đã bị thu hồi).");
        }, error -> Toast.error(root, error));
    }
}
