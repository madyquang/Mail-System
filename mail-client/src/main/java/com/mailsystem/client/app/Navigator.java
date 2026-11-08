// Gói "app": trạng thái và điều hướng chung của ứng dụng Client.
package com.mailsystem.client.app;

// Kết nối Server; JavaFX: nạp FXML, cảnh, ảnh, màu, cửa sổ.
import com.mailsystem.client.network.ServerConnection;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

// Lỗi vào/ra, URL tài nguyên, Consumer.
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

    // Đường dẫn các tệp FXML của từng màn hình / cửa sổ.
    public static final String LOGIN = "/fxml/login.fxml";
    public static final String REGISTER = "/fxml/register.fxml";
    public static final String MAIN = "/fxml/main.fxml";
    public static final String COMPOSE = "/fxml/compose.fxml";
    public static final String GROUPS = "/fxml/groups.fxml";
    public static final String ACCOUNT = "/fxml/account.fxml";

    // Cửa sổ chính; màn hình đang hiện; thông báo chờ hiện ở màn đăng nhập.
    private static Stage primaryStage;
    private static String currentScreen;
    private static String pendingNotice;

    // Constructor private: lớp chỉ có hàm tĩnh.
    private Navigator() {
    }

    /** Ghi nhớ cửa sổ chính và gắn icon (gọi một lần khi khởi động). */
    public static void init(Stage stage) {
        primaryStage = stage;
        stage.getIcons().add(appIcon());

    }

    /** @return cửa sổ chính */
    public static Stage primaryStage() {
        return primaryStage;
    }

    /** @return đường dẫn FXML của màn hình đang hiện */
    public static String currentScreen() {
        return currentScreen;
    }

    /** Thay nội dung cửa sổ chính; giữ nguyên kích thước cửa sổ. */
    public static <C> C show(String fxml) {
        // Bỏ bộ nghe EVENT của màn hình cũ (màn mới sẽ tự đăng ký).
        ServerConnection.getInstance().setEventListener(null);
        // Nạp FXML và controller.
        Loaded<C> loaded = load(fxml);
        // Lần đầu: tạo cảnh mới, áp CSS, gắn vào cửa sổ.
        if (primaryStage.getScene() == null) {
            Scene scene = new Scene(loaded.root());
            styleScene(scene);
            primaryStage.setScene(scene);
        } else {
            // Đã có cảnh: chỉ thay root (giữ kích thước cửa sổ).
            primaryStage.getScene().setRoot(loaded.root());
        }
        // Hiệu ứng hiện dần; ghi nhớ màn hiện tại; trả controller.
        com.mailsystem.client.ui.Motion.fadeIn(loaded.root(), com.mailsystem.client.ui.Motion.NORMAL);
        currentScreen = fxml;
        return loaded.controller();
    }

    /** Về màn hình đăng nhập kèm thông báo (VD: lý do mất kết nối). */
    public static void showLogin(String notice) {
        // Lưu thông báo; xoá phiên; đóng cửa sổ phụ; đặt lại tiêu đề; hiện màn đăng nhập.
        pendingNotice = notice;
        Session.signOut();
        closeSessionWindows();
        primaryStage.setTitle("Mail System");
        show(LOGIN);
    }

    /**
     * Khoá trong stage.getProperties(): giá trị là BooleanSupplier, trả true nếu cửa sổ
     * cần GIỮ LẠI khi phiên kết thúc (VD khung soạn thư đang có nội dung).
     */
    public static final String KEEP_ON_SIGN_OUT = "mail.keepOnSignOut";

    /**
     * Phiên kết thúc (đăng xuất, bị thu hồi, không kết nối lại được): đóng các cửa sổ phụ
     * của phiên cũ (Nhóm thư, Tài khoản...). Nếu để lại, thao tác trong đó sẽ chạy dưới
     * phiên MỚI, có thể là của một tài khoản khác.
     */
    private static void closeSessionWindows() {
        // Duyệt bản sao danh sách cửa sổ (vì đóng cửa sổ làm danh sách thay đổi).
        for (Window window : new java.util.ArrayList<>(Window.getWindows())) {
            // Chỉ xét cửa sổ phụ (không phải cửa sổ chính).
            if (window instanceof Stage stage && stage != primaryStage) {
                // Cửa sổ yêu cầu giữ lại (vd soạn thư có nội dung): bỏ qua.
                Object keep = stage.getProperties().get(KEEP_ON_SIGN_OUT);
                if (keep instanceof java.util.function.BooleanSupplier supplier && supplier.getAsBoolean()) {
                    continue;
                }
                // Đóng cửa sổ.
                stage.close();
            }
        }
    }

    /** Thông báo chờ hiển thị ở màn hình đăng nhập (đọc một lần). */
    public static String consumeNotice() {
        // Lấy thông báo rồi xoá (chỉ hiện một lần).
        String notice = pendingNotice;
        pendingNotice = null;
        return notice;
    }

    /** Mở cửa sổ phụ không chặn (VD: nhiều cửa sổ soạn thư cùng lúc). */
    public static <C> C openWindow(String fxml, String title, double width, double height, boolean modal,
            Consumer<C> init) {
        // Nạp FXML; tạo cửa sổ mới thuộc cửa sổ chính.
        Loaded<C> loaded = load(fxml);
        Stage stage = new Stage();
        stage.initOwner(primaryStage);
        // modal: chặn thao tác với cửa sổ chính khi đang mở.
        if (modal) {
            stage.initModality(Modality.WINDOW_MODAL);
        }
        // Tiêu đề, icon, cảnh có kích thước cho trước, áp CSS.
        stage.setTitle(title);
        stage.getIcons().add(appIcon());
        Scene scene = new Scene(loaded.root(), width, height);
        styleScene(scene);
        stage.setScene(scene);
        // Kích thước tối thiểu (không quá 520x420).
        stage.setMinWidth(Math.min(width, 520));
        stage.setMinHeight(Math.min(height, 420));
        // Cho nơi gọi cấu hình controller trước khi hiện.
        if (init != null) {
            init.accept(loaded.controller());
        }
        // Hiện cửa sổ (không chờ đóng); trả controller.
        stage.show();
        return loaded.controller();
    }

    /** Cửa sổ cha cho hộp thoại / popup. */
    public static Window owner() {
        return primaryStage;
    }

    /** Đường dẫn app.css (dạng URL). */
    public static String stylesheet() {
        return resource("/css/app.css").toExternalForm();
    }

    /** Popup / hộp thoại có stylesheet riêng: ghi nhớ (yếu) để đổi theme cho cả chúng. */
    // Tập yếu (WeakHashMap): popup bị huỷ thì tự rời khỏi tập.
    private static final java.util.Set<javafx.scene.Parent> STYLED_PARENTS = java.util.Collections
            .newSetFromMap(new java.util.WeakHashMap<>());

    /** app.css, cộng dark.css khi bật giao diện tối (dark.css chỉ đổi giá trị token). */
    public static java.util.List<String> stylesheets() {
        // Giao diện tối: app.css + dark.css; sáng: chỉ app.css.
        return Session.darkMode()
                ? java.util.List.of(stylesheet(), resource("/css/dark.css").toExternalForm())
                : java.util.List.of(stylesheet());
    }

    /** Áp CSS theme hiện tại cho một cảnh. */
    public static void styleScene(Scene scene) {
        scene.getStylesheets().setAll(stylesheets());
    }

    /** Áp CSS cho một popup / hộp thoại và ghi nhớ nó để đổi theme sau này. */
    public static void styleParent(javafx.scene.Parent parent) {
        parent.getStylesheets().setAll(stylesheets());
        STYLED_PARENTS.add(parent);
    }

    /** Đổi theme cho MỌI cửa sổ đang mở ngay lập tức (không cần khởi động lại). */
    public static void setDarkMode(boolean dark) {
        // Lưu lựa chọn.
        Session.setDarkMode(dark);
        // Áp lại CSS cho mọi cửa sổ có cảnh (trừ popup, đã xử lý riêng ở dưới).
        for (Window window : Window.getWindows()) {
            if (window.getScene() != null && !(window instanceof javafx.stage.PopupWindow)) {
                styleScene(window.getScene());
            }
        }
        // Áp lại CSS cho các popup / hộp thoại đã ghi nhớ.
        for (javafx.scene.Parent parent : new java.util.ArrayList<>(STYLED_PARENTS)) {
            parent.getStylesheets().setAll(stylesheets());
        }
    }

    /** Kết quả nạp FXML: nút gốc và controller. */
    private record Loaded<C>(Parent root, C controller) {
    }

    /** Nạp tệp FXML; lỗi thì ném UncheckedIOException. */
    private static <C> Loaded<C> load(String fxml) {
        try {
            // FXMLLoader nạp tệp, tạo controller và gán các trường @FXML.
            FXMLLoader loader = new FXMLLoader(resource(fxml));
            Parent root = loader.load();
            return new Loaded<>(root, loader.getController());
        } catch (IOException error) {
            // Không nạp được: báo kèm tên tệp.
            throw new UncheckedIOException("Không nạp được giao diện " + fxml, error);
        }
    }

    /** Tìm tài nguyên trong classpath; thiếu thì báo lỗi. */
    private static URL resource(String path) {
        URL url = Navigator.class.getResource(path);
        if (url == null) {
            throw new IllegalStateException("Thiếu tài nguyên " + path);
        }
        return url;
    }

    /** Icon cửa sổ vẽ bằng code (phong bì xanh), không cần tệp ảnh. */
    private static Image appIcon() {
        // Ảnh 64x64 vẽ từng điểm ảnh.
        int size = 64;
        WritableImage image = new WritableImage(size, size);
        // Màu xanh và trắng.
        Color blue = Color.web("#0b57d0");
        Color white = Color.WHITE;
        // Duyệt từng điểm (x, y).
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                // Trong hình vuông bo góc (khoảng cách tới lõi 36x36 ở giữa < 14)?
                boolean inRound = Math.hypot(Math.max(0, Math.abs(x - 31.5) - 18),
                        Math.max(0, Math.abs(y - 31.5) - 18)) < 14;
                // Mặc định trong suốt.
                Color color = Color.TRANSPARENT;
                if (inRound) {
                    color = blue;
                    // Trong thân phong bì (hình chữ nhật trắng)?
                    boolean inEnvelope = x >= 14 && x <= 49 && y >= 20 && y <= 44;
                    if (inEnvelope) {
                        color = white;
                        // Trên nắp chữ V (đường xiên dày khoảng 1,6px, nửa trên phong bì)?
                        boolean onFlap = Math.abs((y - 20) - Math.abs(x - 31.5) * 0.66) < 1.6 && y <= 33;
                        if (onFlap) {
                            color = blue;
                        }
                    }
                }
                // Ghi màu vào điểm ảnh.
                image.getPixelWriter().setColor(x, y, color);
            }
        }
        return image;
    }
}
