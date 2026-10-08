package com.mailsystem.client.controller;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mailsystem.client.network.ServerConnection;
import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.ProtocolConstants;
import com.mailsystem.common.protocol.ResponseMessage;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client-side compose interactions and validation. The server must repeat all
 * recipient and attachment validation before persisting a message.
 *
 * Gửi thư có tệp đính kèm gồm hai pha: tải từng tệp lên theo khối
 * (UPLOAD_ATTACHMENT_CHUNK, mỗi khối tối đa 512 KB) để nhận uploadId, rồi gửi
 * SEND_MAIL chỉ kèm danh sách uploadId.
 */
public class ComposeController {

    @FXML
    private TextField toField;
    @FXML
    private TextField ccField;
    @FXML
    private TextField bccField;
    @FXML
    private TextField subjectField;
    @FXML
    private TextArea bodyArea;
    @FXML
    private ListView<File> attachmentListView;
    @FXML
    private Label statusLabel;
    @FXML
    private Button sendButton;

    private static final long MAX_TOTAL_BYTES = ProtocolConstants.MAX_ATTACHMENT_TOTAL_BYTES;
    /** Đọc tệp trên thread riêng: không chặn UI thread hay thread nghe mạng. */
    private static final ExecutorService FILE_IO = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "compose-file-io");
        thread.setDaemon(true);
        return thread;
    });

    @FXML
    public void initialize() {
        attachmentListView.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(File file, boolean empty) {
                super.updateItem(file, empty);
                setText(empty || file == null ? null : file.getName() + "  ·  " + formatSize(file.length()));
            }
        });
    }

    @FXML
    public void handleSend() {
        JsonArray to = toEmailArray(toField.getText());
        JsonArray cc = toEmailArray(ccField.getText());
        JsonArray bcc = toEmailArray(bccField.getText());
        if (to.isEmpty() && cc.isEmpty() && bcc.isEmpty()) {
            showError("Thêm ít nhất một người nhận ở To, CC hoặc BCC.");
            return;
        }

        JsonObject payload = new JsonObject();
        payload.add("to", to);
        payload.add("cc", cc);
        payload.add("bcc", bcc);
        payload.addProperty("subject", subjectField.getText());
        payload.addProperty("body", bodyArea.getText());

        List<File> files = new ArrayList<>(attachmentListView.getItems());
        long totalBytes = 0;
        for (File file : files) {
            if (!file.isFile() || file.length() == 0) {
                showError("Tệp không tồn tại hoặc rỗng: " + file.getName());
                return;
            }
            totalBytes += file.length();
        }
        if (totalBytes > MAX_TOTAL_BYTES) {
            showError("Tổng dung lượng tệp không được vượt quá 25 MB.");
            return;
        }

        showStatus(files.isEmpty() ? "Đang gửi thư..." : "Đang tải tệp lên...");
        sendButton.setDisable(true);
        long uploadTotal = totalBytes;
        AtomicLong uploaded = new AtomicLong();
        uploadAll(files, uploaded, uploadTotal)
                .thenCompose(uploadIds -> {
                    JsonArray attachments = new JsonArray();
                    for (String uploadId : uploadIds) {
                        JsonObject attachment = new JsonObject();
                        attachment.addProperty("uploadId", uploadId);
                        attachments.add(attachment);
                    }
                    payload.add("attachments", attachments);
                    if (!uploadIds.isEmpty()) {
                        Platform.runLater(() -> showStatus("Đã tải tệp lên, đang gửi thư..."));
                    }
                    return ServerConnection.getInstance().sendRequest(Command.SEND_MAIL, payload);
                })
                .whenComplete((response, error) -> Platform.runLater(() -> {
                    sendButton.setDisable(false);
                    if (error != null) {
                        showError(LoginController.describe(error));
                    } else if (response.isOk()) {
                        statusLabel.getStyleClass().remove("error-text");
                        statusLabel.getStyleClass().add("success-text");
                        statusLabel.setText("Đã gửi thư thành công.");
                        clearForm();
                    } else {
                        showError(response.getMessage());
                    }
                }));
    }

    /** Tải lần lượt từng tệp (tuần tự để tiến độ dễ theo dõi và giới hạn bộ nhớ). */
    private CompletableFuture<List<String>> uploadAll(List<File> files, AtomicLong uploaded, long total) {
        CompletableFuture<List<String>> chain = CompletableFuture.completedFuture(new ArrayList<>());
        for (File file : files) {
            chain = chain.thenCompose(ids -> uploadChunk(file, file.length(), null, 0, uploaded, total)
                    .thenApply(uploadId -> {
                        ids.add(uploadId);
                        return ids;
                    }));
        }
        return chain;
    }

    /**
     * Gửi khối bắt đầu tại offset; khi Server xác nhận thì gửi khối kế tiếp
     * (stop-and-wait). Server trả receivedBytes nên Client luôn tiếp tục đúng
     * vị trí Server đã ghi.
     */
    private CompletableFuture<String> uploadChunk(File file, long size, String uploadId, long offset,
            AtomicLong uploaded, long total) {
        int length = (int) Math.min(ProtocolConstants.CHUNK_SIZE_BYTES, size - offset);
        return CompletableFuture.supplyAsync(() -> readChunk(file, offset, length), FILE_IO)
                .thenCompose(chunk -> {
                    JsonObject payload = new JsonObject();
                    if (uploadId != null) {
                        payload.addProperty("uploadId", uploadId);
                    }
                    payload.addProperty("filename", file.getName());
                    payload.addProperty("sizeBytes", size);
                    payload.addProperty("offset", offset);
                    payload.addProperty("dataBase64", Base64.getEncoder().encodeToString(chunk));
                    return ServerConnection.getInstance().sendRequest(Command.UPLOAD_ATTACHMENT_CHUNK, payload);
                })
                .thenComposeAsync(response -> {
                    UploadProgress progress = readProgress(response);
                    long done = uploaded.addAndGet(progress.receivedBytes - offset);
                    Platform.runLater(() -> showStatus("Đang tải tệp lên: " + file.getName() + " - "
                            + formatSize(done) + " / " + formatSize(total)));
                    if (progress.receivedBytes >= size) {
                        return CompletableFuture.completedFuture(progress.uploadId);
                    }
                    return uploadChunk(file, size, progress.uploadId, progress.receivedBytes, uploaded, total);
                }, FILE_IO);
    }

    private byte[] readChunk(File file, long offset, int length) {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            byte[] chunk = new byte[length];
            input.seek(offset);
            input.readFully(chunk);
            return chunk;
        } catch (EOFException error) {
            throw new UncheckedIOException(
                    new IOException("Tệp " + file.getName() + " đã bị thay đổi khi đang gửi."));
        } catch (IOException error) {
            throw new UncheckedIOException(new IOException("Không đọc được tệp " + file.getName() + "."));
        }
    }

    private UploadProgress readProgress(ResponseMessage response) {
        if (!response.isOk()) {
            throw new CompletionException(new IOException(response.getMessage()));
        }
        JsonElement data = response.getData();
        try {
            JsonObject values = data.getAsJsonObject();
            return new UploadProgress(values.get("uploadId").getAsString(),
                    values.get("receivedBytes").getAsLong());
        } catch (RuntimeException error) {
            throw new CompletionException(new IOException("Phản hồi tải tệp lên không hợp lệ."));
        }
    }

    private record UploadProgress(String uploadId, long receivedBytes) {
    }

    @FXML
    public void handleChooseAttachments() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn tệp đính kèm");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Tệp được hỗ trợ", "*.pdf", "*.docx", "*.pptx", "*.txt", "*.jpg", "*.png", "*.zip"));
        Window window = subjectField.getScene() == null ? null : subjectField.getScene().getWindow();
        List<File> selected = chooser.showOpenMultipleDialog(window);
        if (selected == null)
            return;

        List<File> candidate = new ArrayList<>(attachmentListView.getItems());
        for (File file : selected) {
            if (!ProtocolConstants.ALLOWED_ATTACHMENT_EXTENSIONS.contains(extension(file))) {
                showError("Không hỗ trợ định dạng tệp: " + file.getName());
                return;
            }
            if (!candidate.contains(file))
                candidate.add(file);
        }
        if (candidate.size() > ProtocolConstants.MAX_ATTACHMENTS_PER_MAIL) {
            showError("Mỗi thư đính kèm tối đa 5 tệp.");
            return;
        }
        long totalBytes = candidate.stream().mapToLong(File::length).sum();
        if (totalBytes > MAX_TOTAL_BYTES) {
            showError("Tổng dung lượng tệp không được vượt quá 25 MB.");
            return;
        }
        attachmentListView.getItems().setAll(candidate);
        showStatus(candidate.size() + " tệp · " + formatSize(totalBytes));
    }

    @FXML
    public void handleRemoveAttachment() {
        File selected = attachmentListView.getSelectionModel().getSelectedItem();
        if (selected != null)
            attachmentListView.getItems().remove(selected);
        showStatus(attachmentListView.getItems().size() + " tệp đính kèm");
    }

    @FXML
    public void handleBack() {
        com.mailsystem.client.MailClientApp.switchScene("/fxml/inbox.fxml");
    }

    private String extension(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private void clearForm() {
        toField.clear();
        ccField.clear();
        bccField.clear();
        subjectField.clear();
        bodyArea.clear();
        attachmentListView.getItems().clear();
    }

    private void showError(String message) {
        statusLabel.getStyleClass().remove("success-text");
        if (!statusLabel.getStyleClass().contains("error-text")) {
            statusLabel.getStyleClass().add("error-text");
        }
        statusLabel.setText(message == null || message.isBlank() ? "Gửi thư không thành công." : message);
    }

    private void showStatus(String message) {
        statusLabel.getStyleClass().remove("error-text");
        statusLabel.getStyleClass().remove("success-text");
        statusLabel.setText(message);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    /** Tach chuoi email cach nhau boi dau phay thanh JsonArray. */
    private JsonArray toEmailArray(String rawText) {
        JsonArray arr = new JsonArray();
        if (rawText == null || rawText.isBlank())
            return arr;
        for (String email : rawText.split(",")) {
            String recipient = email.trim();
            if (!recipient.isEmpty())
                arr.add(recipient);
        }
        return arr;
    }
}
