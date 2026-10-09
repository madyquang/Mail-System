package com.mailsystem.client.controller;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.mailsystem.client.MailClientApp;
import com.mailsystem.client.network.ServerConnection;
import com.mailsystem.common.model.Attachment;
import com.mailsystem.common.model.MailDetail;
import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.ProtocolUtil;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

public class MailDetailController {

    @FXML
    private Label subjectLabel;
    @FXML
    private Label senderLabel;
    @FXML
    private Label dateLabel;
    @FXML
    private Label toLabel;
    @FXML
    private Label ccLabel;
    @FXML
    private Label bccTitleLabel;
    @FXML
    private Label bccLabel;
    @FXML
    private Label statusLabel;
    @FXML
    private TextArea bodyArea;
    @FXML
    private VBox attachmentsBox;

    @FXML
    public void initialize() {
        bodyArea.setEditable(false);
        int mailId = MailClientApp.getSelectedMailId();
        if (mailId <= 0) {
            showError("Không có thư nào được chọn.");
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("mailId", mailId);
        if (MailClientApp.getSelectedEntryId() > 0) {
            payload.addProperty("entryId", MailClientApp.getSelectedEntryId());
        }
        statusLabel.setText("Đang tải nội dung thư...");
        try {
            ServerConnection.getInstance().sendRequest(Command.GET_MAIL_DETAIL, payload)
                    .whenComplete((response, error) -> Platform.runLater(() -> {
                        if (error != null) {
                            showError("Không nhận được phản hồi từ máy chủ.");
                        } else if (!response.isOk()) {
                            showError(response.getMessage());
                        } else if (response.getData() == null || response.getData().isJsonNull()) {
                            showError("Máy chủ không trả nội dung thư.");
                        } else {
                            render(ProtocolUtil.fromJson(response.getData().toString(), MailDetail.class));
                        }
                    }));
        } catch (RuntimeException error) {
            showError("Chưa kết nối được máy chủ.");
        }
    }

    private void render(MailDetail mail) {
        subjectLabel.setText(mail.getSubject() == null || mail.getSubject().isBlank()
                ? "(Không có tiêu đề)"
                : mail.getSubject());
        senderLabel.setText(value(mail.getSenderName()) + "  <" + value(mail.getSenderEmail()) + ">");
        dateLabel.setText(value(mail.getSentAt()));
        toLabel.setText(join(mail.getTo()));
        ccLabel.setText(join(mail.getCc()));
        boolean hasVisibleBcc = mail.getBcc() != null && !mail.getBcc().isEmpty();
        bccTitleLabel.setVisible(hasVisibleBcc);
        bccTitleLabel.setManaged(hasVisibleBcc);
        bccLabel.setText(hasVisibleBcc ? join(mail.getBcc()) : "");
        bccLabel.setVisible(hasVisibleBcc);
        bccLabel.setManaged(hasVisibleBcc);
        bodyArea.setText(value(mail.getBody()));
        attachmentsBox.getChildren().clear();
        if (mail.getAttachments() != null) {
            for (Attachment attachment : mail.getAttachments()) {
                Button openButton = new Button("Mở / tải xuống: " + attachment.getFilename()
                        + "  ·  " + formatSize(attachment.getSizeBytes()));
                openButton.getStyleClass().add("quiet-button");
                openButton.setOnAction(event -> downloadAttachment(attachment, openButton));
                attachmentsBox.getChildren().add(openButton);
            }
        }
        statusLabel.setText(attachmentsBox.getChildren().isEmpty()
                ? "Đã tải thư"
                : attachmentsBox.getChildren().size() + " tệp đính kèm");
    }

    private void downloadAttachment(Attachment attachment, Button button) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Lưu tệp đính kèm");
        String filename = safeFileName(attachment.getFilename());
        String extension = fileExtension(filename);
        chooser.setInitialFileName(filename);
        if (!extension.isEmpty()) {
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    extension.toUpperCase() + " files", "*" + extension));
        }
        Path initialDirectory = Path.of(System.getProperty("user.home"), "Downloads");
        if (Files.isDirectory(initialDirectory)) {
            chooser.setInitialDirectory(initialDirectory.toFile());
        }
        java.io.File destination = chooser.showSaveDialog(button.getScene().getWindow());
        if (destination == null) {
            return;
        }

        Path selectedPath = destination.toPath().toAbsolutePath();
        Path destinationPath = withExpectedExtension(selectedPath, extension);
        if (!destinationPath.equals(selectedPath) && Files.exists(destinationPath)) {
            Alert overwrite = new Alert(Alert.AlertType.CONFIRMATION,
                    "Tệp " + destinationPath.getFileName() + " đã tồn tại. Ghi đè?",
                    ButtonType.CANCEL, ButtonType.OK);
            overwrite.setHeaderText("Xác nhận ghi đè");
            if (overwrite.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
                return;
            }
        }

        Path temporaryPath;
        try {
            temporaryPath = Files.createTempFile(destinationPath.getParent(), ".mail-download-", ".part");
        } catch (IOException error) {
            showError("Không thể tạo tệp tải tạm: " + error.getMessage());
            return;
        }

        button.setDisable(true);
        requestAttachmentChunk(attachment, destinationPath, temporaryPath, button, 0);
    }

    private void requestAttachmentChunk(Attachment metadata, Path destination, Path temporaryPath,
            Button button, long offset) {
        JsonObject payload = new JsonObject();
        payload.addProperty("attachmentId", metadata.getAttachmentId());
        payload.addProperty("offset", offset);
        try {
            ServerConnection.getInstance().sendRequest(Command.DOWNLOAD_ATTACHMENT, payload)
                    .orTimeout(30, TimeUnit.SECONDS)
                    .whenComplete((response, error) -> {
                        if (error != null) {
                            failDownload(temporaryPath, button,
                                    "Mất kết nối hoặc hết thời gian chờ khi tải tệp.");
                            return;
                        }
                        if (!response.isOk()) {
                            failDownload(temporaryPath, button, response.getMessage());
                            return;
                        }
                        if (response.getData() == null || response.getData().isJsonNull()) {
                            failDownload(temporaryPath, button, "Máy chủ không trả nội dung tệp.");
                            return;
                        }

                        final byte[] chunk;
                        try {
                            Attachment responseChunk = ProtocolUtil.fromJson(
                                    response.getData().toString(), Attachment.class);
                            if (responseChunk.getAttachmentId() != metadata.getAttachmentId()
                                    || responseChunk.getSizeBytes() != metadata.getSizeBytes()
                                    || responseChunk.getDataBase64() == null) {
                                throw new IllegalArgumentException("Metadata tệp tải về không khớp.");
                            }
                            chunk = Base64.getDecoder().decode(responseChunk.getDataBase64());
                            if (offset + chunk.length > metadata.getSizeBytes()
                                    || (chunk.length == 0 && offset < metadata.getSizeBytes())) {
                                throw new IllegalArgumentException("Dữ liệu tệp tải về không đầy đủ.");
                            }
                        } catch (RuntimeException parseError) {
                            failDownload(temporaryPath, button, "Phản hồi tệp từ máy chủ không hợp lệ.");
                            return;
                        }

                        CompletableFuture.runAsync(() -> writeChunk(temporaryPath, chunk, offset))
                                .whenComplete((ignored, writeError) -> {
                                    if (writeError != null) {
                                        failDownload(temporaryPath, button,
                                                "Không thể ghi tệp tải về: " + errorMessage(writeError));
                                        return;
                                    }
                                    long nextOffset = offset + chunk.length;
                                    Platform.runLater(() -> statusLabel.setText(
                                            "Đang tải tệp " + metadata.getFilename() + " — "
                                                    + formatSize(nextOffset) + " / "
                                                    + formatSize(metadata.getSizeBytes())));
                                    if (nextOffset < metadata.getSizeBytes()) {
                                        requestAttachmentChunk(metadata, destination, temporaryPath, button,
                                                nextOffset);
                                    } else {
                                        finishDownload(destination, temporaryPath, button, metadata);
                                    }
                                });
                    });
        } catch (RuntimeException error) {
            failDownload(temporaryPath, button, "Chưa kết nối được máy chủ.");
        }
    }

    private void writeChunk(Path temporaryPath, byte[] chunk, long offset) {
        try {
            if (offset == 0) {
                Files.write(temporaryPath, chunk,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                Files.write(temporaryPath, chunk, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            }
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private void finishDownload(Path destination, Path temporaryPath, Button button, Attachment metadata) {
        CompletableFuture.runAsync(() -> {
            try {
                if (Files.size(temporaryPath) != metadata.getSizeBytes()) {
                    throw new IOException("Kích thước tệp tải về không khớp.");
                }
                try {
                    Files.move(temporaryPath, destination,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException error) {
                    Files.move(temporaryPath, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        }).whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (error != null) {
                failDownload(temporaryPath, button, "Không thể hoàn tất tệp tải về: " + errorMessage(error));
                return;
            }
            statusLabel.setText("Đã tải xong: " + destination + " — đang mở...");
            openSavedAttachment(destination, button);
        }));
    }

    private void failDownload(Path temporaryPath, Button button, String message) {
        CompletableFuture.runAsync(() -> {
            try {
                Files.deleteIfExists(temporaryPath);
            } catch (IOException error) {
                System.err.println("Không thể xóa tệp tải tạm: " + error.getMessage());
            }
        });
        Platform.runLater(() -> {
            button.setDisable(false);
            showError(message);
        });
    }

    private void openSavedAttachment(Path destination, Button button) {
        CompletableFuture.runAsync(() -> {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                throw new UnsupportedOperationException("Thiết bị không hỗ trợ mở tệp tự động.");
            }
            try {
                Desktop.getDesktop().open(destination.toFile());
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        }).whenComplete((ignored, error) -> Platform.runLater(() -> {
            button.setDisable(false);
            if (error == null) {
                statusLabel.setText("Đã mở tệp: " + destination);
            } else {
                statusLabel.setText("Đã tải tệp nhưng không mở tự động được. Tệp đã lưu tại: " + destination);
            }
        }));
    }

    private String errorMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private String safeFileName(String filename) {
        String safeName = filename == null ? "attachment" : filename.replace('\\', '/');
        safeName = safeName.substring(safeName.lastIndexOf('/') + 1);
        return safeName.isBlank() ? "attachment" : safeName;
    }

    private Path withExpectedExtension(Path selectedPath, String expectedExtension) {
        if (expectedExtension.isEmpty()) {
            return selectedPath;
        }
        String selectedName = selectedPath.getFileName().toString();
        String selectedExtension = fileExtension(selectedName);
        if (expectedExtension.equalsIgnoreCase(selectedExtension)) {
            return selectedPath;
        }

        String baseName = selectedExtension.isEmpty()
                ? selectedName
                : selectedName.substring(0, selectedName.length() - selectedExtension.length());
        return selectedPath.resolveSibling(baseName + expectedExtension);
    }

    private String fileExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot <= 0 ? "" : filename.substring(dot).toLowerCase(java.util.Locale.ROOT);
    }

    @FXML
    public void handleBack() {
        MailClientApp.switchScene("/fxml/inbox.fxml");
    }

    private void showError(String message) {
        statusLabel.setText(message == null || message.isBlank() ? "Không tải được thư." : message);
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String join(java.util.List<String> recipients) {
        return recipients == null || recipients.isEmpty() ? "—" : String.join(", ", recipients);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}