package com.mailsystem.client.ui;

import com.mailsystem.client.service.AttachmentTransfer;
import com.mailsystem.common.model.Attachment;
import com.mailsystem.common.model.Draft;
import com.mailsystem.common.model.MailDetail;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Khung đọc thư (cột phải của bố cục 3 cột). Hiển thị một trong các trạng
 * thái: trống, đang tải, lỗi, nhiều thư đang chọn, chi tiết thư, xem trước thư
 * nháp. Mọi thao tác được báo ra ngoài qua Actions để Controller xử lý.
 */
public class MailReader extends StackPane {

    /** Thao tác người dùng bấm trong khung đọc. */
    public interface Actions {
        void reply(MailDetail mail);

        void replyAll(MailDetail mail);

        void forward(MailDetail mail);

        void toggleStar(MailDetail mail);

        void markUnread(MailDetail mail);

        void trash(MailDetail mail);

        void restore(MailDetail mail);

        void deleteForever(MailDetail mail);

        void editDraft(Draft draft);

        void deleteDraft(Draft draft);
    }

    private final Actions actions;
    private final StackPane toastRoot;

    public MailReader(Actions actions, StackPane toastRoot) {
        this.actions = actions;
        this.toastRoot = toastRoot;
        getStyleClass().add("reader");
        showEmpty();
    }

    public void showEmpty() {
        getChildren().setAll(new EmptyState().show(Icons.MAIL, "Chọn một thư để đọc",
                "Nội dung thư sẽ hiển thị ở đây. Mẹo: Ctrl/Shift + click để chọn nhiều thư."));
    }

    public void showLoading() {
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setMaxSize(36, 36);
        getChildren().setAll(indicator);
    }

    public void showError(String message) {
        getChildren().setAll(new EmptyState().show(Icons.ERROR, "Không tải được thư", message));
    }

    public void showSelection(int count) {
        getChildren().setAll(new EmptyState().show(Icons.SELECT_ALL, "Đã chọn " + count + " thư",
                "Dùng thanh thao tác phía trên danh sách để đánh dấu, gắn sao hoặc xoá các thư đã chọn."));
    }

    // ------------------------------------------------------------------ chi tiết thư

    public void showMail(MailDetail mail, String myEmail) {
        boolean inTrash = "TRASH".equals(mail.getFolderType());

        HBox toolbar = new HBox(2);
        toolbar.getStyleClass().add("reader-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        toolbar.getChildren().addAll(
                iconButton(Icons.REPLY, "Trả lời (R)", () -> actions.reply(mail)),
                iconButton(Icons.REPLY_ALL, "Trả lời tất cả (A)", () -> actions.replyAll(mail)),
                iconButton(Icons.FORWARD, "Chuyển tiếp (F)", () -> actions.forward(mail)),
                new Separator(javafx.geometry.Orientation.VERTICAL),
                iconButton(mail.isStarred() ? Icons.STAR : Icons.STAR_OUTLINE,
                        mail.isStarred() ? "Bỏ gắn sao (S)" : "Gắn sao (S)", () -> actions.toggleStar(mail)));
        if (!inTrash) {
            toolbar.getChildren().addAll(
                    iconButton(Icons.UNREAD, "Đánh dấu chưa đọc (U)", () -> actions.markUnread(mail)),
                    iconButton(Icons.TRASH, "Chuyển vào Thùng rác (Delete)", () -> actions.trash(mail)));
        } else {
            toolbar.getChildren().addAll(
                    iconButton(Icons.RESTORE, "Khôi phục", () -> actions.restore(mail)),
                    iconButton(Icons.DELETE_FOREVER, "Xoá vĩnh viễn", () -> actions.deleteForever(mail)));
        }
        toolbar.getChildren().add(spacer);
        if (mail.isStarred()) {
            ((Button) toolbar.getChildren().get(4)).getStyleClass().add("starred");
        }

        Label subject = new Label(mail.getSubject() == null || mail.getSubject().isBlank()
                ? "(Không có tiêu đề)" : mail.getSubject());
        subject.getStyleClass().add("reader-subject");
        subject.setWrapText(true);

        String senderName = mail.getSenderName() == null || mail.getSenderName().isBlank()
                ? mail.getSenderEmail() : mail.getSenderName();
        Avatar avatar = new Avatar(40);
        avatar.set(senderName, mail.getSenderEmail());
        Label sender = new Label(senderName);
        sender.getStyleClass().add("reader-sender");
        Label senderEmail = new Label("<" + mail.getSenderEmail() + ">");
        senderEmail.getStyleClass().add("reader-meta");
        HBox senderLine = new HBox(6, sender, senderEmail);
        senderLine.setAlignment(Pos.BASELINE_LEFT);
        Label recipients = new Label(recipientsText(mail, myEmail));
        recipients.getStyleClass().add("reader-meta");
        recipients.setWrapText(true);
        Label date = new Label(Formats.longDate(mail.getSentAt()));
        date.getStyleClass().add("reader-meta");
        date.setMinWidth(Region.USE_PREF_SIZE);
        VBox who = new VBox(3, senderLine, recipients);
        HBox.setHgrow(who, Priority.ALWAYS);
        HBox senderRow = new HBox(12, avatar, who, date);
        senderRow.setAlignment(Pos.TOP_LEFT);

        TextArea body = new TextArea(mail.getBody() == null ? "" : mail.getBody());
        body.setEditable(false);
        body.setWrapText(true);
        body.getStyleClass().add("reader-body");
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox content = new VBox(16, subject, senderRow, body);
        content.setPadding(new Insets(18, 24, 12, 24));
        VBox.setVgrow(content, Priority.ALWAYS);

        VBox layout = new VBox(toolbar, content);
        List<Attachment> attachments = mail.getAttachments();
        if (attachments != null && !attachments.isEmpty()) {
            Label title = new Label(attachments.size() + " tệp đính kèm");
            title.getStyleClass().add("field-label");
            FlowPane chips = new FlowPane(10, 10);
            for (Attachment attachment : attachments) {
                chips.getChildren().add(attachmentChip(attachment));
            }
            VBox attachmentBox = new VBox(8, new Separator(), title, chips);
            attachmentBox.setPadding(new Insets(0, 24, 18, 24));
            layout.getChildren().add(attachmentBox);
        }
        getChildren().setAll(layout);
    }

