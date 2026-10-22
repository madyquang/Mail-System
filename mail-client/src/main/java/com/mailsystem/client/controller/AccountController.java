package com.mailsystem.client.controller;

import com.mailsystem.client.api.MailApi;
import com.mailsystem.client.app.Navigator;
import com.mailsystem.client.app.Session;
import com.mailsystem.client.network.Connector;
import com.mailsystem.client.network.ServerConnection;
import com.mailsystem.client.network.TlsConnector;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
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
import javafx.scene.control.CheckBox;
import javafx.scene.control.PasswordField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.layout.VBox;
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
    @FXML
    private TextArea deviceValue;
    @FXML
    private VBox securityBox;

    private final Label totpStatus = new Label();
    private final Button totpButton = new Button();
    private final VBox totpSetupBox = new VBox(10);
    private final CheckBox shareReceipts = new CheckBox("Gửi xác nhận đã đọc cho người gửi");
    private boolean totpEnabled;

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
        TlsConnector tls = ServerConnection.getInstance().tlsConnector();
        String device = tls == null ? null : tls.device().fingerprint();
        deviceValue.setText(device == null ? "Không có (máy này không tạo được chứng chỉ thiết bị)" : device);

        buildTwoFactorPanel();
        buildPrivacyPanel();
        Fx.then(api.settings(), settings -> {
            shareReceipts.setSelected(settings.isShareReadReceipts());
            shareReceipts.setDisable(false);
            showTotpState(settings.isTotpEnabled());
        }, error -> totpStatus.setText("Không tải được cài đặt: " + error));
    }

    // ------------------------------------------------------------------ xác minh 2 bước (TOTP)

    private void buildTwoFactorPanel() {
        Label title = new Label("Xác minh 2 bước");
        title.getStyleClass().add("title-md");
        Label hint = new Label("Ngoài mật khẩu, mỗi lần đăng nhập cần thêm mã 6 số đổi mỗi 30 giây từ ứng dụng "
                + "Google Authenticator / Microsoft Authenticator trên điện thoại. Kẻ lấy được mật khẩu vẫn không vào được.");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        totpStatus.setWrapText(true);
        totpButton.setOnAction(event -> {
            if (totpEnabled) {
                disableTotp();
            } else {
                startTotpSetup();
            }
        });
        totpSetupBox.setVisible(false);
        totpSetupBox.setManaged(false);
        VBox panel = new VBox(10, title, hint, totpStatus, new HBox(totpButton), totpSetupBox);
        panel.getStyleClass().add("panel");
        securityBox.getChildren().add(1, panel);
    }

    private void showTotpState(boolean enabled) {
        totpEnabled = enabled;
        totpStatus.setText(enabled ? "Đang BẬT: đăng nhập cần mật khẩu + mã xác minh." : "Đang tắt.");
        totpStatus.getStyleClass().setAll("label", enabled ? "success-text" : "text-muted");
        totpButton.setText(enabled ? "Tắt xác minh 2 bước" : "Bật xác minh 2 bước");
        totpButton.getStyleClass().setAll("button", enabled ? "secondary-button" : "primary-button");
        totpSetupBox.setVisible(false);
        totpSetupBox.setManaged(false);
    }

    private void startTotpSetup() {
        totpButton.setDisable(true);
        Fx.then(api.totpSetup(), setup -> {
            totpButton.setDisable(false);
            StackPane qr = new StackPane(new ImageView(qrImage(setup.otpauthUri(), 200)));
            qr.getStyleClass().add("qr-frame");
            qr.setMaxSize(StackPane.USE_PREF_SIZE, StackPane.USE_PREF_SIZE);
            Label step1 = new Label("1. Quét mã QR bằng ứng dụng xác thực (hoặc nhập khoá thủ công):");
            step1.setWrapText(true);
            TextField secret = new TextField(setup.secret().replaceAll("(.{4})", "$1 ").trim());
            secret.setEditable(false);
            secret.getStyleClass().add("mono");
            Label step2 = new Label("2. Nhập mã 6 số ứng dụng hiển thị để xác nhận:");
            TextField code = new TextField();
            code.setPromptText("123456");
            code.getStyleClass().add("totp-field");
            code.setMaxWidth(180);
            Button confirm = new Button("Xác nhận và bật");
            confirm.getStyleClass().add("primary-button");
            Runnable submit = () -> {
                confirm.setDisable(true);
                Fx.then(api.totpEnable(code.getText().trim()), ignored -> {
                    showTotpState(true);
                    Toast.success(root, "Đã bật xác minh 2 bước.");
                }, error -> {
                    confirm.setDisable(false);
                    Toast.error(root, error);
                });
            };
            confirm.setOnAction(event -> submit.run());
            code.setOnAction(event -> submit.run());
            totpSetupBox.getChildren().setAll(step1, qr, secret, step2, new HBox(10, code, confirm));
            totpSetupBox.setVisible(true);
            totpSetupBox.setManaged(true);
            code.requestFocus();
        }, error -> {
            totpButton.setDisable(false);
            Toast.error(root, error);
        });
    }

    private void disableTotp() {
        PasswordField password = new PasswordField();
        password.setPromptText("Mật khẩu hiện tại");
        TextField code = new TextField();
        code.setPromptText("Mã 6 số");
        Button confirm = new Button("Tắt xác minh 2 bước");
        confirm.getStyleClass().add("danger-button");
        confirm.setOnAction(event -> {
            confirm.setDisable(true);
            Fx.then(api.totpDisable(password.getText(), code.getText().trim()), ignored -> {
                showTotpState(false);
                Toast.success(root, "Đã tắt xác minh 2 bước.");
            }, error -> {
                confirm.setDisable(false);
                Toast.error(root, error);
            });
        });
        Label hint = new Label("Xác nhận bằng mật khẩu và một mã hiện tại:");
        totpSetupBox.getChildren().setAll(hint, password, code, new HBox(confirm));
        totpSetupBox.setVisible(true);
        totpSetupBox.setManaged(true);
        password.requestFocus();
    }

    /** Vẽ mã QR (ZXing tính ma trận điểm, JavaFX tô từng điểm). */
    private static Image qrImage(String text, int size) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                    java.util.Map.of(EncodeHintType.MARGIN, 1));
            WritableImage image = new WritableImage(matrix.getWidth(), matrix.getHeight());
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    image.getPixelWriter().setColor(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return image;
        } catch (WriterException error) {
            throw new IllegalStateException(error);
        }
    }

    // ------------------------------------------------------------------ quyền riêng tư

    private void buildPrivacyPanel() {
        Label title = new Label("Quyền riêng tư");
        title.getStyleClass().add("title-md");
        Label hint = new Label("Khi bật, người gửi thấy bạn đã mở thư của họ lúc nào. Khi tắt, bạn cũng vẫn thấy "
                + "xác nhận từ những người khác vẫn bật.");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        shareReceipts.setDisable(true);
        shareReceipts.setOnAction(event -> {
            boolean value = shareReceipts.isSelected();
            Fx.then(api.setShareReadReceipts(value),
                    ignored -> Toast.success(root, value ? "Đã bật gửi xác nhận đã đọc." : "Đã tắt gửi xác nhận đã đọc."),
                    error -> {
                        shareReceipts.setSelected(!value);
                        Toast.error(root, error);
                    });
        });
        VBox panel = new VBox(10, title, shareReceipts, hint);
        panel.getStyleClass().add("panel");
        securityBox.getChildren().add(2, panel);
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
