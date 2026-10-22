package com.mailsystem.client.ui;

import com.mailsystem.common.model.ScheduledMail;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/** Một thư đang chờ gửi (hẹn giờ): thời điểm gửi, người nhận, tiêu đề, nút huỷ để sửa. */
public class ScheduledCell extends ListCell<ScheduledMail> {

    private final Label when = new Label();
    private final Label to = new Label();
    private final Label subject = new Label();
    private final Label files = new Label();
    private final HBox root;

    public ScheduledCell(Consumer<ScheduledMail> onCancel) {
        when.getStyleClass().add("schedule-tag");
        when.setMinWidth(Region.USE_PREF_SIZE);
        to.getStyleClass().add("cell-sender");
        subject.getStyleClass().add("cell-subject");
        files.getStyleClass().add("hint");
        for (Label label : new Label[] {to, subject}) {
            label.setTextOverrun(OverrunStyle.ELLIPSIS);
            label.setPrefWidth(1);
            label.setMaxWidth(Double.MAX_VALUE);
            label.setMinWidth(0);
        }
        Button cancel = new Button("Huỷ & sửa", Icons.of(Icons.COMPOSE, 16));
        cancel.getStyleClass().add("text-button");
        cancel.setOnAction(event -> {
            if (getItem() != null) {
                onCancel.accept(getItem());
            }
        });
        HBox top = new HBox(8, Icons.of(Icons.SCHEDULE, 16), when, files);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox text = new VBox(3, top, to, subject);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        root = new HBox(10, text, cancel);
        root.setAlignment(Pos.CENTER_LEFT);
        setText(null);
        setPrefWidth(0);
    }

    @Override
    protected void updateItem(ScheduledMail mail, boolean empty) {
        super.updateItem(mail, empty);
        if (empty || mail == null) {
            setGraphic(null);
            return;
        }
        when.setText("Gửi lúc " + Formats.longDate(mail.getSendAt()));
        to.setText("Tới: " + (mail.getTo() == null || mail.getTo().isBlank() ? "—" : mail.getTo()));
        subject.setText(mail.getSubject() == null || mail.getSubject().isBlank() ? "(Không có tiêu đề)" : mail.getSubject());
        files.setText(mail.getAttachmentCount() > 0 ? "· " + mail.getAttachmentCount() + " tệp" : "");
        setGraphic(root);
    }
}