    /** "tới: tôi, b@x · cc: c@x · bcc: tôi" (BCC chỉ có khi Server cho phép xem). */
    private static String recipientsText(MailDetail mail, String myEmail) {
        StringBuilder text = new StringBuilder("tới: ").append(names(mail.getTo(), myEmail));
        if (mail.getCc() != null && !mail.getCc().isEmpty()) {
            text.append("  ·  cc: ").append(names(mail.getCc(), myEmail));
        }
        if (mail.getBcc() != null && !mail.getBcc().isEmpty()) {
            text.append("  ·  bcc: ").append(names(mail.getBcc(), myEmail));
        }
        return text.toString();
    }

    private static String names(List<String> emails, String myEmail) {
        if (emails == null || emails.isEmpty()) {
            return "—";
        }
        return String.join(", ", emails.stream()
                .map(email -> email.equalsIgnoreCase(myEmail) ? "tôi" : email).toList());
    }

    private Node attachmentChip(Attachment attachment) {
        Label name = new Label(attachment.getFilename());
        name.getStyleClass().add("attachment-name");
        name.setMaxWidth(200);
        Label size = new Label(Formats.size(attachment.getSizeBytes()));
        size.getStyleClass().add("hint");
        ProgressBar progress = new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.setManaged(false);
        VBox text = new VBox(2, name, size, progress);
        Button download = iconButton(Icons.DOWNLOAD, "Tải xuống và mở", null);
        HBox chip = new HBox(10, Icons.of(Icons.forFile(attachment.getFilename()), 28), text, download);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.getStyleClass().add("attachment-chip");
        chip.setMinWidth(220);
        Runnable start = () -> download(attachment, chip, progress, size);
        download.setOnAction(event -> start.run());
        chip.setOnMouseClicked(event -> start.run());
        return chip;
    }

