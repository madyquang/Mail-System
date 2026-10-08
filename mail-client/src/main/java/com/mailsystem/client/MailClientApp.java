package com.mailsystem.client;

import com.mailsystem.client.network.ServerConnection;
import com.mailsystem.common.protocol.ProtocolConstants;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.io.IOException;

/**
 * ENTRY POINT của JavaFX Client: điều hướng giữa các màn hình và giữ địa chỉ
 * máy chủ đang dùng. Kết nối TCP được mở khi người dùng đăng nhập/đăng ký (không
 * mở lúc khởi động), nên app vẫn chạy được khi Server chưa bật.
 *
 * Địa chỉ mặc định lấy từ biến môi trường MAIL_SERVER_HOST / MAIL_SERVER_PORT,
 * và có thể sửa trực tiếp ở màn hình đăng nhập (để thử qua mạng LAN).
 */
public class MailClientApp extends Application {

    private static final String LOGIN_SCREEN = "/fxml/login.fxml";
    private static final String REGISTER_SCREEN = "/fxml/register.fxml";

    private static Stage primaryStage;
    private static int selectedMailId;
    private static String currentScreen;
    private static String disconnectReason;

    private static String serverHost = environment("MAIL_SERVER_HOST", "localhost");
    private static int serverPort = parsePort(environment("MAIL_SERVER_PORT",
            Integer.toString(ProtocolConstants.DEFAULT_PORT)), ProtocolConstants.DEFAULT_PORT);

    @Override
    public void start(Stage stage) {
        primaryStage = stage;
        primaryStage.setTitle("Mail System");
        primaryStage.setMinWidth(760);
        primaryStage.setMinHeight(560);

        ServerConnection.getInstance().setDisconnectListener(reason -> Platform.runLater(() -> {
            // Ở màn hình đăng nhập/đăng ký chưa có phiên nào bị mất: lần bấm
            // tiếp theo sẽ tự kết nối lại, không cần làm gián đoạn người dùng.
            if (LOGIN_SCREEN.equals(currentScreen) || REGISTER_SCREEN.equals(currentScreen)) {
                return;
            }
            disconnectReason = reason;
            Alert alert = new Alert(Alert.AlertType.WARNING, reason
                    + "\nVui lòng đăng nhập lại khi máy chủ sẵn sàng.");
            alert.setHeaderText("Mất kết nối tới máy chủ");
            alert.show();
            switchScene(LOGIN_SCREEN);
        }));

        switchScene(LOGIN_SCREEN);
        primaryStage.show();
    }

    @Override
    public void stop() {
        ServerConnection.getInstance().disconnect();
    }

    public static void switchScene(String fxmlPath) {
        // Màn hình mới tự đăng ký listener nếu cần; tránh để EVENT gọi vào Controller cũ.
        ServerConnection.getInstance().setEventListener(null);
        try {
            Parent root = FXMLLoader.load(MailClientApp.class.getResource(fxmlPath));
            Scene scene = new Scene(root);
            scene.getStylesheets().add(MailClientApp.class.getResource("/styles/mail.css").toExternalForm());
            primaryStage.setScene(scene);
            primaryStage.sizeToScene();
            currentScreen = fxmlPath;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void setSelectedMailId(int mailId) {
        selectedMailId = mailId;
    }

    public static int getSelectedMailId() {
        return selectedMailId;
    }

    public static String getServerHost() {
        return serverHost;
    }

    public static int getServerPort() {
        return serverPort;
    }

    public static String getServerAddress() {
        return serverHost + ":" + serverPort;
    }

    /**
     * Đặt địa chỉ máy chủ dạng "host" hoặc "host:port".
     *
     * @return null nếu hợp lệ, ngược lại là thông báo lỗi
     */
    public static String setServerAddress(String address) {
        String value = address == null ? "" : address.trim();
        if (value.isEmpty()) {
            return "Nhập địa chỉ máy chủ, VD: localhost:5000.";
        }
        String host = value;
        int port = ProtocolConstants.DEFAULT_PORT;
        int colon = value.lastIndexOf(':');
        if (colon >= 0 && value.indexOf(':') == colon) {
            host = value.substring(0, colon).trim();
            port = parsePort(value.substring(colon + 1).trim(), -1);
            if (port < 0) {
                return "Cổng máy chủ phải là số từ 1 đến 65535.";
            }
        }
        if (host.isEmpty() || host.contains(" ")) {
            return "Địa chỉ máy chủ không hợp lệ.";
        }
        serverHost = host;
        serverPort = port;
        return null;
    }

    /** Lý do mất kết nối gần nhất (đọc một lần để hiển thị ở màn hình đăng nhập). */
    public static String consumeDisconnectReason() {
        String reason = disconnectReason;
        disconnectReason = null;
        return reason;
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static int parsePort(String value, int fallback) {
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65535 ? port : fallback;
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
