// Gói "controller": xử lý sự kiện của từng màn hình (gắn với tệp .fxml).
package com.mailsystem.client.controller;

// Gọi Server, điều hướng, phiên, truyền tệp, các thành phần giao diện tự viết, DTO, giới hạn giao thức; Gson; JavaFX.
import com.mailsystem.client.api.MailApi;
import com.mailsystem.client.app.Navigator;
import com.mailsystem.client.app.Session;
import com.mailsystem.client.service.AttachmentTransfer;
import com.mailsystem.client.ui.ContactSuggestions;
import com.mailsystem.client.ui.Dialogs;
import com.mailsystem.client.ui.Formats;
import com.mailsystem.client.ui.Fx;
import com.mailsystem.client.ui.Icons;
import com.mailsystem.client.ui.Toast;
import com.mailsystem.common.model.Attachment;
import com.mailsystem.common.model.Draft;
import com.mailsystem.common.model.MailDetail;
import com.mailsystem.common.protocol.ProtocolConstants;
import com.google.gson.JsonObject;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

// Tệp, thời gian, định dạng giờ, tập hợp, Locale, gom luồng dữ liệu.
import java.io.File;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cửa sổ soạn thư (mỗi thư một cửa sổ, có thể mở nhiều cái cùng lúc). Dùng cho
 * thư mới, trả lời, trả lời tất cả, chuyển tiếp và tiếp tục thư nháp. Tự lưu
 * nháp định kỳ khi có thay đổi; đóng cửa sổ khi thư chưa gửi thì hỏi lưu nháp.
 */
public class ComposeController {

    // Chu kỳ tự lưu nháp: 20 giây.
    private static final Duration AUTOSAVE_INTERVAL = Duration.seconds(20);

