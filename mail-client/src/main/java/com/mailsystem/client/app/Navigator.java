package com.mailsystem.client.app;

import com.mailsystem.client.network.ServerConnection;
import com.mailsystem.client.ui.Dialogs;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.function.Consumer;

/**
 * Điều hướng giữa các màn hình (thay root của cửa sổ chính) và mở cửa sổ phụ
 * (soạn thư, nhóm, tài khoản). Mọi FXML/CSS được nạp ở đây nên controller
 * không phải biết đường dẫn tài nguyên.
 */
public final class Navigator {

    public static final String LOGIN = "/fxml/login.fxml";
    public static final String REGISTER = "/fxml/register.fxml";
    public static final String MAIN = "/fxml/main.fxml";
    public static final String COMPOSE = "/fxml/compose.fxml";
    public static final String GROUPS = "/fxml/groups.fxml";
    public static final String ACCOUNT = "/fxml/account.fxml";

    private static Stage primaryStage;
    private static String currentScreen;
    private static String pendingNotice;

    private Navigator() {
    }

    public static void init(Stage stage) {
        primaryStage = stage;
        stage.getIcons().add(appIcon());
        Dialogs.setStylesheet(stylesheet());
    }

    public static Stage primaryStage() {
        return primaryStage;
    }

    public static String currentScreen() {
        return currentScreen;
    }

    /** Thay nội dung cửa sổ chính; giữ nguyên kích thước cửa sổ. */
    public static <C> C show(String fxml) {
        ServerConnection.getInstance().setEventListener(null);
        Loaded<C> loaded = load(fxml);
        if (primaryStage.getScene() == null) {
            Scene scene = new Scene(loaded.root());
            scene.getStylesheets().add(stylesheet());
            primaryStage.setScene(scene);
        } else {
            primaryStage.getScene().setRoot(loaded.root());
        }
        currentScreen = fxml;
        return loaded.controller();
    }

    /** Về màn hình đăng nhập kèm thông báo (VD: lý do mất kết nối). */
    public static void showLogin(String notice) {
        pendingNotice = notice;
        Session.signOut();
        primaryStage.setTitle("Mail System");
        show(LOGIN);
    }

    /** Thông báo chờ hiển thị ở màn hình đăng nhập (đọc một lần). */
    public static String consumeNotice() {
        String notice = pendingNotice;
        pendingNotice = null;
        return notice;
    }

    /** Mở cửa sổ phụ không chặn (VD: nhiều cửa sổ soạn thư cùng lúc). */
    public static <C> C openWindow(String fxml, String title, double width, double height, boolean modal,
            Consumer<C> init) {
        Loaded<C> loaded = load(fxml);
        Stage stage = new Stage();
        stage.initOwner(primaryStage);
        if (modal) {
            stage.initModality(Modality.WINDOW_MODAL);
        }
        stage.setTitle(title);
        stage.getIcons().add(appIcon());
        Scene scene = new Scene(loaded.root(), width, height);
        scene.getStylesheets().add(stylesheet());
        stage.setScene(scene);
        stage.setMinWidth(Math.min(width, 520));
        stage.setMinHeight(Math.min(height, 420));
        if (init != null) {
            init.accept(loaded.controller());
        }
        stage.show();
        return loaded.controller();
    }

    public static Window owner() {
        return primaryStage;
    }

    public static String stylesheet() {
        return resource("/css/app.css").toExternalForm();
    }

    private record Loaded<C>(Parent root, C controller) {
    }

    private static <C> Loaded<C> load(String fxml) {
        try {
            FXMLLoader loader = new FXMLLoader(resource(fxml));
            Parent root = loader.load();
            return new Loaded<>(root, loader.getController());
        } catch (IOException error) {
            throw new UncheckedIOException("Không nạp được giao diện " + fxml, error);
        }
    }

    private static URL resource(String path) {
        URL url = Navigator.class.getResource(path);
        if (url == null) {
            throw new IllegalStateException("Thiếu tài nguyên " + path);
        }
        return url;
    }

    /** Icon cửa sổ vẽ bằng code (phong bì xanh), không cần tệp ảnh. */
    private static Image appIcon() {
        int size = 64;
        WritableImage image = new WritableImage(size, size);
        Color blue = Color.web("#0b57d0");
        Color white = Color.WHITE;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean inRound = Math.hypot(Math.max(0, Math.abs(x - 31.5) - 18), Math.max(0, Math.abs(y - 31.5) - 18)) < 14;
                Color color = Color.TRANSPARENT;
                if (inRound) {
                    color = blue;
                    boolean inEnvelope = x >= 14 && x <= 49 && y >= 20 && y <= 44;
                    if (inEnvelope) {
                        color = white;
                        boolean onFlap = Math.abs((y - 20) - Math.abs(x - 31.5) * 0.66) < 1.6 && y <= 33;
                        if (onFlap) {
                            color = blue;
                        }
                    }
                }
                image.getPixelWriter().setColor(x, y, color);
            }
        }
        return image;
    }
}
