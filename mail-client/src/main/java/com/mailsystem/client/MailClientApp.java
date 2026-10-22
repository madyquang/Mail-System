package com.mailsystem.client;

import com.mailsystem.client.app.Navigator;
import com.mailsystem.client.network.ServerConnection;
import com.mailsystem.client.network.TlsConnector;
import com.mailsystem.client.service.DesktopNotifier;
import com.mailsystem.client.ui.Dialogs;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

/**
 * ENTRY POINT của JavaFX Client. Chỉ khởi tạo cửa sổ, gắn hộp thoại xác nhận
 * chứng chỉ TLS và phản ứng khi mất kết nối; còn lại do Navigator và các
 * Controller đảm nhiệm. Kết nối TCP/TLS chỉ được mở khi người dùng đăng nhập.
 */
public class MailClientApp extends Application {

    @Override
    public void start(Stage stage) {
        Navigator.init(stage);
        stage.setTitle("Mail System");
        stage.setMinWidth(960);
        stage.setMinHeight(640);
        stage.setWidth(1240);
        stage.setHeight(780);

        TlsConnector tls = ServerConnection.getInstance().tlsConnector();
        if (tls != null) {
            tls.setDecider(identity -> Dialogs.trustServer(Navigator.owner(), identity));
        }
        ServerConnection.getInstance().setDisconnectListener(reason -> Platform.runLater(() -> {
            String screen = Navigator.currentScreen();
            if (Navigator.LOGIN.equals(screen) || Navigator.REGISTER.equals(screen)) {
                return; // chưa có phiên nào bị mất; lần bấm tiếp theo tự kết nối lại
            }
            DesktopNotifier.shutdown();
            Navigator.showLogin(reason + " Vui lòng đăng nhập lại khi máy chủ sẵn sàng.");
        }));

        Navigator.show(Navigator.LOGIN);
        stage.show();
    }

    @Override
    public void stop() {
        DesktopNotifier.shutdown();
        ServerConnection.getInstance().disconnect();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
