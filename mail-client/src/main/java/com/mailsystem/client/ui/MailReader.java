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
        contentChanged();
        getChildren().setAll(new EmptyState().show(Icons.MAIL, "Chọn một thư để đọc",
                "Nội dung thư sẽ hiển thị ở đây. Mẹo: Ctrl/Shift + click để chọn nhiều thư."));
    }

    /**
     * Mở thư thường chỉ mất vài chục ms: hiện vòng quay ngay sẽ làm khung đọc nháy
     * (nội dung → vòng quay → nội dung). Chỉ hiện nếu sau 200 ms thư vẫn chưa về.
     */
    public void showLoading() {
        int token = ++contentVersion;
        javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(200));
        delay.setOnFinished(event -> {
            if (token == contentVersion) {
                ProgressIndicator indicator = new ProgressIndicator();
                indicator.setMaxSize(36, 36);
                getChildren().setAll(indicator);
            }
        });
        delay.play();
    }

    /** Tăng mỗi khi nội dung khung đọc thay đổi; vòng quay đến trễ thì không ghi đè nội dung mới. */
    private int contentVersion;

    private void contentChanged() {
        contentVersion++;
    }

    public void showError(String message) {
        contentChanged();
        getChildren().setAll(new EmptyState().show(Icons.ERROR, "Không tải được thư", message));
    }

    public void showSelection(int count) {
        contentChanged();
        getChildren().setAll(new EmptyState().show(Icons.SELECT_ALL, "Đã chọn " + count + " thư",
                "Dùng thanh thao tác phía trên danh sách để đánh dấu, gắn sao hoặc xoá các thư đã chọn."));
    }

    // ------------------------------------------------------------------ chi tiết thư

    public void showMail(MailDetail mail, String myEmail) {
        contentChanged();
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

        VBox content = new VBox(16, subject, senderRow);
        Node receipts = receiptBox(mail);
        if (receipts != null) {
            content.getChildren().add(receipts);
        }
        content.getChildren().add(body);
        content.setPadding(new Insets(18, 24, 12, 24));
        VBox.setVgrow(content, Priority.ALWAYS);

        VBox layout = new VBox(toolbar, content);
        Node attachmentBox = attachmentBox(mail.getAttachments());
        if (attachmentBox != null) {
            ((VBox) attachmentBox).setPadding(new Insets(0, 24, 18, 24));
            layout.getChildren().add(attachmentBox);
        }
        getChildren().setAll(layout);
    }

    /**
     * HỘI THOẠI: các thư cùng thread_id xếp từ cũ tới mới. Thư mới nhất mở sẵn,
     * thư cũ thu gọn (bấm để mở). Thanh công cụ thao tác trên thư mới nhất.
     */
    public void showThread(List<MailDetail> mails, String myEmail) {
        if (mails.size() == 1) {
            showMail(mails.get(0), myEmail);
            return;
        }
        MailDetail latest = mails.get(mails.size() - 1);
        showMail(latest, myEmail);
        VBox layout = (VBox) getChildren().get(0);
        HBox toolbar = (HBox) layout.getChildren().get(0);

        Label subject = new Label(latest.getSubject() == null || latest.getSubject().isBlank()
                ? "(Không có tiêu đề)" : latest.getSubject());
        subject.getStyleClass().add("reader-subject");
        subject.setWrapText(true);
        Label count = new Label(mails.size() + " thư");
        count.getStyleClass().add("thread-badge");
        HBox title = new HBox(10, subject, count);
        title.setAlignment(Pos.CENTER_LEFT);

        VBox messages = new VBox(10, title);
        messages.setPadding(new Insets(18, 20, 20, 20));
        for (int i = 0; i < mails.size(); i++) {
            messages.getChildren().add(threadMessage(mails.get(i), myEmail, i == mails.size() - 1));
        }
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(messages);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().setAll(new VBox(toolbar, scroll));
        // Cuộn tới thư mới nhất sau khi bố cục xong.
        javafx.application.Platform.runLater(() -> scroll.setVvalue(1.0));
    }

    private Node threadMessage(MailDetail mail, String myEmail, boolean expanded) {
        String senderName = mail.getSenderName() == null || mail.getSenderName().isBlank()
                ? mail.getSenderEmail() : mail.getSenderName();
        Avatar avatar = new Avatar(34);
        avatar.set(senderName, mail.getSenderEmail());
        Label sender = new Label(senderName.equalsIgnoreCase(myEmail) || mail.getSenderEmail().equalsIgnoreCase(myEmail)
                ? senderName + " (tôi)" : senderName);
        sender.getStyleClass().add("reader-sender");
        Label date = new Label(Formats.longDate(mail.getSentAt()));
        date.getStyleClass().add("reader-meta");
        date.setMinWidth(Region.USE_PREF_SIZE);
        Label preview = new Label(oneLine(mail.getBody()));
        preview.getStyleClass().add("reader-meta");
        preview.setMinWidth(0);
        preview.setPrefWidth(1);
        preview.setMaxWidth(Double.MAX_VALUE);
        Label recipients = new Label(recipientsText(mail, myEmail));
        recipients.getStyleClass().add("reader-meta");
        recipients.setWrapText(true);
        VBox who = new VBox(2, sender, expanded ? recipients : preview);
        HBox.setHgrow(who, Priority.ALWAYS);
        who.setMinWidth(0);
        HBox header = new HBox(10, avatar, who, date);
        header.setAlignment(Pos.TOP_LEFT);

        VBox card = new VBox(10, header);
        card.getStyleClass().add("thread-message");
        if (!expanded) {
            card.getStyleClass().add("collapsed");
            card.setOnMouseClicked(event -> {
                VBox parent = (VBox) card.getParent();
                int index = parent.getChildren().indexOf(card);
                parent.getChildren().set(index, threadMessage(mail, myEmail, true));
            });
            return card;
        }
        Label body = new Label(mail.getBody() == null ? "" : mail.getBody());
        body.setWrapText(true);
        body.getStyleClass().add("message-body");
        body.setMaxWidth(Double.MAX_VALUE);
        card.getChildren().add(body);
        Node receipts = receiptBox(mail);
        if (receipts != null) {
            card.getChildren().add(receipts);
        }
        Node attachments = attachmentBox(mail.getAttachments());
        if (attachments != null) {
            card.getChildren().add(attachments);
        }
        HBox actionsRow = new HBox(6,
                textButton(Icons.REPLY, "Trả lời", () -> actions.reply(mail)),
                textButton(Icons.REPLY_ALL, "Trả lời tất cả", () -> actions.replyAll(mail)),
                textButton(Icons.FORWARD, "Chuyển tiếp", () -> actions.forward(mail)));
        card.getChildren().add(actionsRow);
        return card;
    }

    private static String oneLine(String body) {
        String text = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return text.length() > 140 ? text.substring(0, 140) + "…" : text;
    }

    /**
     * BÁO ĐÃ ĐỌC (chỉ người gửi thấy): ai đã mở thư và lúc nào. Người nhận tắt
     * "Gửi xác nhận đã đọc" trong cài đặt thì không xuất hiện ở đây.
     */
    private static Node receiptBox(MailDetail mail) {
        List<com.mailsystem.common.model.ReadReceipt> receipts = mail.getReadReceipts();
        if (receipts == null) {
            return null;
        }
        String text;
        if (receipts.isEmpty()) {
            text = "Chưa có người nhận nào mở thư (hoặc họ đã tắt xác nhận đã đọc).";
        } else {
            text = "Đã xem: " + String.join(", ", receipts.stream().map(receipt -> {
                String name = receipt.getDisplayName() == null || receipt.getDisplayName().isBlank()
                        ? receipt.getEmail() : receipt.getDisplayName();
                return name + " (" + Formats.shortDate(receipt.getReadAt()) + ")";
            }).toList());
        }
        Label label = new Label(text);
        label.setWrapText(true);
        HBox box = new HBox(8, Icons.of(Icons.READ_RECEIPT, 16), label);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("receipt-box");
        return box;
    }

    private Node attachmentBox(List<Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        Label title = new Label(attachments.size() + " tệp đính kèm");
        title.getStyleClass().add("field-label");
        FlowPane chips = new FlowPane(10, 10);
        for (Attachment attachment : attachments) {
            chips.getChildren().add(attachmentChip(attachment));
        }
        return new VBox(8, new Separator(), title, chips);
    }

    private static Button textButton(String icon, String text, Runnable action) {
        Button button = new Button(text, Icons.of(icon, 16));
        button.getStyleClass().add("text-button");
        button.setOnAction(event -> action.run());
        return button;
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
        HBox chip = new HBox(10, Icons.of(Icons.forFile(attachment.getFilename()), 28), text);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.getStyleClass().add("attachment-chip");
        chip.setMinWidth(220);
        Runnable save = () -> download(attachment, chip, progress, size);
        download.setOnAction(event -> save.run());
        boolean previewable = AttachmentPreview.supports(attachment.getFilename());
        if (previewable) {
            Button preview = iconButton(Icons.PREVIEW, "Xem trước", null);
            Runnable open = () -> preview(attachment, chip, progress, size, save);
            preview.setOnAction(event -> open.run());
            chip.getChildren().add(preview);
            chip.setOnMouseClicked(event -> open.run());
        } else {
            chip.setOnMouseClicked(event -> save.run());
        }
        chip.getChildren().add(download);
        return chip;
    }

    /** Tải tệp vào thư mục tạm (qua giao thức chia khối) rồi mở cửa sổ xem trước. */
    private void preview(Attachment attachment, HBox chip, ProgressBar progress, Label size, Runnable save) {
        if (chip.isDisabled()) {
            return;
        }
        Path target;
        try {
            target = AttachmentPreview.cacheFile(attachment.getAttachmentId(), attachment.getFilename());
        } catch (java.io.IOException error) {
            Toast.error(toastRoot, "Không tạo được tệp tạm: " + error.getMessage());
            return;
        }
        Runnable openExternal = () -> openWithSystem(target);
        try {
            if (Files.exists(target) && Files.size(target) == attachment.getSizeBytes()) {
                AttachmentPreview.show(attachment.getFilename(), target, save, openExternal);
                return;
            }
        } catch (java.io.IOException ignored) {
        }
        chip.setDisable(true);
        progress.setVisible(true);
        progress.setManaged(true);
        long total = Math.max(1, attachment.getSizeBytes());
        Fx.then(AttachmentTransfer.download(attachment, target, received -> javafx.application.Platform.runLater(() ->
                progress.setProgress(received / (double) total))), path -> {
                    chip.setDisable(false);
                    progress.setVisible(false);
                    progress.setManaged(false);
                    AttachmentPreview.show(attachment.getFilename(), path, save, openExternal);
                }, error -> {
                    chip.setDisable(false);
                    progress.setVisible(false);
                    progress.setManaged(false);
                    size.setText(Formats.size(attachment.getSizeBytes()));
                    Toast.error(toastRoot, "Không tải được tệp: " + error);
                });
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
        contentChanged();
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