    /**
     * Dữ liệu khởi tạo cửa sổ soạn thư. inReplyTo: mailId của thư đang trả lời
     * (để Server nối vào cùng hội thoại); files: tệp trên máy (khi hoàn tác gửi);
     * scheduledAt: thời điểm đã hẹn trước đó (sửa thư đã lên lịch), null nếu không có.
     */
    public record Request(String title, String to, String cc, String bcc, String subject, String body,
            Integer draftId, List<Attachment> forwardAttachments, Integer inReplyTo, List<File> files,
            LocalDateTime scheduledAt) {

        /** Constructor rút gọn: không có giờ hẹn trước. */
        public Request(String title, String to, String cc, String bcc, String subject, String body,
                Integer draftId, List<Attachment> forwardAttachments, Integer inReplyTo, List<File> files) {
            this(title, to, cc, bcc, subject, body, draftId, forwardAttachments, inReplyTo, files, null);
        }

        /** Thư mới trống. */
        public static Request blank() {
            return new Request("Thư mới", "", "", "", "", "", null, List.of(), null, List.of());
        }

        /** Tiếp tục soạn một bản nháp (giữ draftId để lưu đè). */
        public static Request draft(Draft draft) {
            return new Request("Thư nháp", draft.getTo(), draft.getCc(), draft.getBcc(), draft.getSubject(),
                    draft.getBody(), draft.getDraftId(), List.of(), null, List.of());
        }

        /** Mở lại thư vừa huỷ gửi (hoàn tác hoặc huỷ hẹn giờ) từ nội dung Server trả lại. */
        public static Request reopened(JsonObject data, List<Attachment> forwarded, List<File> files) {
            // Đọc sendAt (nếu có) thành LocalDateTime.
            LocalDateTime scheduledAt = null;
            try {
                scheduledAt = data.has("sendAt") ? LocalDateTime.parse(data.get("sendAt").getAsString()) : null;
            } catch (RuntimeException ignored) {
                // Server cũ không trả sendAt hoặc sai định dạng: hộp thoại dùng mặc định
            }
            // Tiêu đề cửa sổ theo trường hợp; người nhận nối từ mảng; tiêu đề, nội dung, draftId, inReplyTo nếu có.
            return new Request(scheduledAt == null ? "Thư chưa gửi" : "Sửa thư đã lên lịch",
                    joined(data, "to"), joined(data, "cc"), joined(data, "bcc"),
                    text(data, "subject"), text(data, "body"),
                    data.has("draftId") ? data.get("draftId").getAsInt() : null, forwarded,
                    data.has("inReplyTo") ? data.get("inReplyTo").getAsInt() : null, files, scheduledAt);
        }

        // Mảng chuỗi field -> "a, b, c" (không có hoặc không phải mảng thì rỗng).
        private static String joined(JsonObject data, String field) {
            if (!data.has(field) || !data.get(field).isJsonArray()) {
                return "";
            }
            List<String> values = new ArrayList<>();
            data.getAsJsonArray(field).forEach(value -> values.add(value.getAsString()));
            return String.join(", ", values);
        }

        // Trường chuỗi field (không có hoặc null thì rỗng).
        private static String text(JsonObject data, String field) {
            return data.has(field) && !data.get(field).isJsonNull() ? data.get(field).getAsString() : "";
        }

        /** Trả lời (all = false) hoặc trả lời tất cả (all = true). */
        public static Request reply(MailDetail mail, boolean all) {
            // Email của mình.
            String me = Session.account() == null ? "" : Session.account().getEmail();
            // Tới: người gửi (LinkedHashSet: giữ thứ tự, bỏ trùng).
            Set<String> to = new LinkedHashSet<>();
            to.add(mail.getSenderEmail());
            Set<String> cc = new LinkedHashSet<>();
            // Trả lời tất cả: thêm mọi người trong Tới và Cc của thư gốc.
            if (all) {
                if (mail.getTo() != null) {
                    to.addAll(mail.getTo());
                }
                if (mail.getCc() != null) {
                    cc.addAll(mail.getCc());
                }
            }
            // Không tự gửi cho mình (trừ khi chính mình là người gửi duy nhất).
            if (to.size() > 1 || !mail.getSenderEmail().equalsIgnoreCase(me)) {
                to.removeIf(email -> email.equalsIgnoreCase(me));
            }
            // Cc: bỏ mình và những người đã có trong Tới.
            cc.removeIf(email -> email.equalsIgnoreCase(me) || to.contains(email));
            // Tên người gửi (trống thì email).
            String sender = mail.getSenderName() == null || mail.getSenderName().isBlank()
                    ? mail.getSenderEmail() : mail.getSenderName();
            // Trích dẫn: "Vào <ngày>, <tên> <email> đã viết:" rồi nội dung gốc, mỗi dòng có "> ".
            String quote = "\n\nVào " + Formats.longDate(mail.getSentAt()) + ", " + sender + " <" + mail.getSenderEmail()
                    + "> đã viết:\n" + quoted(mail.getBody());
            // Tiêu đề thêm "Re: "; inReplyTo = mailId thư gốc.
            return new Request(all ? "Trả lời tất cả" : "Trả lời", String.join(", ", to), String.join(", ", cc), "",
                    prefixed("Re: ", mail.getSubject()), quote, null, List.of(), mail.getMailId(), List.of());
        }

        public static Request forward(MailDetail mail) {
            // Phần đầu thư chuyển tiếp: người gửi, ngày, tiêu đề, người nhận gốc, rồi nội dung gốc.
            String header = "\n\n---------- Thư được chuyển tiếp ----------\n"
                    + "Từ: " + nullToEmpty(mail.getSenderName()) + " <" + mail.getSenderEmail() + ">\n"
                    + "Ngày: " + Formats.longDate(mail.getSentAt()) + "\n"
                    + "Tiêu đề: " + nullToEmpty(mail.getSubject()) + "\n"
                    + "Tới: " + (mail.getTo() == null ? "" : String.join(", ", mail.getTo())) + "\n\n"
                    + nullToEmpty(mail.getBody());
            // Tiêu đề thêm "Fwd: "; mang theo tệp đính kèm của thư gốc.
            return new Request("Chuyển tiếp", "", "", "", prefixed("Fwd: ", mail.getSubject()), header, null,
                    mail.getAttachments() == null ? List.of() : mail.getAttachments(), null, List.of());
        }

        private static String prefixed(String prefix, String subject) {
            // Thêm tiền tố nếu tiêu đề chưa bắt đầu bằng nó (không phân biệt hoa thường).
            String value = subject == null ? "" : subject.trim();
            return value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT)) ? value : prefix + value;
        }

        private static String quoted(String body) {
            // Thêm "> " vào đầu mỗi dòng.
            return nullToEmpty(body).lines().map(line -> "> " + line).collect(Collectors.joining("\n"));
        }

        // null thành chuỗi rỗng.
        private static String nullToEmpty(String value) {
            return value == null ? "" : value;
        }
    }

    /** Mở một cửa sổ soạn thư mới. */
    public static void open(Request request) {
        // Cửa sổ 780x640, không modal; sau khi nạp FXML thì gọi init(request).
        Navigator.<ComposeController>openWindow(Navigator.COMPOSE, request.title() + " – Mail System", 780, 640,
                false, controller -> controller.init(request));
    }

    // Các thành phần giao diện; @FXML: JavaFX tự gán theo fx:id trong compose.fxml.
    @FXML
    private StackPane root;
    @FXML
    private Label titleLabel;
    @FXML
    private Label draftStatus;
    @FXML
    private TextField toField;
    @FXML
    private TextField ccField;
    @FXML
    private TextField bccField;
    @FXML
    private HBox ccRow;
    @FXML
    private HBox bccRow;
    @FXML
    private Hyperlink ccToggle;
    @FXML
    private TextField subjectField;
    @FXML
    private TextArea bodyArea;
    @FXML
    private FlowPane attachmentPane;
    @FXML
    private Button sendButton;
    @FXML
    private Button attachButton;
    @FXML
    private Button scheduleButton;
    @FXML
    private StackPane dropZone;
    @FXML
    private Button saveDraftButton;
    @FXML
    private Button discardButton;
    @FXML
    private HBox progressBox;
    @FXML
    private ProgressBar uploadProgress;
    @FXML
    private Label progressLabel;
    @FXML
    private Label errorLabel;

    // Tệp trên máy sẽ tải lên; tệp của thư gốc (chuyển tiếp); id bản nháp; thư được trả lời.
    private final List<File> files = new ArrayList<>();
    private final List<Attachment> forwarded = new ArrayList<>();
    private Integer draftId;
    private Integer inReplyTo;
    /** Thời điểm đã hẹn lần trước (sửa thư đã lên lịch): điền sẵn khi hẹn giờ lại. */
    private LocalDateTime previousSchedule;
    /** Tài khoản đã mở khung soạn: thư chỉ được gửi / lưu nháp dưới đúng tài khoản này. */
    private Integer ownerAccountId;
    // Bản nháp do chính cửa sổ này tạo; có thay đổi chưa lưu; đang gửi; bộ hẹn giờ tự lưu.
    private boolean draftCreatedHere;
    private boolean dirty;
    private boolean sending;
    private Timeline autosave;

    /** Điền dữ liệu ban đầu và cài đặt các xử lý (gọi từ open). */
    private void init(Request request) {
        // Ghi nhớ tài khoản đang đăng nhập.
        ownerAccountId = Session.account() == null ? null : Session.account().getAccountId();
        // Điền tiêu đề cửa sổ, người nhận, tiêu đề thư, nội dung.
        titleLabel.setText(request.title());
        toField.setText(request.to());
        ccField.setText(request.cc());
        bccField.setText(request.bcc());
        subjectField.setText(request.subject());
        bodyArea.setText(request.body());
        // Ghi nhớ bản nháp, thư được trả lời, giờ hẹn cũ.
        draftId = request.draftId();
        inReplyTo = request.inReplyTo();
        previousSchedule = request.scheduledAt();
        // Có giờ hẹn cũ: hiện ở dòng trạng thái.
        if (previousSchedule != null) {
            draftStatus.setText("Thư đã hẹn gửi lúc " + Formats.longDate(previousSchedule.toString())
                    + " · bấm biểu tượng lịch để hẹn lại");
        }
        // Tệp chuyển tiếp và tệp trên máy.
        forwarded.addAll(request.forwardAttachments());
        files.addAll(request.files());
        // Cho phép kéo thả tệp.
        installFileDrop();
        // Có Cc / Bcc thì mở hai ô đó.
        if (!request.cc().isBlank() || !request.bcc().isBlank()) {
            handleToggleCc();
        }
        // Vẽ các thẻ tệp.
        renderAttachments();
        // Gợi ý người nhận/nhóm khi gõ vài ký tự (↓↑ chọn, Enter/Tab dùng gợi ý).
        ContactSuggestions.forRecipients(toField);
        ContactSuggestions.forRecipients(ccField);
        ContactSuggestions.forRecipients(bccField);
        // Sửa bất kỳ ô nào: đánh dấu có thay đổi.
        for (TextField field : new TextField[] {toField, ccField, bccField, subjectField}) {
            field.textProperty().addListener((observable, old, value) -> dirty = true);
        }
        bodyArea.textProperty().addListener((observable, old, value) -> dirty = true);
        // Dữ liệu ban đầu không tính là thay đổi.
        dirty = false;

        // Sau khi cửa sổ hiện: đặt con trỏ (ô Tới nếu trống, không thì đầu nội dung).
        Platform.runLater(() -> {
            if (toField.getText().isBlank()) {
                toField.requestFocus();
            } else {
                bodyArea.requestFocus();
                bodyArea.positionCaret(0);
            }
            // Cửa sổ chứa khung soạn.
            Stage stage = (Stage) root.getScene().getWindow();
            // Phiên kết thúc: khung soạn đang có nội dung được giữ lại để không mất chữ đã gõ.
            stage.getProperties().put(Navigator.KEEP_ON_SIGN_OUT, (java.util.function.BooleanSupplier) this::hasContent);
            // Cửa sổ ẩn: dừng tự lưu.
            stage.setOnHidden(event -> {
                if (autosave != null) {
                    autosave.stop();
                }
            });
            // Bấm X: không đóng ngay mà xử lý như nút Bỏ (hỏi lưu nháp).
            stage.setOnCloseRequest(event -> {
                event.consume();
                handleDiscard();
            });
            // Phím tắt: Ctrl+Enter gửi, Ctrl+S lưu nháp.
            root.getScene().getAccelerators().put(
                    new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN), this::handleSend);
            root.getScene().getAccelerators().put(
                    new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN), this::handleSaveDraft);
        });

        // Tự lưu mỗi 20 giây nếu có thay đổi, không đang gửi, có nội dung và vẫn đúng tài khoản.
        autosave = new Timeline(new KeyFrame(AUTOSAVE_INTERVAL, event -> {
            if (dirty && !sending && hasContent() && sameAccount()) {
                saveDraft(false, null);
            }
        }));
        // Lặp mãi; bắt đầu chạy.
        autosave.setCycleCount(Timeline.INDEFINITE);
        autosave.play();
    }

    // ------------------------------------------------------------------ gửi

    /** Bấm "Gửi": gửi ngay. */
    @FXML
    public void handleSend() {
        send(null);
    }

    /** Hẹn giờ gửi: chọn thời điểm rồi gửi với sendAt (Server giữ thư trong hộp chờ gửi). */
    @FXML
    public void handleSchedule() {
        if (!sending) {
            // Không đang gửi: hiện hộp chọn giờ (điền sẵn giờ cũ); chọn xong thì gửi với giờ đó.
            Dialogs.pickSendTime(window(), previousSchedule).ifPresent(this::send);
        }
    }

    /** sendAt null: gửi ngay; khác null: hẹn giờ (Server giữ thư trong hộp chờ gửi). */
    private void send(LocalDateTime sendAt) {
        // Đang gửi: bỏ qua (tránh bấm hai lần).
        if (sending) {
            return;
        }
        // Đã đổi tài khoản / hết phiên: không gửi.
        if (!sameAccount()) {
            showError(wrongAccountMessage());
            return;
        }
        // Tách danh sách người nhận; phải có ít nhất một người.
        List<String> to = Formats.splitRecipients(toField.getText());
        List<String> cc = Formats.splitRecipients(ccField.getText());
        List<String> bcc = Formats.splitRecipients(bccField.getText());
        if (to.isEmpty() && cc.isEmpty() && bcc.isEmpty()) {
            showError("Thêm ít nhất một người nhận.");
            toField.requestFocus();
            return;
        }
        // Nội dung không quá MAX_BODY_CHARS.
        if (bodyArea.getText().length() > ProtocolConstants.MAX_BODY_CHARS) {
            showError("Nội dung thư quá dài (tối đa " + ProtocolConstants.MAX_BODY_CHARS + " ký tự).");
            return;
        }
        // Không có tiêu đề: hỏi lại.
        if (subjectField.getText().isBlank() && !Dialogs.confirm(window(), "Gửi thư không có tiêu đề?",
                "Thư này chưa có tiêu đề.", "Vẫn gửi", false)) {
            return;
        }
        // Mọi tệp phải còn tồn tại và không rỗng.
        for (File file : files) {
            if (!file.isFile() || file.length() == 0) {
                showError("Tệp không tồn tại hoặc rỗng: " + file.getName());
                return;
            }
        }

        // Khoá giao diện; tính tổng byte; có tệp thì hiện thanh tiến độ.
        setSending(true);
        long total = files.stream().mapToLong(File::length).sum();
        if (total > 0) {
            progressBox.setVisible(true);
            progressBox.setManaged(true);
        }
        // Bước 1: tải lần lượt các tệp lên, cập nhật tiến độ trên luồng giao diện.
        Fx.then(AttachmentTransfer.uploadAll(new ArrayList<>(files), sent -> Platform.runLater(() -> {
                    uploadProgress.setProgress(total == 0 ? 1 : sent / (double) total);
                    progressLabel.setText("Đang tải tệp lên " + Formats.size(sent) + " / " + Formats.size(total));
                }))
                // Bước 2: có uploadIds thì gửi SEND_MAIL (kèm tệp chuyển tiếp, draftId, inReplyTo, sendAt).
                .thenCompose(uploadIds -> {
                    Platform.runLater(() -> progressLabel.setText("Đang gửi thư..."));
                    return MailApi.get().send(new MailApi.OutgoingMail(to, cc, bcc, subjectField.getText(),
                            bodyArea.getText(), uploadIds,
                            forwarded.stream().map(Attachment::getAttachmentId).collect(Collectors.toList()), draftId,
                            inReplyTo, null, sendAt));
                }), result -> {
                    // Thành công: đóng cửa sổ, báo ở cửa sổ chính (hẹn giờ: kèm thời điểm).
                    close();
                    StackPane mainRoot = Navigator.primaryStage().getScene().getRoot() instanceof StackPane pane
                            ? pane : null;
                    if (result.queued()) {
                        Toast.success(mainRoot, "Đã hẹn gửi lúc " + Formats.longDate(result.sendAt())
                                + ". Xem trong mục Đã lên lịch.");
                    } else {
                        Toast.success(mainRoot, "Đã gửi thư.");
                    }
                }, error -> {
                    // Lỗi: mở khoá, ẩn tiến độ, hiện lỗi.
                    setSending(false);
                    progressBox.setVisible(false);
                    progressBox.setManaged(false);
                    showError(error);
                });
    }

    private void setSending(boolean value) {
        // Bật / tắt trạng thái đang gửi: đổi chữ nút Gửi, khoá các ô và nút, xoá lỗi cũ.
        sending = value;
        sendButton.setDisable(value);
        sendButton.setText(value ? "Đang gửi..." : "Gửi");
        for (javafx.scene.Node node : new javafx.scene.Node[] {toField, ccField, bccField, subjectField, bodyArea,
                attachButton, scheduleButton, saveDraftButton, discardButton, attachmentPane}) {
            node.setDisable(value);
        }
        errorLabel.setText("");
    }

    // ------------------------------------------------------------------ tệp đính kèm

    @FXML
    public void handleAttach() {
        // Hộp chọn nhiều tệp, chỉ hiện các loại được hỗ trợ.
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn tệp đính kèm");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Tệp được hỗ trợ (PDF, DOCX, PPTX, TXT, JPG, PNG, ZIP)",
                "*.pdf", "*.docx", "*.pptx", "*.txt", "*.jpg", "*.png", "*.zip"));
        // Chọn xong thì thêm.
        List<File> selected = chooser.showOpenMultipleDialog(window());
        if (selected != null) {
            addFiles(selected);
        }
    }

    /** Kéo tệp từ Explorer/Finder thả vào cửa sổ soạn thư. */
    private void installFileDrop() {
        // Kéo tệp vào (không đang gửi): chấp nhận sao chép, hiện vùng thả.
        root.setOnDragOver(event -> {
            if (!sending && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
                dropZone.setVisible(true);
            }
            event.consume();
        });
        // Kéo ra ngoài: ẩn vùng thả.
        root.setOnDragExited(event -> dropZone.setVisible(false));
        // Thả: thêm các tệp (bỏ thư mục), báo đã xử lý, ẩn vùng thả.
        root.setOnDragDropped(event -> {
            boolean done = false;
            if (!sending && event.getDragboard().hasFiles()) {
                addFiles(event.getDragboard().getFiles().stream().filter(File::isFile).toList());
                done = true;
            }
            dropZone.setVisible(false);
            event.setDropCompleted(done);
            event.consume();
        });
    }

    private void addFiles(List<File> selected) {
        // Danh sách thử: tệp hiện có + tệp mới (bỏ trùng); đuôi phải được phép.
        List<File> candidate = new ArrayList<>(files);
        for (File file : selected) {
            String name = file.getName();
            String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            if (!ProtocolConstants.ALLOWED_ATTACHMENT_EXTENSIONS.contains(extension)) {
                showError("Không hỗ trợ định dạng tệp: " + name);
                return;
            }
            if (!candidate.contains(file)) {
                candidate.add(file);
            }
        }
        // Không quá MAX_ATTACHMENTS_PER_MAIL tệp (kể cả tệp chuyển tiếp).
        if (candidate.size() + forwarded.size() > ProtocolConstants.MAX_ATTACHMENTS_PER_MAIL) {
            showError("Mỗi thư đính kèm tối đa " + ProtocolConstants.MAX_ATTACHMENTS_PER_MAIL + " tệp.");
            return;
        }
        // Tổng dung lượng không quá MAX_ATTACHMENT_TOTAL_BYTES.
        long total = candidate.stream().mapToLong(File::length).sum()
                + forwarded.stream().mapToLong(Attachment::getSizeBytes).sum();
        if (total > ProtocolConstants.MAX_ATTACHMENT_TOTAL_BYTES) {
            showError("Tổng dung lượng tệp không được vượt quá 25 MB (hiện tại " + Formats.size(total) + ").");
            return;
        }
        // Hợp lệ: thay danh sách tệp, xoá lỗi, vẽ lại.
        files.clear();
        files.addAll(candidate);
        errorLabel.setText("");
        renderAttachments();
    }

    private void renderAttachments() {
        // Vẽ lại: thẻ cho tệp chuyển tiếp và tệp trên máy; nút X trên thẻ thì bỏ tệp và vẽ lại.
        attachmentPane.getChildren().clear();
        for (Attachment attachment : forwarded) {
            attachmentPane.getChildren().add(chip(attachment.getFilename(), attachment.getSizeBytes(), () -> {
                forwarded.remove(attachment);
                renderAttachments();
            }));
        }
        for (File file : files) {
            attachmentPane.getChildren().add(chip(file.getName(), file.length(), () -> {
                files.remove(file);
                renderAttachments();
            }));
        }
    }

    private HBox chip(String name, long size, Runnable onRemove) {
        // Thẻ tệp: icon theo loại, "tên · kích thước", nút X nhỏ.
        Label label = new Label(name + "  ·  " + Formats.size(size));
        Button remove = new Button();
        remove.setGraphic(Icons.of(Icons.CLOSE, 14));
        remove.getStyleClass().add("icon-button");
        remove.setStyle("-fx-min-width: 26; -fx-min-height: 26; -fx-padding: 3;");
        remove.setOnAction(event -> onRemove.run());
        HBox chip = new HBox(6, Icons.of(Icons.forFile(name), 16), label, remove);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.getStyleClass().add("file-chip");
        return chip;
    }

    // ------------------------------------------------------------------ thư nháp

    /** Bấm "Lưu nháp" (hoặc Ctrl+S). */
    @FXML
    public void handleSaveDraft() {
        if (!sending) {
            saveDraft(true, null);
        }
    }

    /** Còn đăng nhập đúng tài khoản đã mở khung soạn này không (sau khi đăng xuất / đổi tài khoản). */
    private boolean sameAccount() {
        // Có tài khoản, có ghi nhớ, và trùng id.
        return Session.account() != null && ownerAccountId != null
                && Session.account().getAccountId() == ownerAccountId;
    }

    private static String wrongAccountMessage() {
        // Câu báo tuỳ theo đã đăng xuất hay đã đổi tài khoản.
        return Session.account() == null
                ? "Phiên đăng nhập đã kết thúc. Đăng nhập lại đúng tài khoản để gửi hoặc lưu thư này."
                : "Thư này được soạn từ tài khoản khác. Đăng nhập lại tài khoản đó để gửi, hoặc sao chép nội dung sang thư mới.";
    }

    // Lưu nháp; announce: lưu tay (báo thêm nếu có tệp); afterSave: việc làm sau khi lưu xong.
    private void saveDraft(boolean announce, Runnable afterSave) {
        // Sai tài khoản: không lưu.
        if (!sameAccount()) {
            showError(wrongAccountMessage());
            return;
        }
        // Dựng Draft từ các ô (draftId 0 = tạo mới).
        Draft draft = new Draft();
        draft.setDraftId(draftId == null ? 0 : draftId);
        draft.setTo(toField.getText());
        draft.setCc(ccField.getText());
        draft.setBcc(bccField.getText());
        draft.setSubject(subjectField.getText());
        draft.setBody(bodyArea.getText());
        // Coi như đã lưu; hiện "Đang lưu nháp...".
        dirty = false;
        draftStatus.setText("Đang lưu nháp...");
        // Gửi SAVE_DRAFT.
        Fx.then(MailApi.get().saveDraft(draft), id -> {
            // Lần đầu tạo nháp ở cửa sổ này: ghi nhớ (để xoá khi bỏ thư).
            if (draftId == null) {
                draftCreatedHere = true;
            }
            // Lưu id; hiện giờ lưu; nhắc tệp đính kèm không lưu trong nháp.
            draftId = id;
            draftStatus.setText("Đã lưu nháp lúc " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
            if (announce && files.size() > 0) {
                draftStatus.setText(draftStatus.getText() + " (tệp đính kèm không lưu trong nháp)");
            }
            if (afterSave != null) {
                afterSave.run();
            }
        }, error -> {
            // Lỗi: đánh dấu lại là có thay đổi, báo lỗi.
            dirty = true;
            draftStatus.setText("");
            showError("Không lưu được nháp: " + error);
        });
    }

    /** Đóng cửa sổ: thư còn nội dung chưa lưu thì hỏi lưu nháp. */
    @FXML
    public void handleDiscard() {
        // Đang gửi: không đóng.
        if (sending) {
            return;
        }
        // Không có thay đổi hoặc trống: đóng luôn.
        if (!dirty || !hasContent()) {
            close();
            return;
        }
        // Hỏi: Lưu nháp (lưu xong đóng) / Bỏ thư / Tiếp tục soạn.
        switch (Dialogs.unsavedDraft(window())) {
            case SAVE -> saveDraft(false, this::close);
            case DISCARD -> {
                if (draftCreatedHere && draftId != null && sameAccount()) {
                    // Bản nháp do tự lưu tạo ra trong phiên soạn này: bỏ thư thì xoá luôn.
                    MailApi.get().deleteDrafts(List.of(draftId));
                }
                close();
            }
            // Tiếp tục soạn: không làm gì.
            case CANCEL -> {
            }
        }
    }

    /** Bấm "Cc / Bcc": ẩn / hiện hai ô Cc và Bcc. */
    @FXML
    public void handleToggleCc() {
        boolean show = !ccRow.isVisible();
        ccRow.setVisible(show);
        ccRow.setManaged(show);
        bccRow.setVisible(show);
        bccRow.setManaged(show);
        ccToggle.setText(show ? "Ẩn Cc / Bcc" : "Cc / Bcc");
    }

    // Có ô nào có chữ không.
    private boolean hasContent() {
        return !toField.getText().isBlank() || !ccField.getText().isBlank() || !bccField.getText().isBlank()
                || !subjectField.getText().isBlank() || !bodyArea.getText().isBlank();
    }

    // Hiện lỗi ở cuối cửa sổ.
    private void showError(String message) {
        errorLabel.setText(message);
    }

    // Cửa sổ của khung soạn (chưa có cảnh thì cửa sổ chính).
    private javafx.stage.Window window() {
        return root.getScene() == null ? Navigator.owner() : root.getScene().getWindow();
    }

    // Dừng tự lưu rồi đóng cửa sổ.
    private void close() {
        if (autosave != null) {
            autosave.stop();
        }
        ((Stage) root.getScene().getWindow()).close();
    }
}
