// Gói "ui": các thành phần giao diện tự viết (JavaFX).
package com.mailsystem.client.ui;

// DTO dòng thư; các lớp JavaFX; Consumer (hàm nhận một giá trị).
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

    // Các phần của một dòng: ảnh đại diện, chấm chưa đọc, người gửi, nhãn thư mục, số thư hội thoại,
    // ngày, tiêu đề, đoạn trích, icon kẹp giấy, nút ngôi sao (icon + chú thích), khung gốc.
    private final Avatar avatar = new Avatar(36);
    private final Region unreadDot = new Region();
    private final Label sender = new Label();
    private final Label folderTag = new Label();
    private final Label threadCount = new Label();
    private final Label date = new Label();
    private final Label subject = new Label();
    private final Label snippet = new Label();
    private final StackPane attachmentIcon = new StackPane(Icons.of(Icons.ATTACH, 15));
    private final Button star = new Button();
    private final org.kordamp.ikonli.javafx.FontIcon starIcon = Icons.of(Icons.STAR_OUTLINE, 18);
    private final Tooltip starTooltip = new Tooltip();
    private final HBox root;

    /**
     * Dựng giao diện ô một lần (ô được ListView dùng lại khi cuộn).
     *
     * @param onToggleStar  việc làm khi bấm ngôi sao
     * @param showFolderTag có hiện tên thư mục không (dùng ở mục Có gắn sao)
     */
    public MailCell(Consumer<MailSummary> onToggleStar, boolean showFolderTag) {
        // Gắn lớp CSS và cấu hình từng nhãn (chữ dài hiện "…", cho phép co nhỏ).
        unreadDot.getStyleClass().add("unread-dot");
        sender.getStyleClass().add("cell-sender");
        sender.setTextOverrun(OverrunStyle.ELLIPSIS);
        sender.setMinWidth(0);
        folderTag.getStyleClass().add("folder-tag");
        // Nhãn thư mục chỉ hiện khi được yêu cầu.
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
        // Nút ngôi sao: không nhận Tab, có icon và chú thích; bấm thì gọi onToggleStar với thư của ô.
        star.getStyleClass().add("star-button");
        star.setFocusTraversable(false);
        star.setGraphic(starIcon);
        star.setTooltip(starTooltip);
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
        // Số thư hội thoại không bị co.
        threadCount.getStyleClass().add("thread-badge");
        threadCount.setMinWidth(Region.USE_PREF_SIZE);
        // Hàng trên: người gửi (giãn), số thư, thư mục, kẹp giấy, ngày.
        HBox top = new HBox(6, sender, threadCount, folderTag, attachmentIcon, date);
        top.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(sender, Priority.ALWAYS);
        // Cột chữ: hàng trên, tiêu đề, đoạn trích.
        VBox text = new VBox(3, top, subject, snippet);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        // Cột chấm chưa đọc (rộng tối thiểu 10px) và cột ngôi sao.
        StackPane dotHolder = new StackPane(unreadDot);
        dotHolder.setMinWidth(10);
        VBox starHolder = new VBox(star);
        starHolder.setAlignment(Pos.TOP_CENTER);
        // Gốc: chấm, ảnh đại diện, cột chữ, ngôi sao.
        root = new HBox(10, dotHolder, avatar, text, starHolder);
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(0));
        setText(null);
        setPrefWidth(0); // bề rộng ô do ListView quyết định, không do nội dung
    }

    /** JavaFX gọi khi ô được gán thư khác (hoặc thành trống). */
    @Override
    protected void updateItem(MailSummary mail, boolean empty) {
        super.updateItem(mail, empty);
        // Bỏ kiểu "chưa đọc" của lần hiển thị trước.
        getStyleClass().remove("unread");
        // Ô trống: không hiển thị gì.
        if (empty || mail == null) {
            setGraphic(null);
            return;
        }
        // Tên người gửi (trống thì email); ảnh đại diện theo tên và email.
        String name = mail.getSenderName() == null || mail.getSenderName().isBlank()
                ? mail.getSenderEmail() : mail.getSenderName();
        avatar.set(name, mail.getSenderEmail());
        sender.setText(name);
        // Hội thoại nhiều thư: hiện số thư như Gmail ("Lan, tôi  3").
        boolean thread = mail.getThreadCount() > 1;
        threadCount.setText(thread ? Integer.toString(mail.getThreadCount()) : "");
        threadCount.setVisible(thread);
        threadCount.setManaged(thread);
        // Ngày, tiêu đề, đoạn trích (trống thì chữ thay thế).
        date.setText(Formats.shortDate(mail.getSentAt()));
        subject.setText(mail.getSubject() == null || mail.getSubject().isBlank() ? "(Không có tiêu đề)" : mail.getSubject());
        snippet.setText(mail.getSnippet() == null || mail.getSnippet().isBlank() ? " " : mail.getSnippet());
        // Kẹp giấy chỉ hiện khi có tệp đính kèm.
        attachmentIcon.setVisible(mail.isHasAttachments());
        attachmentIcon.setManaged(mail.isHasAttachments());
        // Tên thư mục (chỉ thấy khi showFolderTag).
        folderTag.setText(folderName(mail.getFolderType()));
        // Chưa đọc: hiện chấm và thêm kiểu CSS "unread" (in đậm).
        boolean unread = !mail.isRead();
        unreadDot.setVisible(unread);
        if (unread) {
            getStyleClass().add("unread");
        }
        // Ngôi sao: kiểu CSS, icon đặc / rỗng, chú thích theo trạng thái.
        star.getStyleClass().remove("starred");
        if (mail.isStarred()) {
            star.getStyleClass().add("starred");
        }
        starIcon.setIconLiteral(mail.isStarred() ? Icons.STAR : Icons.STAR_OUTLINE);
        starTooltip.setText(mail.isStarred() ? "Bỏ gắn sao" : "Gắn sao");
        // Hiện khung gốc.
        setGraphic(root);
    }

    // Tên thư mục tiếng Việt theo loại.
    private static String folderName(String type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case "INBOX" -> "Hộp thư đến";
            case "SENT" -> "Đã gửi";
            case "TRASH" -> "Thùng rác";
            default -> "Thư mục riêng";
        };
    }
}