    private void download(Attachment attachment, HBox chip, ProgressBar progress, Label size) {
        if (chip.isDisabled()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Lưu tệp đính kèm");
        String filename = safeFileName(attachment.getFilename());
        chooser.setInitialFileName(filename);
        Path downloads = Path.of(System.getProperty("user.home"), "Downloads");
        if (Files.isDirectory(downloads)) {
            chooser.setInitialDirectory(downloads.toFile());
        }
        File target = chooser.showSaveDialog(getScene() == null ? null : getScene().getWindow());
        if (target == null) {
            return;
        }
        chip.setDisable(true);
        progress.setVisible(true);
        progress.setManaged(true);
        long total = Math.max(1, attachment.getSizeBytes());
        CompletableFuture<Path> future = AttachmentTransfer.download(attachment, withExtension(target.toPath(), filename),
                received -> javafx.application.Platform.runLater(() -> {
                    progress.setProgress(received / (double) total);
                    size.setText(Formats.size(received) + " / " + Formats.size(attachment.getSizeBytes()));
                }));
        Fx.then(future, path -> {
            chip.setDisable(false);
            progress.setVisible(false);
            progress.setManaged(false);
            size.setText(Formats.size(attachment.getSizeBytes()));
            Toast.success(toastRoot, "Đã tải xong " + path.getFileName());
            openWithSystem(path);
        }, error -> {
            chip.setDisable(false);
            progress.setVisible(false);
            progress.setManaged(false);
            size.setText(Formats.size(attachment.getSizeBytes()));
            Toast.error(toastRoot, "Không tải được tệp: " + error);
        });
    }

    private void openWithSystem(Path path) {
        CompletableFuture.runAsync(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(path.toFile());
                }
            } catch (Exception ignored) {
                // Không có ứng dụng mở được loại tệp này: tệp vẫn đã được lưu.
            }
        });
    }

    private static String safeFileName(String filename) {
        String name = filename == null ? "tep-dinh-kem" : filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[<>:\"|?*\\p{Cntrl}]", "_");
        return name.isBlank() ? "tep-dinh-kem" : name;
    }

    /** Giữ đúng phần mở rộng gốc kể cả khi người dùng xoá nó trong hộp thoại lưu. */
    private static Path withExtension(Path selected, String original) {
        int dot = original.lastIndexOf('.');
        if (dot < 0) {
            return selected;
        }
        String extension = original.substring(dot).toLowerCase(Locale.ROOT);
        String name = selected.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(extension) ? selected : selected.resolveSibling(name + extension);
    }

    // ------------------------------------------------------------------ thư nháp

    public void showDraft(Draft draft) {
        Label subject = new Label(draft.getSubject() == null || draft.getSubject().isBlank()
                ? "(Không có tiêu đề)" : draft.getSubject());
        subject.getStyleClass().add("reader-subject");
        subject.setWrapText(true);
        Label tag = new Label("Thư nháp · lưu lúc " + Formats.longDate(draft.getUpdatedAt()));
        tag.getStyleClass().add("draft-tag");
        Label to = new Label("Tới: " + blankAsDash(draft.getTo())
                + (draft.getCc() == null || draft.getCc().isBlank() ? "" : "   ·   Cc: " + draft.getCc())
                + (draft.getBcc() == null || draft.getBcc().isBlank() ? "" : "   ·   Bcc: " + draft.getBcc()));
        to.getStyleClass().add("reader-meta");
        to.setWrapText(true);
        TextArea body = new TextArea(draft.getBody() == null ? "" : draft.getBody());
        body.setEditable(false);
        body.setWrapText(true);
        body.getStyleClass().add("reader-body");
        VBox.setVgrow(body, Priority.ALWAYS);

        Button edit = new Button("Tiếp tục soạn", Icons.of(Icons.COMPOSE, 16));
        edit.getStyleClass().add("primary-button");
        edit.setOnAction(event -> actions.editDraft(draft));
        Button delete = new Button("Xoá nháp", Icons.of(Icons.TRASH, 16));
        delete.getStyleClass().add("secondary-button");
        delete.setOnAction(event -> actions.deleteDraft(draft));
        HBox buttons = new HBox(10, edit, delete);

        VBox content = new VBox(14, tag, subject, to, body, buttons);
        content.setPadding(new Insets(22, 24, 20, 24));
        getChildren().setAll(content);
    }

    private static String blankAsDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static Button iconButton(String icon, String tooltip, Runnable action) {
        Button button = new Button();
        button.setGraphic(Icons.of(icon, 20));
        button.getStyleClass().add("icon-button");
        button.setTooltip(new Tooltip(tooltip));
        if (action != null) {
            button.setOnAction(event -> action.run());
        }
        return button;
    }
}
