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

import java.util.List;
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
     *
     * Chỉnh ngày / giờ / phút là tự chọn mục "Chọn ngày giờ"; giờ gõ tay được ghi nhận
     * ngay (không cần Enter). Dòng "Thư sẽ được gửi lúc" luôn cho thấy đúng thời điểm sẽ hẹn.
     */
    public static Optional<java.time.LocalDateTime> pickSendTime(Window owner) {
        return pickSendTime(owner, null);
    }

    /** initial: thời điểm đã hẹn trước đó (sửa thư đã lên lịch) để điền sẵn; null = mặc định "Sau 1 giờ". */
    public static Optional<java.time.LocalDateTime> pickSendTime(Window owner, java.time.LocalDateTime initial) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now().withSecond(0).withNano(0);
        java.time.LocalDate tomorrow = now.toLocalDate().plusDays(1);
        java.time.LocalDate nextMonday = now.toLocalDate().with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));

        // Mỗi lựa chọn là một thẻ (ToggleButton): icon + tên + giờ cụ thể căn phải.
        javafx.scene.control.ToggleGroup group = new javafx.scene.control.ToggleGroup();
        java.util.Map<javafx.scene.control.Toggle, java.time.LocalDateTime> presets = new java.util.LinkedHashMap<>();
        VBox options = new VBox(6);
        options.getStyleClass().add("schedule-options");
        Object[][] choices = {
            {Icons.CLOCK, "Sau 1 giờ", now.plusHours(1)},
            {Icons.SUNRISE, "Sáng mai", tomorrow.atTime(8, 0)},
            {Icons.SUNNY, "Chiều mai", tomorrow.atTime(13, 0)},
            {Icons.CALENDAR_WEEK, "Sáng thứ Hai", nextMonday.atTime(8, 0)},
        };
        for (Object[] choice : choices) {
            java.time.LocalDateTime time = (java.time.LocalDateTime) choice[2];
            javafx.scene.control.ToggleButton option = scheduleOption((String) choice[0], (String) choice[1], describe(time, now));
            option.setToggleGroup(group);
            presets.put(option, time);
            options.getChildren().add(option);
        }
        javafx.scene.control.ToggleButton custom = scheduleOption(Icons.CALENDAR_EDIT, "Chọn ngày giờ", "");
        custom.setToggleGroup(group);
        // Bấm lại thẻ đang chọn không được bỏ chọn (luôn có đúng một lựa chọn).
        group.selectedToggleProperty().addListener((obs, old, value) -> {
            if (value == null && old != null) {
                old.setSelected(true);
            }
        });
        group.getToggles().get(0).setSelected(true);

        javafx.scene.control.DatePicker date = new javafx.scene.control.DatePicker(tomorrow);
        // Mặc định theo máy (VD Windows tiếng Anh: 11/5/2026 = tháng/ngày) dễ đọc nhầm: dùng ngày/tháng/năm.
        java.time.format.DateTimeFormatter dayFormat = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        date.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(java.time.LocalDate value) {
                return value == null ? "" : dayFormat.format(value);
            }

            @Override
            public java.time.LocalDate fromString(String text) {
                return text == null || text.isBlank() ? null : java.time.LocalDate.parse(text.trim(), dayFormat);
            }
        });
        date.setPromptText("dd/MM/yyyy");
        date.setPrefWidth(150);
        date.setDayCellFactory(picker -> new javafx.scene.control.DateCell() {
            @Override
            public void updateItem(java.time.LocalDate item, boolean empty) {
                super.updateItem(item, empty);
                setDisable(empty || item.isBefore(now.toLocalDate()) || item.isAfter(now.toLocalDate().plusDays(365)));
            }
        });
        javafx.scene.control.Spinner<Integer> hour = new javafx.scene.control.Spinner<>(0, 23, 8);
        javafx.scene.control.Spinner<Integer> minute = new javafx.scene.control.Spinner<>(0, 59, 0, 5);
        for (javafx.scene.control.Spinner<Integer> spinner : List.of(hour, minute)) {
            spinner.setPrefWidth(84);
            spinner.setEditable(true);
            spinner.getStyleClass().add("time-spinner");
            // Luôn hiện 2 chữ số: 09:05 thay vì 9:5.
            spinner.getValueFactory().setConverter(new javafx.util.StringConverter<>() {
                @Override
                public String toString(Integer value) {
                    return value == null ? "" : String.format("%02d", value);
                }

                @Override
                public Integer fromString(String text) {
                    try {
                        return Integer.valueOf(text.trim());
                    } catch (RuntimeException error) {
                        return spinner.getValue();
                    }
                }
            });
            spinner.getEditor().setText(String.format("%02d", spinner.getValue()));
        }
        Label colon = new Label(":");
        colon.getStyleClass().add("schedule-colon");
        Label dateLabel = new Label("Ngày");
        dateLabel.getStyleClass().add("hint");
        Label timeLabel = new Label("Giờ (24h)");
        timeLabel.getStyleClass().add("hint");
        javafx.scene.layout.HBox timeRow = new javafx.scene.layout.HBox(6, hour, colon, minute);
        timeRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        javafx.scene.layout.GridPane fields = new javafx.scene.layout.GridPane();
        fields.getStyleClass().add("schedule-fields");
        fields.setHgap(12);
        fields.setVgap(4);
        fields.add(dateLabel, 0, 0);
        fields.add(timeLabel, 1, 0);
        fields.add(date, 0, 1);
        fields.add(timeRow, 1, 1);
        VBox customPanel = new VBox(custom, fields);
        customPanel.getStyleClass().add("schedule-custom");
        options.getChildren().add(customPanel);

        // Dải tóm tắt: luôn cho biết chính xác thời điểm sẽ hẹn.
        Label summary = new Label();
        summary.getStyleClass().add("schedule-summary-text");
        summary.setWrapText(true);
        javafx.scene.layout.HBox summaryBox = new javafx.scene.layout.HBox(10, Icons.of(Icons.SENT, 18), summary);
        summaryBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        summaryBox.getStyleClass().add("schedule-summary");
        Label error = new Label();
        error.getStyleClass().add("error-text");
        error.setWrapText(true);
        Label subtitle = new Label("Thư được giữ ở mục Đã lên lịch và tự gửi đúng giờ, kể cả khi bạn tắt ứng dụng.");
        subtitle.getStyleClass().add("hint");
        subtitle.setWrapText(true);
        VBox content = new VBox(14, subtitle, options, summaryBox, error);
        content.getStyleClass().add("schedule-dialog");
        content.setPrefWidth(460);

        java.util.function.Supplier<java.time.LocalDateTime> chosen = () -> {
            if (custom.isSelected()) {
                return date.getValue() == null ? null : date.getValue().atTime(hour.getValue(), minute.getValue());
            }
            return presets.get(group.getSelectedToggle());
        };
        Runnable refresh = () -> {
            java.time.LocalDateTime at = chosen.get();
            summary.setText(at == null ? "Chọn ngày gửi." : "Thư sẽ được gửi lúc: " + describe(at, java.time.LocalDateTime.now()));
            customPanel.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("active"), custom.isSelected());
            error.setText("");
        };
        // Đụng vào ô ngày / giờ / phút (focus, gõ, mở lịch, bấm vào khung) là chọn "Chọn ngày giờ".
        Runnable pickCustom = () -> {
            custom.setSelected(true);
            refresh.run();
        };
        date.valueProperty().addListener((obs, old, value) -> pickCustom.run());
        date.showingProperty().addListener((obs, was, showing) -> {
            if (showing) {
                pickCustom.run();
            }
        });
        date.getEditor().focusedProperty().addListener((obs, was, focused) -> {
            if (focused) {
                pickCustom.run();
            }
        });
        date.getEditor().textProperty().addListener((obs, old, text) -> {
            if (date.getEditor().isFocused()) {
                try {
                    java.time.LocalDate typed = date.getConverter().fromString(text);
                    if (typed != null) {
                        date.setValue(typed);
                    }
                } catch (RuntimeException ignored) {
                    // đang gõ dở, chưa thành ngày hợp lệ
                }
            }
        });
        commitWhileTyping(hour, 0, 23, pickCustom);
        commitWhileTyping(minute, 0, 59, pickCustom);
        fields.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> pickCustom.run());
        group.selectedToggleProperty().addListener((obs, old, value) -> refresh.run());
        if (initial != null) {
            // Sửa thư đã lên lịch: điền sẵn đúng ngày giờ đã hẹn.
            date.setValue(initial.toLocalDate());
            hour.getValueFactory().setValue(initial.getHour());
            minute.getValueFactory().setValue(initial.getMinute());
            custom.setSelected(true);
        }
        refresh.run();

        ButtonType ok = new ButtonType("Hẹn giờ gửi", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Huỷ", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = alert(owner, Alert.AlertType.NONE, "Hẹn giờ gửi thư", null, ok, cancel);
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().getStyleClass().add("schedule-pane");
        alert.getDialogPane().lookupButton(ok).getStyleClass().add("primary-button");
        alert.getDialogPane().lookupButton(cancel).getStyleClass().add("secondary-button");
        alert.getDialogPane().lookupButton(ok).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            java.time.LocalDateTime at = chosen.get();
            if (at == null || at.isBefore(java.time.LocalDateTime.now().plusMinutes(1))) {
                error.setText("Chọn một thời điểm ở tương lai (ít nhất 1 phút nữa).");
                event.consume();
            }
        });
        return alert.showAndWait().filter(ok::equals).map(button -> chosen.get());
    }

    /** Một thẻ lựa chọn trong hộp thoại hẹn giờ: icon + tên + mô tả thời gian (căn phải). */
    private static javafx.scene.control.ToggleButton scheduleOption(String icon, String name, String when) {
        Label title = new Label(name);
        title.getStyleClass().add("schedule-option-title");
        Label time = new Label(when);
        time.getStyleClass().add("schedule-option-time");
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(12, Icons.of(icon, 20), title, spacer, time);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        javafx.scene.control.ToggleButton option = new javafx.scene.control.ToggleButton();
        option.setGraphic(row);
        option.getStyleClass().setAll("schedule-option");
        option.setMaxWidth(Double.MAX_VALUE);
        row.prefWidthProperty().bind(option.widthProperty().subtract(32));
        return option;
    }

    /** Ô số giờ / phút: số gõ tay được nhận ngay (Spinner mặc định chỉ nhận khi bấm Enter). */
    private static void commitWhileTyping(javafx.scene.control.Spinner<Integer> spinner, int min, int max, Runnable onChange) {
        spinner.valueProperty().addListener((obs, old, value) -> onChange.run());
        spinner.getEditor().textProperty().addListener((obs, old, text) -> {
            try {
                int value = Integer.parseInt(text.trim());
                if (value >= min && value <= max && value != spinner.getValue()) {
                    spinner.getValueFactory().setValue(value);
                }
            } catch (NumberFormatException ignored) {
                // ô đang trống / gõ dở
            }
        });
        spinner.getEditor().focusedProperty().addListener((obs, was, focused) -> {
            if (focused) {
                onChange.run();
            } else {
                spinner.getEditor().setText(spinner.getValueFactory().getConverter().toString(spinner.getValue()));
            }
        });
    }

    /** "18:08 hôm nay", "08:00 ngày mai", "08:00, thứ Hai 09/11/2026". */
    private static String describe(java.time.LocalDateTime time, java.time.LocalDateTime now) {
        String clock = String.format("%02d:%02d", time.getHour(), time.getMinute());
        if (time.toLocalDate().equals(now.toLocalDate())) {
            return clock + " hôm nay";
        }
        if (time.toLocalDate().equals(now.toLocalDate().plusDays(1))) {
            return clock + " ngày mai";
        }
        String[] days = {"thứ Hai", "thứ Ba", "thứ Tư", "thứ Năm", "thứ Sáu", "thứ Bảy", "Chủ nhật"};
        return clock + ", " + days[time.getDayOfWeek().getValue() - 1] + " "
                + time.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
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
