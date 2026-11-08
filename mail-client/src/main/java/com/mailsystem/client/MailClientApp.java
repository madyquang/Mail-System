// Gói gốc của Client.
package com.mailsystem.client;

// Điều hướng màn hình, tự kết nối lại, kết nối Server, TLS, thông báo desktop, hộp thoại; JavaFX.
import com.mailsystem.client.app.Navigator;
import com.mailsystem.client.app.Reconnector;
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

    /** JavaFX gọi khi ứng dụng khởi động, truyền vào cửa sổ chính. */
    @Override
    public void start(Stage stage) {
        // Ghi nhớ cửa sổ chính cho Navigator.
        Navigator.init(stage);
        // Tiêu đề và kích thước cửa sổ.
        stage.setTitle("Mail System");
        stage.setMinWidth(960);
        stage.setMinHeight(640);
        stage.setWidth(1240);
        stage.setHeight(780);

        // Gắn hộp thoại tin chứng chỉ và xử lý mất kết nối.
        installConnectionHandlers();

        // Mở màn đăng nhập rồi hiện cửa sổ.
        Navigator.show(Navigator.LOGIN);
        stage.show();
    }

    /** Hộp thoại xác nhận chứng chỉ TLS + phản ứng khi mất kết nối (tự kết nối lại hoặc về màn đăng nhập). */
    public static void installConnectionHandlers() {
        // Kết nối TLS: khi gặp chứng chỉ lạ thì hỏi người dùng (Dialogs.trustServer).
        TlsConnector tls = ServerConnection.getInstance().tlsConnector();
        if (tls != null) {
            tls.setDecider(identity -> Dialogs.trustServer(Navigator.owner(), identity));
        }
        // Khi mất kết nối (gọi từ thread mạng nên chuyển về luồng giao diện).
        ServerConnection.getInstance().setDisconnectListener(reason -> Platform.runLater(() -> {
            String screen = Navigator.currentScreen();
            if (Navigator.LOGIN.equals(screen) || Navigator.REGISTER.equals(screen)) {
                return; // chưa có phiên nào bị mất; lần bấm tiếp theo tự kết nối lại
            }
            // Đang đăng nhập: tự kết nối lại bằng token thay vì đá về màn đăng nhập.
            // Không bắt đầu kết nối lại được (vd không có token): về màn đăng nhập.
            if (!Reconnector.get().start(reason)) {
                DesktopNotifier.shutdown();
                Navigator.showLogin(reason + " Vui lòng đăng nhập lại khi máy chủ sẵn sàng.");
            }
        }));
        // Kết nối lại thất bại hẳn: về màn đăng nhập.
        Reconnector.get().setOnGiveUp(reason -> {
            DesktopNotifier.shutdown();
            Navigator.showLogin(reason + " Vui lòng đăng nhập lại.");
        });
    }

    /** JavaFX gọi khi thoát: dừng kết nối lại, tắt thông báo, ngắt kết nối. */
    @Override
    public void stop() {
        Reconnector.get().cancel();
        DesktopNotifier.shutdown();
        ServerConnection.getInstance().disconnect();
    }

    /** Điểm vào: khởi động JavaFX (gọi start). */
    public static void main(String[] args) {
        launch(args);
    }
}
