// Gói "ui": các thành phần giao diện tự viết (JavaFX).
package com.mailsystem.client.ui;

// DTO bản nháp; các lớp JavaFX.
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

    // Các nhãn: người nhận, ngày sửa, tiêu đề, đoạn trích; khung gốc của ô.
    private final Label to = new Label();
    private final Label date = new Label();
    private final Label subject = new Label();
    private final Label snippet = new Label();
    private final HBox root;

    /** Dựng giao diện ô một lần (ô được dùng lại khi cuộn). */
    public DraftCell() {
        // Nhãn "Nháp".
        Label tag = new Label("Nháp");
        tag.getStyleClass().add("draft-tag");
        // Kiểu CSS cho từng nhãn; chữ dài hiện "…"; cho phép co nhỏ.
        to.getStyleClass().add("cell-sender");
        to.setTextOverrun(OverrunStyle.ELLIPSIS);
        to.setMinWidth(0);
        date.getStyleClass().add("cell-date");
        date.setMinWidth(Region.USE_PREF_SIZE);
        subject.getStyleClass().add("cell-subject");
        subject.setMinWidth(0);
        snippet.getStyleClass().add("cell-snippet");
        snippet.setMinWidth(0);
        // Ba nhãn co giãn theo bề rộng ô (không đẩy ô rộng ra).
        for (Label label : new Label[] {to, subject, snippet}) {
            label.setPrefWidth(1);
            label.setMaxWidth(Double.MAX_VALUE);
        }
        // Hàng trên: "Nháp", người nhận (giãn), ngày.
        HBox.setHgrow(to, Priority.ALWAYS);
        HBox top = new HBox(8, tag, to, date);
        top.setAlignment(Pos.CENTER_LEFT);
        // Cột chữ: hàng trên, tiêu đề, đoạn trích.
        VBox text = new VBox(3, top, subject, snippet);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        // Gốc: icon bản nháp + cột chữ.
        root = new HBox(12, Icons.of(Icons.DRAFT, 22), text);
        root.setAlignment(Pos.CENTER_LEFT);
        // Ô không đòi bề rộng riêng (theo bề rộng danh sách).
        setPrefWidth(0);
    }

    /** JavaFX gọi khi ô được gán bản nháp khác (hoặc thành trống). */
    @Override
    protected void updateItem(Draft draft, boolean empty) {
        super.updateItem(draft, empty);
        setText(null);
        // Ô trống: không hiển thị gì.
        if (empty || draft == null) {
            setGraphic(null);
            return;
        }
        // Điền nội dung (giá trị trống thì hiện chữ thay thế).
        to.setText(draft.getTo() == null || draft.getTo().isBlank() ? "(Chưa có người nhận)" : "Tới: " + draft.getTo());
        date.setText(Formats.shortDate(draft.getUpdatedAt()));
        subject.setText(draft.getSubject() == null || draft.getSubject().isBlank() ? "(Không có tiêu đề)" : draft.getSubject());
        // Đoạn trích: gộp khoảng trắng, tối đa 160 ký tự.
        String body = draft.getBody() == null ? "" : draft.getBody().replaceAll("\\s+", " ").trim();
        snippet.setText(body.isEmpty() ? " " : body.length() > 160 ? body.substring(0, 160) : body);
        // Hiện khung gốc.
        setGraphic(root);
    }
}
