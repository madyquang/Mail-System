package com.mailsystem.client.ui;

import com.mailsystem.common.model.Draft;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Một dòng trong danh sách Thư nháp. */
public class DraftCell extends ListCell<Draft> {

    private final Label to = new Label();
    private final Label date = new Label();
    private final Label subject = new Label();
    private final Label snippet = new Label();
    private final HBox root;

    public DraftCell() {
        Label tag = new Label("Nháp");
        tag.getStyleClass().add("draft-tag");
        to.getStyleClass().add("cell-sender");
        to.setTextOverrun(OverrunStyle.ELLIPSIS);
        to.setMinWidth(0);
        date.getStyleClass().add("cell-date");
        date.setMinWidth(Region.USE_PREF_SIZE);
        subject.getStyleClass().add("cell-subject");
        subject.setMinWidth(0);
        snippet.getStyleClass().add("cell-snippet");
        snippet.setMinWidth(0);
        for (Label label : new Label[] {to, subject, snippet}) {
            label.setPrefWidth(1);
            label.setMaxWidth(Double.MAX_VALUE);
        }
        HBox.setHgrow(to, Priority.ALWAYS);
        HBox top = new HBox(8, tag, to, date);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox text = new VBox(3, top, subject, snippet);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        root = new HBox(12, Icons.of(Icons.DRAFT, 22), text);
        root.setAlignment(Pos.CENTER_LEFT);
        setPrefWidth(0);
    }

    @Override
    protected void updateItem(Draft draft, boolean empty) {
        super.updateItem(draft, empty);
        setText(null);
        if (empty || draft == null) {
            setGraphic(null);
            return;
        }
        to.setText(draft.getTo() == null || draft.getTo().isBlank() ? "(Chưa có người nhận)" : "Tới: " + draft.getTo());
        date.setText(Formats.shortDate(draft.getUpdatedAt()));
        subject.setText(draft.getSubject() == null || draft.getSubject().isBlank() ? "(Không có tiêu đề)" : draft.getSubject());
        String body = draft.getBody() == null ? "" : draft.getBody().replaceAll("\\s+", " ").trim();
        snippet.setText(body.isEmpty() ? " " : body.length() > 160 ? body.substring(0, 160) : body);
        setGraphic(root);
    }
}
