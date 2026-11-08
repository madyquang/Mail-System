// Gói "ui": các thành phần giao diện tự viết (JavaFX).
package com.mailsystem.client.ui;

// Truyền tệp; DTO tệp đính kèm, bản nháp, chi tiết thư; các lớp JavaFX: bố cục, nút, nhãn, thanh tiến độ, hộp chọn tệp.
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

// Desktop (mở tệp bằng ứng dụng mặc định), tệp, danh sách, Locale, CompletableFuture.
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
        // Trả lời / trả lời tất cả / chuyển tiếp / gắn sao / đánh dấu chưa đọc / xoá / khôi phục / xoá vĩnh viễn;
        // sửa và xoá bản nháp.
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

    // Nơi xử lý thao tác (MainController); vùng gốc để hiện toast.
    private final Actions actions;
    private final StackPane toastRoot;

    /** Tạo khung đọc; ban đầu ở trạng thái trống. */
    public MailReader(Actions actions, StackPane toastRoot) {
        this.actions = actions;
        this.toastRoot = toastRoot;
        getStyleClass().add("reader");
        showEmpty();
    }

    /** Trạng thái trống: "Chọn một thư để đọc". */
    public void showEmpty() {
        // Đánh dấu nội dung đã đổi (huỷ vòng quay đang chờ), đổi nội dung có hiệu ứng.
        contentChanged();
        Motion.swap(this, new EmptyState().show(Icons.MAIL, "Chọn một thư để đọc",
                "Nội dung thư sẽ hiển thị ở đây. Mẹo: Ctrl/Shift + click để chọn nhiều thư."));
    }

    /**
     * Mở thư thường chỉ mất vài chục ms: hiện vòng quay ngay sẽ làm khung đọc nháy
     * (nội dung → vòng quay → nội dung). Chỉ hiện nếu sau 200 ms thư vẫn chưa về.
     */
    public void showLoading() {
        // Lấy số phiên bản cho lần tải này.
        int token = ++contentVersion;
        // Chờ 200ms; nếu nội dung chưa đổi thì mới hiện vòng quay 36x36.
        javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(200));
        delay.setOnFinished(event -> {
            if (token == contentVersion) {
                ProgressIndicator indicator = new ProgressIndicator();
                indicator.setMaxSize(36, 36);
                Motion.swap(this, indicator);
            }
        });
        delay.play();
    }

    /** Tăng mỗi khi nội dung khung đọc thay đổi; vòng quay đến trễ thì không ghi đè nội dung mới. */
    private int contentVersion;

    // Tăng số phiên bản nội dung.
    private void contentChanged() {
        contentVersion++;
    }

    /** Trạng thái lỗi: icon lỗi + lý do. */
    public void showError(String message) {
        contentChanged();
        Motion.swap(this, new EmptyState().show(Icons.ERROR, "Không tải được thư", message));
    }

    /** Trạng thái nhiều thư đang chọn. */
    public void showSelection(int count) {
        contentChanged();
        Motion.swap(this, new EmptyState().show(Icons.SELECT_ALL, "Đã chọn " + count + " thư",
                "Dùng thanh thao tác phía trên danh sách để đánh dấu, gắn sao hoặc xoá các thư đã chọn."));
    }

    // ------------------------------------------------------------------ chi tiết thư

    /** Hiện chi tiết một thư; myEmail để hiện "tôi" thay cho email của mình. */
    public void showMail(MailDetail mail, String myEmail) {
        contentChanged();
        // Thư đang ở Thùng rác?
        boolean inTrash = "TRASH".equals(mail.getFolderType());

        // Thanh công cụ ngang, căn trái.
        HBox toolbar = new HBox(2);
        toolbar.getStyleClass().add("reader-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        // Khoảng trống co giãn ở cuối thanh.
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        // Các nút: Trả lời, Trả lời tất cả, Chuyển tiếp, vạch ngăn, Gắn sao / Bỏ gắn sao.
        toolbar.getChildren().addAll(
                iconButton(Icons.REPLY, "Trả lời (R)", () -> actions.reply(mail)),
                iconButton(Icons.REPLY_ALL, "Trả lời tất cả (A)", () -> actions.replyAll(mail)),
                iconButton(Icons.FORWARD, "Chuyển tiếp (F)", () -> actions.forward(mail)),
                new Separator(javafx.geometry.Orientation.VERTICAL),
                iconButton(mail.isStarred() ? Icons.STAR : Icons.STAR_OUTLINE,
                        mail.isStarred() ? "Bỏ gắn sao (S)" : "Gắn sao (S)", () -> actions.toggleStar(mail)));
        // Ngoài Thùng rác: Đánh dấu chưa đọc, Chuyển vào Thùng rác.
        if (!inTrash) {
            toolbar.getChildren().addAll(
                    iconButton(Icons.UNREAD, "Đánh dấu chưa đọc (U)", () -> actions.markUnread(mail)),
                    iconButton(Icons.TRASH, "Chuyển vào Thùng rác (Delete)", () -> actions.trash(mail)));
        } else {
            // Trong Thùng rác: Khôi phục, Xoá vĩnh viễn.
            toolbar.getChildren().addAll(
                    iconButton(Icons.RESTORE, "Khôi phục", () -> actions.restore(mail)),
                    iconButton(Icons.DELETE_FOREVER, "Xoá vĩnh viễn", () -> actions.deleteForever(mail)));
        }
        // Thêm khoảng trống.
        toolbar.getChildren().add(spacer);
        // Thư có sao: tô nút ngôi sao (phần tử thứ 5, chỉ số 4).
        if (mail.isStarred()) {
            ((Button) toolbar.getChildren().get(4)).getStyleClass().add("starred");
        }

        // Tiêu đề thư (trống thì "(Không có tiêu đề)"), tự xuống dòng.
        Label subject = new Label(mail.getSubject() == null || mail.getSubject().isBlank()
                ? "(Không có tiêu đề)" : mail.getSubject());
        subject.getStyleClass().add("reader-subject");
        subject.setWrapText(true);

        // Tên người gửi (trống thì email); ảnh đại diện 40px.
        String senderName = mail.getSenderName() == null || mail.getSenderName().isBlank()
                ? mail.getSenderEmail() : mail.getSenderName();
        Avatar avatar = new Avatar(40);
        avatar.set(senderName, mail.getSenderEmail());
        // Tên và <email> người gửi trên cùng một hàng.
        Label sender = new Label(senderName);
        sender.getStyleClass().add("reader-sender");
        Label senderEmail = new Label("<" + mail.getSenderEmail() + ">");
        senderEmail.getStyleClass().add("reader-meta");
        HBox senderLine = new HBox(6, sender, senderEmail);
        senderLine.setAlignment(Pos.BASELINE_LEFT);
        // Dòng người nhận (tới / cc / bcc).
        Label recipients = new Label(recipientsText(mail, myEmail));
        recipients.getStyleClass().add("reader-meta");
        recipients.setWrapText(true);
        // Ngày gửi đầy đủ (không bị co).
        Label date = new Label(Formats.longDate(mail.getSentAt()));
        date.getStyleClass().add("reader-meta");
        date.setMinWidth(Region.USE_PREF_SIZE);
        // Cột người gửi + người nhận (giãn); hàng: ảnh, cột, ngày.
        VBox who = new VBox(3, senderLine, recipients);
        HBox.setHgrow(who, Priority.ALWAYS);
        HBox senderRow = new HBox(12, avatar, who, date);
        senderRow.setAlignment(Pos.TOP_LEFT);

        // Nội dung thư: ô chữ chỉ đọc, tự xuống dòng, giãn theo chiều dọc.
        TextArea body = new TextArea(mail.getBody() == null ? "" : mail.getBody());
        body.setEditable(false);
        body.setWrapText(true);
        body.getStyleClass().add("reader-body");
        VBox.setVgrow(body, Priority.ALWAYS);

        // Cột nội dung: tiêu đề, hàng người gửi, (báo đã đọc nếu có), nội dung.
        VBox content = new VBox(16, subject, senderRow);
        Node receipts = receiptBox(mail);
        if (receipts != null) {
            content.getChildren().add(receipts);
        }
        content.getChildren().add(body);
        // Lề và giãn theo chiều dọc.
        content.setPadding(new Insets(18, 24, 12, 24));
        VBox.setVgrow(content, Priority.ALWAYS);

        // Bố cục: thanh công cụ + nội dung + (tệp đính kèm nếu có).
        VBox layout = new VBox(toolbar, content);
        Node attachmentBox = attachmentBox(mail.getAttachments());
        if (attachmentBox != null) {
            ((VBox) attachmentBox).setPadding(new Insets(0, 24, 18, 24));
            layout.getChildren().add(attachmentBox);
        }
        // Hiện bố cục có hiệu ứng.
        Motion.swap(this, layout);
    }

    /**
     * HỘI THOẠI: các thư cùng thread_id xếp từ cũ tới mới. Thư mới nhất mở sẵn,
     * thư cũ thu gọn (bấm để mở). Thanh công cụ thao tác trên thư mới nhất.
     */
    public void showThread(List<MailDetail> mails, String myEmail) {
        // Chỉ một thư: hiện như thư thường.
        if (mails.size() == 1) {
            showMail(mails.get(0), myEmail);
            return;
        }
        // Dựng khung của thư mới nhất để lấy lại thanh công cụ (các nút thao tác trên thư mới nhất).
        MailDetail latest = mails.get(mails.size() - 1);
        showMail(latest, myEmail);
        VBox layout = (VBox) getChildren().get(0);
        HBox toolbar = (HBox) layout.getChildren().get(0);

        // Tiêu đề hội thoại + nhãn số thư.
        Label subject = new Label(latest.getSubject() == null || latest.getSubject().isBlank()
                ? "(Không có tiêu đề)" : latest.getSubject());
        subject.getStyleClass().add("reader-subject");
        subject.setWrapText(true);
        Label count = new Label(mails.size() + " thư");
        count.getStyleClass().add("thread-badge");
        HBox title = new HBox(10, subject, count);
        title.setAlignment(Pos.CENTER_LEFT);

        // Cột các thư: tiêu đề, rồi từng thư (chỉ thư cuối mở sẵn).
        VBox messages = new VBox(10, title);
        messages.setPadding(new Insets(18, 20, 20, 20));
        for (int i = 0; i < mails.size(); i++) {
            messages.getChildren().add(threadMessage(mails.get(i), myEmail, i == mails.size() - 1));
        }
        // Khung cuộn; bố cục mới: thanh công cụ + khung cuộn.
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(messages);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        Motion.swap(this, new VBox(toolbar, scroll));
        // Cuộn tới thư mới nhất sau khi bố cục xong.
        javafx.application.Platform.runLater(() -> scroll.setVvalue(1.0));
    }

    // Thẻ một thư trong hội thoại; expanded = mở (đủ nội dung) hay thu gọn.
    private Node threadMessage(MailDetail mail, String myEmail, boolean expanded) {
        // Tên người gửi (trống thì email); ảnh 34px; thêm "(tôi)" nếu là mình.
        String senderName = mail.getSenderName() == null || mail.getSenderName().isBlank()
                ? mail.getSenderEmail() : mail.getSenderName();
        Avatar avatar = new Avatar(34);
        avatar.set(senderName, mail.getSenderEmail());
        Label sender = new Label(senderName.equalsIgnoreCase(myEmail) || mail.getSenderEmail().equalsIgnoreCase(myEmail)
                ? senderName + " (tôi)" : senderName);
        sender.getStyleClass().add("reader-sender");
        // Ngày gửi.
        Label date = new Label(Formats.longDate(mail.getSentAt()));
        date.getStyleClass().add("reader-meta");
        date.setMinWidth(Region.USE_PREF_SIZE);
        // Một dòng trích nội dung (khi thu gọn), được co nhỏ.
        Label preview = new Label(oneLine(mail.getBody()));
        preview.getStyleClass().add("reader-meta");
        preview.setMinWidth(0);
        preview.setPrefWidth(1);
        preview.setMaxWidth(Double.MAX_VALUE);
        // Dòng người nhận (khi mở).
        Label recipients = new Label(recipientsText(mail, myEmail));
        recipients.getStyleClass().add("reader-meta");
        recipients.setWrapText(true);
        // Cột: tên + (người nhận nếu mở, trích đoạn nếu thu gọn); hàng đầu thẻ: ảnh, cột, ngày.
        VBox who = new VBox(2, sender, expanded ? recipients : preview);
        HBox.setHgrow(who, Priority.ALWAYS);
        who.setMinWidth(0);
        HBox header = new HBox(10, avatar, who, date);
        header.setAlignment(Pos.TOP_LEFT);

        // Thẻ.
        VBox card = new VBox(10, header);
        card.getStyleClass().add("thread-message");
        // Thu gọn: bấm vào thì thay bằng thẻ mở ở cùng vị trí.
        if (!expanded) {
            card.getStyleClass().add("collapsed");
            card.setOnMouseClicked(event -> {
                VBox parent = (VBox) card.getParent();
                int index = parent.getChildren().indexOf(card);
                parent.getChildren().set(index, threadMessage(mail, myEmail, true));
            });
            return card;
        }
        // Mở: thêm nội dung, báo đã đọc, tệp đính kèm, các nút trả lời / chuyển tiếp.
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
        // Gộp khoảng trắng thành một dấu cách; dài quá 140 ký tự thì cắt và thêm "…".
        String text = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return text.length() > 140 ? text.substring(0, 140) + "…" : text;
    }

    /**
     * BÁO ĐÃ ĐỌC (chỉ người gửi thấy): ai đã mở thư và lúc nào. Người nhận tắt
     * "Gửi xác nhận đã đọc" trong cài đặt thì không xuất hiện ở đây.
     */
    private static Node receiptBox(MailDetail mail) {
        // null: không phải thư mình gửi (Server không gửi danh sách) -> không hiện.
        List<com.mailsystem.common.model.ReadReceipt> receipts = mail.getReadReceipts();
        if (receipts == null) {
            return null;
        }
        String text;
        // Danh sách rỗng: chưa ai mở.
        if (receipts.isEmpty()) {
            text = "Chưa có người nhận nào mở thư (hoặc họ đã tắt xác nhận đã đọc).";
        } else {
            // Có: "Đã xem: tên (ngày giờ), ...".
            text = "Đã xem: " + String.join(", ", receipts.stream().map(receipt -> {
                String name = receipt.getDisplayName() == null || receipt.getDisplayName().isBlank()
                        ? receipt.getEmail() : receipt.getDisplayName();
                return name + " (" + Formats.shortDate(receipt.getReadAt()) + ")";
            }).toList());
        }
        // Hàng: icon + chữ.
        Label label = new Label(text);
        label.setWrapText(true);
        HBox box = new HBox(8, Icons.of(Icons.READ_RECEIPT, 16), label);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("receipt-box");
        return box;
    }

    private Node attachmentBox(List<Attachment> attachments) {
        // Không có tệp: không hiện.
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        // Tiêu đề "N tệp đính kèm"; các thẻ tệp xếp tràn dòng.
        Label title = new Label(attachments.size() + " tệp đính kèm");
        title.getStyleClass().add("field-label");
        FlowPane chips = new FlowPane(10, 10);
        for (Attachment attachment : attachments) {
            chips.getChildren().add(attachmentChip(attachment));
        }
        // Khung: vạch ngăn, tiêu đề, các thẻ.
        return new VBox(8, new Separator(), title, chips);
    }

    private static Button textButton(String icon, String text, Runnable action) {
        // Nút có icon và chữ.
        Button button = new Button(text, Icons.of(icon, 16));
        button.getStyleClass().add("text-button");
        button.setOnAction(event -> action.run());
        return button;
    }

    /** "tới: tôi, b@x · cc: c@x · bcc: tôi" (BCC chỉ có khi Server cho phép xem). */
    private static String recipientsText(MailDetail mail, String myEmail) {
        // "tới: ...", thêm "cc: ..." và "bcc: ..." nếu có.
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
        // Không có: "—"; có: nối bằng dấu phẩy, email của mình thành "tôi".
        if (emails == null || emails.isEmpty()) {
            return "—";
        }
        return String.join(", ", emails.stream()
                .map(email -> email.equalsIgnoreCase(myEmail) ? "tôi" : email).toList());
    }

    private Node attachmentChip(Attachment attachment) {
        // Nhãn tên tệp (rộng tối đa 200px) và kích thước.
        Label name = new Label(attachment.getFilename());
        name.getStyleClass().add("attachment-name");
        name.setMaxWidth(200);
        Label size = new Label(Formats.size(attachment.getSizeBytes()));
        size.getStyleClass().add("hint");
        // Thanh tiến độ (ẩn tới khi tải).
        ProgressBar progress = new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.setManaged(false);
        // Cột chữ; nút tải xuống.
        VBox text = new VBox(2, name, size, progress);
        Button download = iconButton(Icons.DOWNLOAD, "Tải xuống và mở", null);
        // Thẻ: icon theo loại tệp + cột chữ.
        HBox chip = new HBox(10, Icons.of(Icons.forFile(attachment.getFilename()), 28), text);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.getStyleClass().add("attachment-chip");
        chip.setMinWidth(220);
        // Việc lưu tệp; nút tải xuống gọi việc này.
        Runnable save = () -> download(attachment, chip, progress, size);
        download.setOnAction(event -> save.run());
        // Xem trước được: thêm nút Xem trước; bấm thẻ = xem trước.
        boolean previewable = AttachmentPreview.supports(attachment.getFilename());
        if (previewable) {
            Button preview = iconButton(Icons.PREVIEW, "Xem trước", null);
            Runnable open = () -> preview(attachment, chip, progress, size, save);
            preview.setOnAction(event -> open.run());
            chip.getChildren().add(preview);
            chip.setOnMouseClicked(event -> open.run());
        } else {
            // Không xem trước được: bấm thẻ = lưu tệp.
            chip.setOnMouseClicked(event -> save.run());
        }
        // Nút tải xuống ở cuối thẻ.
        chip.getChildren().add(download);
        return chip;
    }

    /** Tải tệp vào thư mục tạm (qua giao thức chia khối) rồi mở cửa sổ xem trước. */
    private void preview(Attachment attachment, HBox chip, ProgressBar progress, Label size, Runnable save) {
        // Đang tải (thẻ bị khoá): bỏ qua.
        if (chip.isDisabled()) {
            return;
        }
        // Đường dẫn tệp tạm; không tạo được thì báo lỗi.
        Path target;
        try {
            target = AttachmentPreview.cacheFile(attachment.getAttachmentId(), attachment.getFilename());
        } catch (java.io.IOException error) {
            Toast.error(toastRoot, "Không tạo được tệp tạm: " + error.getMessage());
            return;
        }
        // Việc mở tệp tạm bằng ứng dụng mặc định.
        Runnable openExternal = () -> openWithSystem(target);
        try {
            // Đã có bản tải đủ: mở xem trước ngay.
            if (Files.exists(target) && Files.size(target) == attachment.getSizeBytes()) {
                AttachmentPreview.show(attachment.getFilename(), target, save, openExternal);
                return;
            }
        } catch (java.io.IOException ignored) {
        }
        // Khoá thẻ, hiện thanh tiến độ.
        chip.setDisable(true);
        progress.setVisible(true);
        progress.setManaged(true);
        // Tổng byte (tối thiểu 1 để không chia cho 0).
        long total = Math.max(1, attachment.getSizeBytes());
        // Tải theo khối, cập nhật tiến độ trên luồng giao diện; xong thì mở xem trước, lỗi thì báo.
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
        // Đang tải: bỏ qua.
        if (chip.isDisabled()) {
            return;
        }
        // Hộp lưu tệp: tên gợi ý an toàn, mở sẵn thư mục Downloads nếu có.
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Lưu tệp đính kèm");
        String filename = safeFileName(attachment.getFilename());
        chooser.setInitialFileName(filename);
        Path downloads = Path.of(System.getProperty("user.home"), "Downloads");
        if (Files.isDirectory(downloads)) {
            chooser.setInitialDirectory(downloads.toFile());
        }
        // Hiện hộp; huỷ thì thôi.
        File target = chooser.showSaveDialog(getScene() == null ? null : getScene().getWindow());
        if (target == null) {
            return;
        }
        // Khoá thẻ, hiện tiến độ.
        chip.setDisable(true);
        progress.setVisible(true);
        progress.setManaged(true);
        long total = Math.max(1, attachment.getSizeBytes());
        // Tải về đúng đuôi tệp gốc; tiến độ: thanh và chữ "đã tải / tổng".
        CompletableFuture<Path> future = AttachmentTransfer.download(attachment, withExtension(target.toPath(), filename),
                received -> javafx.application.Platform.runLater(() -> {
                    progress.setProgress(received / (double) total);
                    size.setText(Formats.size(received) + " / " + Formats.size(attachment.getSizeBytes()));
                }));
        // Xong: mở khoá, ẩn tiến độ, báo, mở tệp. Lỗi: mở khoá, ẩn tiến độ, báo lỗi.
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
        // Mở bằng ứng dụng mặc định trên thread nền (nếu hệ điều hành hỗ trợ).
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
        // Chỉ giữ tên (bỏ đường dẫn), thay ký tự cấm trên Windows và ký tự điều khiển bằng '_'.
        String name = filename == null ? "tep-dinh-kem" : filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[<>:\"|?*\\p{Cntrl}]", "_");
        return name.isBlank() ? "tep-dinh-kem" : name;
    }

    /** Giữ đúng phần mở rộng gốc kể cả khi người dùng xoá nó trong hộp thoại lưu. */
    private static Path withExtension(Path selected, String original) {
        // Tên gốc không có đuôi: giữ nguyên.
        int dot = original.lastIndexOf('.');
        if (dot < 0) {
            return selected;
        }
        // Đuôi gốc (chữ thường); tên đã chọn chưa có đuôi đó thì thêm vào.
        String extension = original.substring(dot).toLowerCase(Locale.ROOT);
        String name = selected.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(extension) ? selected : selected.resolveSibling(name + extension);
    }

    // ------------------------------------------------------------------ thư nháp

    /** Xem trước bản nháp (chỉ đọc) kèm nút Tiếp tục soạn / Xoá nháp. */
    public void showDraft(Draft draft) {
        contentChanged();
        // Tiêu đề, nhãn "Thư nháp · lưu lúc ...".
        Label subject = new Label(draft.getSubject() == null || draft.getSubject().isBlank()
                ? "(Không có tiêu đề)" : draft.getSubject());
        subject.getStyleClass().add("reader-subject");
        subject.setWrapText(true);
        Label tag = new Label("Thư nháp · lưu lúc " + Formats.longDate(draft.getUpdatedAt()));
        tag.getStyleClass().add("draft-tag");
        // Dòng người nhận: Tới, thêm Cc / Bcc nếu có.
        Label to = new Label("Tới: " + blankAsDash(draft.getTo())
                + (draft.getCc() == null || draft.getCc().isBlank() ? "" : "   ·   Cc: " + draft.getCc())
                + (draft.getBcc() == null || draft.getBcc().isBlank() ? "" : "   ·   Bcc: " + draft.getBcc()));
        to.getStyleClass().add("reader-meta");
        to.setWrapText(true);
        // Nội dung chỉ đọc.
        TextArea body = new TextArea(draft.getBody() == null ? "" : draft.getBody());
        body.setEditable(false);
        body.setWrapText(true);
        body.getStyleClass().add("reader-body");
        VBox.setVgrow(body, Priority.ALWAYS);

        // Hai nút: Tiếp tục soạn, Xoá nháp.
        Button edit = new Button("Tiếp tục soạn", Icons.of(Icons.COMPOSE, 16));
        edit.getStyleClass().add("primary-button");
        edit.setOnAction(event -> actions.editDraft(draft));
        Button delete = new Button("Xoá nháp", Icons.of(Icons.TRASH, 16));
        delete.getStyleClass().add("secondary-button");
        delete.setOnAction(event -> actions.deleteDraft(draft));
        HBox buttons = new HBox(10, edit, delete);

        // Cột nội dung; hiện có hiệu ứng.
        VBox content = new VBox(14, tag, subject, to, body, buttons);
        content.setPadding(new Insets(22, 24, 20, 24));
        Motion.swap(this, content);
    }

    // Trống thì "—".
    private static String blankAsDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    // Nút chỉ có icon 20px, có chú thích; gắn việc làm nếu có.
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
