package com.mailsystem.client.ui;

import com.mailsystem.common.model.MailSummary;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * Một dòng trong danh sách thư: ảnh đại diện, người gửi, ngày, tiêu đề + đoạn
 * trích, ngôi sao, kẹp giấy. Thư chưa đọc in đậm và có chấm xanh.
 */
public class MailCell extends ListCell<MailSummary> {

    private final Avatar avatar = new Avatar(36);
    private final Region unreadDot = new Region();
    private final Label sender = new Label();
    private final Label folderTag = new Label();
    private final Label date = new Label();
    private final Label subject = new Label();
    private final Label snippet = new Label();
    private final StackPane attachmentIcon = new StackPane(Icons.of(Icons.ATTACH, 15));
    private final Button star = new Button();
    private final HBox root;

    public MailCell(Consumer<MailSummary> onToggleStar, boolean showFolderTag) {
        unreadDot.getStyleClass().add("unread-dot");
        sender.getStyleClass().add("cell-sender");
        sender.setTextOverrun(OverrunStyle.ELLIPSIS);
        sender.setMinWidth(0);
        folderTag.getStyleClass().add("folder-tag");
        folderTag.setVisible(showFolderTag);
        folderTag.setManaged(showFolderTag);
        date.getStyleClass().add("cell-date");
        date.setMinWidth(Region.USE_PREF_SIZE);
        subject.getStyleClass().add("cell-subject");
        subject.setTextOverrun(OverrunStyle.ELLIPSIS);
        subject.setMinWidth(0);
        snippet.getStyleClass().add("cell-snippet");
        snippet.setTextOverrun(OverrunStyle.ELLIPSIS);
        snippet.setMinWidth(0);
        star.getStyleClass().add("star-button");
        star.setFocusTraversable(false);
        star.setOnAction(event -> {
            if (getItem() != null) {
                onToggleStar.accept(getItem());
            }
        });

        // prefWidth nhỏ + maxWidth vô hạn: nhãn co giãn theo bề rộng ô thay vì đẩy ô
        // rộng theo độ dài chữ (đoạn trích dài sẽ làm mất cột ngày và ngôi sao).
        for (Label label : new Label[] {sender, subject, snippet}) {
            label.setPrefWidth(1);
            label.setMaxWidth(Double.MAX_VALUE);
        }
        HBox top = new HBox(6, sender, folderTag, attachmentIcon, date);
        top.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(sender, Priority.ALWAYS);
        VBox text = new VBox(3, top, subject, snippet);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        StackPane dotHolder = new StackPane(unreadDot);
        dotHolder.setMinWidth(10);
        VBox starHolder = new VBox(star);
        starHolder.setAlignment(Pos.TOP_CENTER);
        root = new HBox(10, dotHolder, avatar, text, starHolder);
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(0));
        setText(null);
        setPrefWidth(0); // bề rộng ô do ListView quyết định, không do nội dung
    }

    @Override
    protected void updateItem(MailSummary mail, boolean empty) {
        super.updateItem(mail, empty);
        getStyleClass().remove("unread");
        if (empty || mail == null) {
            setGraphic(null);
            return;
        }
        String name = mail.getSenderName() == null || mail.getSenderName().isBlank()
                ? mail.getSenderEmail() : mail.getSenderName();
        avatar.set(name, mail.getSenderEmail());
        sender.setText(name);
        date.setText(Formats.shortDate(mail.getSentAt()));
        subject.setText(mail.getSubject() == null || mail.getSubject().isBlank() ? "(Không có tiêu đề)" : mail.getSubject());
        snippet.setText(mail.getSnippet() == null || mail.getSnippet().isBlank() ? " " : mail.getSnippet());
        attachmentIcon.setVisible(mail.isHasAttachments());
        attachmentIcon.setManaged(mail.isHasAttachments());
        folderTag.setText(folderName(mail.getFolderType()));
        boolean unread = !mail.isRead();
        unreadDot.setVisible(unread);
        if (unread) {
            getStyleClass().add("unread");
        }
        star.getStyleClass().remove("starred");
        if (mail.isStarred()) {
            star.getStyleClass().add("starred");
        }
        star.setGraphic(Icons.of(mail.isStarred() ? Icons.STAR : Icons.STAR_OUTLINE, 18));
        star.setTooltip(new Tooltip(mail.isStarred() ? "Bỏ gắn sao" : "Gắn sao"));
        setGraphic(root);
    }

    private static String folderName(String type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case "INBOX" -> "Hộp thư đến";
            case "SENT" -> "Đã gửi";
            case "TRASH" -> "Thùng rác";
            default -> "Thư mục";
        };
    }
}
