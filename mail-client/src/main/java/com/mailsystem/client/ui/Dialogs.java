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

    private Dialogs() {
    }

    /** Hỏi một dòng chữ (VD tên thư mục). @return chuỗi đã trim, rỗng nếu huỷ */
    public static Optional<String> prompt(Window owner, String title, String label, String initial, String okText) {
        javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog(initial);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("Mail System");
        dialog.setHeaderText(title);
        dialog.setContentText(label);
        com.mailsystem.client.app.Navigator.styleParent(dialog.getDialogPane());
        dialog.getDialogPane().getStyleClass().add("app-dialog");
        ((javafx.scene.control.Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText(okText);
        dialog.getDialogPane().lookupButton(ButtonType.OK).getStyleClass().add("primary-button");
        ((javafx.scene.control.Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("Huỷ");
        return dialog.showAndWait().map(String::trim).filter(value -> !value.isEmpty());
    }

    /**
     * Chọn thời điểm hẹn gửi: vài lựa chọn nhanh (kiểu Gmail) hoặc tự chọn ngày giờ.
     * Thời gian là giờ máy này; Server chạy cùng múi giờ trong mạng nội bộ.
     */
    public static Optional<java.time.LocalDateTime> pickSendTime(Window owner) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now().withSecond(0).withNano(0);
        java.time.LocalDate tomorrow = now.toLocalDate().plusDays(1);
        java.time.LocalDate nextMonday = now.toLocalDate().with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));
        javafx.scene.control.ToggleGroup group = new javafx.scene.control.ToggleGroup();
        java.util.Map<javafx.scene.control.RadioButton, java.time.LocalDateTime> presets = new java.util.LinkedHashMap<>();
        presets.put(new javafx.scene.control.RadioButton("Sau 1 giờ"), now.plusHours(1));
        presets.put(new javafx.scene.control.RadioButton("Sáng mai, 08:00"), tomorrow.atTime(8, 0));
        presets.put(new javafx.scene.control.RadioButton("Chiều mai, 13:00"), tomorrow.atTime(13, 0));
        presets.put(new javafx.scene.control.RadioButton("Sáng thứ Hai, 08:00"), nextMonday.atTime(8, 0));
        javafx.scene.control.RadioButton custom = new javafx.scene.control.RadioButton("Chọn ngày giờ:");
        VBox options = new VBox(8);
        presets.keySet().forEach(radio -> {
            radio.setToggleGroup(group);
            options.getChildren().add(radio);
        });
        custom.setToggleGroup(group);
        presets.keySet().iterator().next().setSelected(true);

        javafx.scene.control.DatePicker date = new javafx.scene.control.DatePicker(tomorrow);
        date.setDayCellFactory(picker -> new javafx.scene.control.DateCell() {
            @Override
            public void updateItem(java.time.LocalDate item, boolean empty) {
                super.updateItem(item, empty);
                setDisable(empty || item.isBefore(now.toLocalDate()) || item.isAfter(now.toLocalDate().plusDays(365)));
            }
        });
        javafx.scene.control.Spinner<Integer> hour = new javafx.scene.control.Spinner<>(0, 23, 8);
        javafx.scene.control.Spinner<Integer> minute = new javafx.scene.control.Spinner<>(0, 59, 0, 5);
        hour.setPrefWidth(76);
        minute.setPrefWidth(76);
        hour.setEditable(true);
        minute.setEditable(true);
        javafx.scene.layout.HBox customRow = new javafx.scene.layout.HBox(8, custom, date, hour, new Label(":"), minute);
        customRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        for (javafx.scene.Node node : new javafx.scene.Node[] {date, hour, minute}) {
            node.setOnMouseClicked(event -> custom.setSelected(true));
        }
        options.getChildren().add(customRow);
        Label error = new Label();
        error.getStyleClass().add("error-text");
        options.getChildren().add(error);

        ButtonType ok = new ButtonType("Hẹn giờ gửi", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Huỷ", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = alert(owner, Alert.AlertType.NONE, "Hẹn giờ gửi thư", null, ok, cancel);
        alert.getDialogPane().setContent(options);
        alert.getDialogPane().lookupButton(ok).getStyleClass().add("primary-button");
        java.util.function.Supplier<java.time.LocalDateTime> chosen = () -> {
            if (custom.isSelected()) {
                return date.getValue() == null ? null : date.getValue().atTime(hour.getValue(), minute.getValue());
            }
            return presets.get((javafx.scene.control.RadioButton) group.getSelectedToggle());
        };
        alert.getDialogPane().lookupButton(ok).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            java.time.LocalDateTime at = chosen.get();
            if (at == null || at.isBefore(java.time.LocalDateTime.now().plusMinutes(1))) {
                error.setText("Chọn một thời điểm ở tương lai (ít nhất 1 phút nữa).");
                event.consume();
            }
        });
        return alert.showAndWait().filter(ok::equals).map(button -> chosen.get());
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
        com.mailsystem.client.app.Navigator.styleParent(pane);
        pane.getStyleClass().add("app-dialog");
        pane.setMinWidth(420);
        return alert;
    }
}
