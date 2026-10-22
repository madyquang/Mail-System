package com.mailsystem.client.ui;

import com.mailsystem.client.network.ServerIdentity;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Hộp thoại dùng chung, cùng phong cách với ứng dụng. */
public final class Dialogs {

    private static String stylesheet;

    private Dialogs() {
    }

    public static void setStylesheet(String css) {
        stylesheet = css;
    }

    /** Hỏi xác nhận; danger = true tô đỏ nút đồng ý (thao tác không hoàn tác). */
    public static boolean confirm(Window owner, String title, String message, String okText, boolean danger) {
        ButtonType ok = new ButtonType(okText, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Huỷ", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = alert(owner, Alert.AlertType.CONFIRMATION, title, message, ok, cancel);
        alert.getDialogPane().lookupButton(ok).getStyleClass().add(danger ? "danger-button" : "primary-button");
        return alert.showAndWait().filter(ok::equals).isPresent();
    }

    public enum UnsavedChoice { SAVE, DISCARD, CANCEL }

    public static UnsavedChoice unsavedDraft(Window owner) {
        ButtonType save = new ButtonType("Lưu nháp", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Bỏ thư", ButtonBar.ButtonData.NO);
        ButtonType cancel = new ButtonType("Tiếp tục soạn", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = alert(owner, Alert.AlertType.CONFIRMATION, "Thư chưa được gửi",
                "Bạn có muốn lưu thư này vào Thư nháp không?", save, discard, cancel);
        alert.getDialogPane().lookupButton(save).getStyleClass().add("primary-button");
        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() == cancel) {
            return UnsavedChoice.CANCEL;
        }
        return choice.get() == save ? UnsavedChoice.SAVE : UnsavedChoice.DISCARD;
    }

    public static void info(Window owner, String title, String message) {
        alert(owner, Alert.AlertType.INFORMATION, title, message, ButtonType.OK).showAndWait();
    }

    /**
     * Hỏi người dùng có tin chứng chỉ máy chủ không. Được gọi từ thread nền
     * (trong lúc bắt tay TLS) nên chuyển sang UI thread và chờ kết quả.
     */
    public static boolean trustServer(Window owner, ServerIdentity identity) {
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                answer.complete(showTrustDialog(owner, identity));
            } catch (RuntimeException error) {
                answer.complete(false);
            }
        });
        return answer.join();
    }

    private static boolean showTrustDialog(Window owner, ServerIdentity identity) {
        ButtonType trust = new ButtonType(identity.changed() ? "Vẫn tin chứng chỉ mới" : "Tin và kết nối",
                ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Huỷ kết nối", ButtonBar.ButtonData.CANCEL_CLOSE);
        String title = identity.changed()
                ? "CẢNH BÁO: chứng chỉ máy chủ đã thay đổi"
                : "Kết nối lần đầu tới " + identity.host() + ":" + identity.port();
        String message = identity.changed()
                ? "Máy chủ đang trình một chứng chỉ KHÁC với lần trước. Điều này xảy ra khi quản trị viên "
                        + "tạo lại chứng chỉ, NHƯNG cũng có thể là có kẻ đang nghe lén (man-in-the-middle). "
                        + "Chỉ tiếp tục nếu dấu vân tay mới khớp với dòng \"[TLS] Dau van tay\" mà Server in ra."
                : "Kết nối được mã hoá bằng TLS 1.3. Máy chủ dùng chứng chỉ tự ký, hãy đối chiếu dấu vân tay "
                        + "dưới đây với dòng \"[TLS] Dau van tay\" mà Server in ra khi khởi động. Ứng dụng sẽ ghi nhớ "
                        + "và tự kiểm tra ở các lần sau.";
        Alert alert = alert(owner, identity.changed() ? Alert.AlertType.WARNING : Alert.AlertType.CONFIRMATION,
                title, null, trust, cancel);
        TextArea fingerprint = new TextArea(identity.fingerprint()
                + (identity.changed() ? "\n\nDấu vân tay cũ:\n" + identity.previousFingerprint() : ""));
        fingerprint.setEditable(false);
        fingerprint.setWrapText(true);
        fingerprint.setPrefRowCount(identity.changed() ? 5 : 2);
        fingerprint.getStyleClass().add("fingerprint");
        Label text = new Label(message);
        text.setWrapText(true);
        Label label = new Label("Dấu vân tay SHA-256 của chứng chỉ:");
        label.getStyleClass().add("field-label");
        VBox content = new VBox(10, text, label, fingerprint);
        content.setPrefWidth(520);
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().lookupButton(trust).getStyleClass()
                .add(identity.changed() ? "danger-button" : "primary-button");
        return alert.showAndWait().filter(trust::equals).isPresent();
    }

    private static Alert alert(Window owner, Alert.AlertType type, String title, String message, ButtonType... buttons) {
        Alert alert = new Alert(type, message == null ? "" : message, buttons);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle("Mail System");
        alert.setHeaderText(title);
        DialogPane pane = alert.getDialogPane();
        if (stylesheet != null) {
            pane.getStylesheets().add(stylesheet);
        }
        pane.getStyleClass().add("app-dialog");
        pane.setMinWidth(420);
        return alert;
    }
}
