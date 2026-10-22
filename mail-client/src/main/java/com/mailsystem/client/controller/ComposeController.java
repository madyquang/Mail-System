package com.mailsystem.client.controller;

import com.mailsystem.client.api.MailApi;
import com.mailsystem.client.app.Navigator;
import com.mailsystem.client.app.Session;
import com.mailsystem.client.service.AttachmentTransfer;
import com.mailsystem.client.ui.Dialogs;
import com.mailsystem.client.ui.Formats;
import com.mailsystem.client.ui.Fx;
import com.mailsystem.client.ui.Icons;
import com.mailsystem.client.ui.Toast;
import com.mailsystem.common.model.Attachment;
import com.mailsystem.common.model.Draft;
import com.mailsystem.common.model.MailDetail;
import com.mailsystem.common.protocol.ProtocolConstants;
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
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
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

    private static final Duration AUTOSAVE_INTERVAL = Duration.seconds(20);

    /** Dữ liệu khởi tạo cửa sổ soạn thư. */
    public record Request(String title, String to, String cc, String bcc, String subject, String body,
            Integer draftId, List<Attachment> forwardAttachments) {

        public static Request blank() {
            return new Request("Thư mới", "", "", "", "", "", null, List.of());
        }

        public static Request draft(Draft draft) {
            return new Request("Thư nháp", draft.getTo(), draft.getCc(), draft.getBcc(), draft.getSubject(),
                    draft.getBody(), draft.getDraftId(), List.of());
        }

        public static Request reply(MailDetail mail, boolean all) {
            String me = Session.account() == null ? "" : Session.account().getEmail();
            Set<String> to = new LinkedHashSet<>();
            to.add(mail.getSenderEmail());
            Set<String> cc = new LinkedHashSet<>();
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
            cc.removeIf(email -> email.equalsIgnoreCase(me) || to.contains(email));
            String sender = mail.getSenderName() == null || mail.getSenderName().isBlank()
                    ? mail.getSenderEmail() : mail.getSenderName();
            String quote = "\n\nVào " + Formats.longDate(mail.getSentAt()) + ", " + sender + " <" + mail.getSenderEmail()
                    + "> đã viết:\n" + quoted(mail.getBody());
            return new Request(all ? "Trả lời tất cả" : "Trả lời", String.join(", ", to), String.join(", ", cc), "",
                    prefixed("Re: ", mail.getSubject()), quote, null, List.of());
        }

        public static Request forward(MailDetail mail) {
            String header = "\n\n---------- Thư được chuyển tiếp ----------\n"
                    + "Từ: " + nullToEmpty(mail.getSenderName()) + " <" + mail.getSenderEmail() + ">\n"
                    + "Ngày: " + Formats.longDate(mail.getSentAt()) + "\n"
                    + "Tiêu đề: " + nullToEmpty(mail.getSubject()) + "\n"
                    + "Tới: " + (mail.getTo() == null ? "" : String.join(", ", mail.getTo())) + "\n\n"
                    + nullToEmpty(mail.getBody());
            return new Request("Chuyển tiếp", "", "", "", prefixed("Fwd: ", mail.getSubject()), header, null,
                    mail.getAttachments() == null ? List.of() : mail.getAttachments());
        }

        private static String prefixed(String prefix, String subject) {
            String value = subject == null ? "" : subject.trim();
            return value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT)) ? value : prefix + value;
        }

        private static String quoted(String body) {
            return nullToEmpty(body).lines().map(line -> "> " + line).collect(Collectors.joining("\n"));
        }

        private static String nullToEmpty(String value) {
            return value == null ? "" : value;
        }
    }

    /** Mở một cửa sổ soạn thư mới. */
    public static void open(Request request) {
        Navigator.<ComposeController>openWindow(Navigator.COMPOSE, request.title() + " – Mail System", 780, 640,
                false, controller -> controller.init(request));
    }

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

    private final List<File> files = new ArrayList<>();
    private final List<Attachment> forwarded = new ArrayList<>();
    private Integer draftId;
    private boolean draftCreatedHere;
    private boolean dirty;
    private boolean sending;
    private Timeline autosave;

    private void init(Request request) {
        titleLabel.setText(request.title());
        toField.setText(request.to());
        ccField.setText(request.cc());
        bccField.setText(request.bcc());
        subjectField.setText(request.subject());
        bodyArea.setText(request.body());
        draftId = request.draftId();
        forwarded.addAll(request.forwardAttachments());
        if (!request.cc().isBlank() || !request.bcc().isBlank()) {
            handleToggleCc();
        }
        renderAttachments();
        for (TextField field : new TextField[] {toField, ccField, bccField, subjectField}) {
            field.textProperty().addListener((observable, old, value) -> dirty = true);
        }
        bodyArea.textProperty().addListener((observable, old, value) -> dirty = true);
        dirty = false;

        Platform.runLater(() -> {
            if (toField.getText().isBlank()) {
                toField.requestFocus();
            } else {
                bodyArea.requestFocus();
                bodyArea.positionCaret(0);
            }
            Stage stage = (Stage) root.getScene().getWindow();
            stage.setOnCloseRequest(event -> {
                event.consume();
                handleDiscard();
            });
            root.getScene().getAccelerators().put(
                    new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN), this::handleSend);
            root.getScene().getAccelerators().put(
                    new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN), this::handleSaveDraft);
        });

        autosave = new Timeline(new KeyFrame(AUTOSAVE_INTERVAL, event -> {
            if (dirty && !sending && hasContent()) {
                saveDraft(false, null);
            }
        }));
        autosave.setCycleCount(Timeline.INDEFINITE);
        autosave.play();
    }

    // ------------------------------------------------------------------ gửi

    @FXML
    public void handleSend() {
        if (sending) {
            return;
        }
        List<String> to = Formats.splitRecipients(toField.getText());
        List<String> cc = Formats.splitRecipients(ccField.getText());
        List<String> bcc = Formats.splitRecipients(bccField.getText());
        if (to.isEmpty() && cc.isEmpty() && bcc.isEmpty()) {
            showError("Thêm ít nhất một người nhận.");
            toField.requestFocus();
            return;
        }
        if (bodyArea.getText().length() > ProtocolConstants.MAX_BODY_CHARS) {
            showError("Nội dung thư quá dài (tối đa " + ProtocolConstants.MAX_BODY_CHARS + " ký tự).");
            return;
        }
        if (subjectField.getText().isBlank() && !Dialogs.confirm(window(), "Gửi thư không có tiêu đề?",
                "Thư này chưa có tiêu đề.", "Vẫn gửi", false)) {
            return;
        }
        for (File file : files) {
            if (!file.isFile() || file.length() == 0) {
                showError("Tệp không tồn tại hoặc rỗng: " + file.getName());
                return;
            }
        }

        setSending(true);
        long total = files.stream().mapToLong(File::length).sum();
        if (total > 0) {
            progressBox.setVisible(true);
            progressBox.setManaged(true);
        }
        Fx.then(AttachmentTransfer.uploadAll(new ArrayList<>(files), sent -> Platform.runLater(() -> {
                    uploadProgress.setProgress(total == 0 ? 1 : sent / (double) total);
                    progressLabel.setText("Đang tải tệp lên " + Formats.size(sent) + " / " + Formats.size(total));
                }))
                .thenCompose(uploadIds -> {
                    Platform.runLater(() -> progressLabel.setText("Đang gửi thư..."));
                    return MailApi.get().send(new MailApi.OutgoingMail(to, cc, bcc, subjectField.getText(),
                            bodyArea.getText(), uploadIds,
                            forwarded.stream().map(Attachment::getAttachmentId).collect(Collectors.toList()), draftId));
                }), mailId -> {
                    close();
                    StackPane mainRoot = Navigator.primaryStage().getScene().getRoot() instanceof StackPane pane
                            ? pane : null;
                    Toast.success(mainRoot, "Đã gửi thư.");
                }, error -> {
                    setSending(false);
                    progressBox.setVisible(false);
                    progressBox.setManaged(false);
                    showError(error);
                });
    }

    private void setSending(boolean value) {
        sending = value;
        sendButton.setDisable(value);
        sendButton.setText(value ? "Đang gửi..." : "Gửi");
        for (javafx.scene.Node node : new javafx.scene.Node[] {toField, ccField, bccField, subjectField, bodyArea,
                attachButton, saveDraftButton, discardButton, attachmentPane}) {
            node.setDisable(value);
        }
        errorLabel.setText("");
    }

    // ------------------------------------------------------------------ tệp đính kèm

    @FXML
    public void handleAttach() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn tệp đính kèm");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Tệp được hỗ trợ (PDF, DOCX, PPTX, TXT, JPG, PNG, ZIP)",
                "*.pdf", "*.docx", "*.pptx", "*.txt", "*.jpg", "*.png", "*.zip"));
        List<File> selected = chooser.showOpenMultipleDialog(window());
        if (selected == null) {
            return;
        }
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
        if (candidate.size() + forwarded.size() > ProtocolConstants.MAX_ATTACHMENTS_PER_MAIL) {
            showError("Mỗi thư đính kèm tối đa " + ProtocolConstants.MAX_ATTACHMENTS_PER_MAIL + " tệp.");
            return;
        }
        long total = candidate.stream().mapToLong(File::length).sum()
                + forwarded.stream().mapToLong(Attachment::getSizeBytes).sum();
        if (total > ProtocolConstants.MAX_ATTACHMENT_TOTAL_BYTES) {
            showError("Tổng dung lượng tệp không được vượt quá 25 MB (hiện tại " + Formats.size(total) + ").");
            return;
        }
        files.clear();
        files.addAll(candidate);
        errorLabel.setText("");
        renderAttachments();
    }

    private void renderAttachments() {
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

    @FXML
    public void handleSaveDraft() {
        if (!sending) {
            saveDraft(true, null);
        }
    }

    private void saveDraft(boolean announce, Runnable afterSave) {
        Draft draft = new Draft();
        draft.setDraftId(draftId == null ? 0 : draftId);
        draft.setTo(toField.getText());
        draft.setCc(ccField.getText());
        draft.setBcc(bccField.getText());
        draft.setSubject(subjectField.getText());
        draft.setBody(bodyArea.getText());
        dirty = false;
        draftStatus.setText("Đang lưu nháp...");
        Fx.then(MailApi.get().saveDraft(draft), id -> {
            if (draftId == null) {
                draftCreatedHere = true;
            }
            draftId = id;
            draftStatus.setText("Đã lưu nháp lúc " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
            if (announce && files.size() > 0) {
                draftStatus.setText(draftStatus.getText() + " (tệp đính kèm không lưu trong nháp)");
            }
            if (afterSave != null) {
                afterSave.run();
            }
        }, error -> {
            dirty = true;
            draftStatus.setText("");
            showError("Không lưu được nháp: " + error);
        });
    }

    /** Đóng cửa sổ: thư còn nội dung chưa lưu thì hỏi lưu nháp. */
    @FXML
    public void handleDiscard() {
        if (sending) {
            return;
        }
        if (!dirty || !hasContent()) {
            close();
            return;
        }
        switch (Dialogs.unsavedDraft(window())) {
            case SAVE -> saveDraft(false, this::close);
            case DISCARD -> {
                if (draftCreatedHere && draftId != null) {
                    // Bản nháp do tự lưu tạo ra trong phiên soạn này: bỏ thư thì xoá luôn.
                    MailApi.get().deleteDrafts(List.of(draftId));
                }
                close();
            }
            case CANCEL -> {
            }
        }
    }

    @FXML
    public void handleToggleCc() {
        boolean show = !ccRow.isVisible();
        ccRow.setVisible(show);
        ccRow.setManaged(show);
        bccRow.setVisible(show);
        bccRow.setManaged(show);
        ccToggle.setText(show ? "Ẩn Cc / Bcc" : "Cc / Bcc");
    }

    private boolean hasContent() {
        return !toField.getText().isBlank() || !ccField.getText().isBlank() || !bccField.getText().isBlank()
                || !subjectField.getText().isBlank() || !bodyArea.getText().isBlank();
    }

    private void showError(String message) {
        errorLabel.setText(message);
    }

    private javafx.stage.Window window() {
        return root.getScene() == null ? Navigator.owner() : root.getScene().getWindow();
    }

    private void close() {
        if (autosave != null) {
            autosave.stop();
        }
        ((Stage) root.getScene().getWindow()).close();
    }
}
