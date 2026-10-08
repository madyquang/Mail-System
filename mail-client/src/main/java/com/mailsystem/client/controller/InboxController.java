package com.mailsystem.client.controller;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mailsystem.client.MailClientApp;
import com.mailsystem.client.network.ServerConnection;
import com.mailsystem.common.model.Folder;
import com.mailsystem.common.model.MailSummary;
import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.EventName;
import com.mailsystem.common.protocol.ProtocolUtil;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class InboxController {

    @FXML
    private ListView<Folder> folderListView;
    @FXML
    private ListView<MailSummary> mailListView;
    @FXML
    private TextField searchField;
    @FXML
    private TextField fromFilterField;
    @FXML
    private Label folderTitle;
    @FXML
    private Label statusLabel;
    @FXML
    private Button moveToTrashButton;
    @FXML
    private Button deleteTrashMailButton;
    @FXML
    private Button emptyTrashButton;
    @FXML
    private Button restoreTrashMailButton;
    @FXML
    private Button markReadButton;

    private int currentFolderId = -1;
    private boolean currentFolderIsTrash;
    private boolean searching;

    @FXML
    public void initialize() {
        configureFolderCells();
        configureMailCells();
        folderListView.getSelectionModel().selectedItemProperty().addListener((observable, oldFolder, folder) -> {
            if (folder != null) {
                currentFolderId = folder.getFolderId();
                folderTitle.setText(folder.getFolderName());
                boolean isTrash = "TRASH".equalsIgnoreCase(folder.getFolderType());
                currentFolderIsTrash = isTrash;
                moveToTrashButton.setVisible(!isTrash);
                moveToTrashButton.setManaged(!isTrash);
                markReadButton.setVisible(!isTrash);
                markReadButton.setManaged(!isTrash);
                deleteTrashMailButton.setVisible(isTrash);
                deleteTrashMailButton.setManaged(isTrash);
                emptyTrashButton.setVisible(isTrash);
                emptyTrashButton.setManaged(isTrash);
                restoreTrashMailButton.setVisible(isTrash);
                restoreTrashMailButton.setManaged(isTrash);
                loadMailList();
            }
        });
        mailListView.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                handleOpenMail();
            }
        });
        loadFolders();
        registerEventListener();
    }

    private void configureFolderCells() {
        folderListView.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Folder folder, boolean empty) {
                super.updateItem(folder, empty);
                setText(empty || folder == null ? null : folder.getFolderName());
            }
        });
    }

    private void configureMailCells() {
        mailListView.setCellFactory(view -> new ListCell<>() {
            private final Label sender = new Label();
            private final Label date = new Label();
            private final Label subject = new Label();
            private final Label snippet = new Label();
            private final VBox text = new VBox(5, new HBox(10, sender, date), subject, snippet);

            {
                sender.getStyleClass().add("mail-sender");
                date.getStyleClass().add("mail-date");
                subject.getStyleClass().add("mail-subject");
                snippet.getStyleClass().add("mail-snippet");
                HBox.setHgrow(sender, Priority.ALWAYS);
                sender.setMaxWidth(Double.MAX_VALUE);
            }

            @Override
            protected void updateItem(MailSummary mail, boolean empty) {
                super.updateItem(mail, empty);
                if (empty || mail == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                sender.setText(mail.getSenderName() == null || mail.getSenderName().isBlank()
                        ? mail.getSenderEmail()
                        : mail.getSenderName());
                date.setText(formatDate(mail.getSentAt()));
                subject.setText(mail.getSubject() == null || mail.getSubject().isBlank()
                        ? "(Không có tiêu đề)"
                        : mail.getSubject());
                snippet.setText(mail.getSenderEmail() == null ? "" : mail.getSenderEmail());
                subject.setStyle(mail.isRead() ? "" : "-fx-font-weight: bold;");
                sender.setStyle(mail.isRead() ? "" : "-fx-font-weight: bold;");
                getStyleClass().remove("unread-mail");
                if (!mail.isRead()) {
                    getStyleClass().add("unread-mail");
                }
                setGraphic(text);
            }
        });
    }

    private void loadFolders() {
        setStatus("Đang tải thư mục...");
        request(Command.GET_FOLDERS, new JsonObject(), (data, error) -> {
            if (error != null) {
                setStatus("Không tải được thư mục: " + error);
                return;
            }
            if (data == null || !data.isJsonArray()) {
                setStatus("Máy chủ chưa trả danh sách thư mục hợp lệ.");
                return;
            }
            folderListView.getItems().clear();
            for (JsonElement item : data.getAsJsonArray()) {
                folderListView.getItems().add(ProtocolUtil.fromJson(item.toString(), Folder.class));
            }
            Folder inbox = folderListView.getItems().stream()
                    .filter(folder -> "INBOX".equalsIgnoreCase(folder.getFolderType()))
                    .findFirst().orElse(folderListView.getItems().isEmpty()
                            ? null
                            : folderListView.getItems().get(0));
            if (inbox != null) {
                folderListView.getSelectionModel().select(inbox);
            } else {
                setStatus("Tài khoản chưa có thư mục nào.");
            }
        });
    }

    private void loadMailList() {
        if (currentFolderId < 0)
            return;
        JsonObject payload = new JsonObject();
        payload.addProperty("folderId", currentFolderId);
        Command command = searching ? Command.SEARCH_MAIL : Command.GET_MAIL_LIST;
        if (searching) {
            payload.addProperty("keyword", searchField.getText() == null ? "" : searchField.getText().trim());
            payload.addProperty("fromFilter",
                    fromFilterField.getText() == null ? "" : fromFilterField.getText().trim());
        }
        setStatus("Đang tải thư...");
        request(command, payload, (data, error) -> {
            if (error != null) {
                setStatus("Không tải được thư: " + error);
                return;
            }
            if (data == null || !data.isJsonArray()) {
                setStatus("Máy chủ chưa trả danh sách thư hợp lệ.");
                return;
            }
            JsonArray items = data.getAsJsonArray();
            mailListView.getItems().clear();
            for (JsonElement item : items) {
                mailListView.getItems().add(ProtocolUtil.fromJson(item.toString(), MailSummary.class));
            }
            setStatus(items.size() == 0 ? "Thư mục này chưa có thư." : items.size() + " thư");
        });
    }

    private void registerEventListener() {
        ServerConnection.getInstance().setEventListener(event -> Platform.runLater(() -> {
            if (EventName.NEW_MAIL.name().equals(event.getEventName())
                    || EventName.MAIL_READ_UPDATED.name().equals(event.getEventName())
                    || EventName.MAIL_DELETED.name().equals(event.getEventName())
                    || EventName.MAIL_RESTORED.name().equals(event.getEventName())) {
                loadMailList();
            }
        }));
    }

    private void request(Command command, JsonObject payload, RequestCallback callback) {
        try {
            ServerConnection.getInstance().sendRequest(command, payload)
                    .whenComplete((response, error) -> Platform.runLater(() -> {
                        if (error != null) {
                            callback.complete(null, "không có kết nối");
                        } else if (!response.isOk()) {
                            callback.complete(null, response.getMessage());
                        } else {
                            callback.complete(response.getData(), null);
                        }
                    }));
        } catch (RuntimeException error) {
            callback.complete(null, "không có kết nối");
        }
    }

    @FXML
    public void handleSearch() {
        searching = (searchField.getText() != null && !searchField.getText().isBlank())
                || (fromFilterField.getText() != null && !fromFilterField.getText().isBlank());
        loadMailList();
    }

    @FXML
    public void handleRefresh() {
        handleSearch();
    }

    @FXML
    public void handleCompose() {
        MailClientApp.switchScene("/fxml/compose.fxml");
    }

    @FXML
    public void handleManageGroups() {
        MailClientApp.switchScene("/fxml/group_management.fxml");
    }

    @FXML
    public void handleOpenMail() {
        MailSummary selected = mailListView.getSelectionModel().getSelectedItem();
        if (selected == null)
            return;
        MailClientApp.setSelectedMailId(selected.getMailId());
        MailClientApp.switchScene("/fxml/mail_detail.fxml");
    }

    @FXML
    public void handleDeleteSelected() {
        MailSummary selected = mailListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            setStatus("Chọn một thư cần chuyển vào Thùng rác.");
            return;
        }
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "Chuyển thư này vào Thùng rác?", ButtonType.CANCEL, ButtonType.OK);
        confirmation.setHeaderText("Chuyển thư vào Thùng rác");
        confirmation.showAndWait().filter(ButtonType.OK::equals).ifPresent(button -> {
            JsonObject payload = new JsonObject();
            payload.addProperty("entryId", selected.getEntryId());
            request(Command.DELETE_MAIL, payload, (data, error) -> {
                if (error != null) {
                    setStatus("Không chuyển được thư vào Thùng rác: " + error);
                } else {
                    setStatus("Đã chuyển thư vào Thùng rác.");
                    loadMailList();
                }
            });
        });
    }

    @FXML
    public void handleDeleteSelectedTrashMail() {
        MailSummary selected = mailListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            setStatus("Chọn một thư trong Thùng rác cần xóa vĩnh viễn.");
            return;
        }
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "Thư này sẽ bị xóa vĩnh viễn khỏi tài khoản của bạn.", ButtonType.CANCEL, ButtonType.OK);
        confirmation.setHeaderText("Xóa vĩnh viễn thư");
        confirmation.showAndWait().filter(ButtonType.OK::equals).ifPresent(button -> {
            JsonObject payload = new JsonObject();
            payload.addProperty("entryId", selected.getEntryId());
            request(Command.DELETE_TRASH_MAIL, payload, (data, error) -> {
                if (error != null) {
                    setStatus("Không thể xóa vĩnh viễn thư: " + error);
                } else {
                    setStatus("Đã xóa vĩnh viễn thư.");
                    loadMailList();
                }
            });
        });
    }

    @FXML
    public void handleEmptyTrash() {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "Tất cả thư trong Thùng rác sẽ bị xóa vĩnh viễn khỏi tài khoản của bạn.",
                ButtonType.CANCEL, ButtonType.OK);
        confirmation.setHeaderText("Dọn sạch Thùng rác");
        confirmation.showAndWait().filter(ButtonType.OK::equals).ifPresent(button -> {
            request(Command.EMPTY_TRASH, new JsonObject(), (data, error) -> {
                if (error != null) {
                    setStatus("Không thể dọn sạch Thùng rác: " + error);
                } else {
                    setStatus(data != null && data.isJsonPrimitive()
                            && data.getAsJsonPrimitive().isNumber()
                            && data.getAsInt() == 0
                                    ? "Thùng rác đã trống."
                                    : "Đã xóa tất cả thư trong Thùng rác.");
                    loadMailList();
                }
            });
        });
    }

    @FXML
    public void handleRestoreSelectedTrashMail() {
        MailSummary selected = mailListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            setStatus("Chọn một thư trong Thùng rác cần khôi phục.");
            return;
        }
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "Khôi phục thư về thư mục trước khi xóa?", ButtonType.CANCEL, ButtonType.OK);
        confirmation.setHeaderText("Khôi phục thư");
        confirmation.showAndWait().filter(ButtonType.OK::equals).ifPresent(button -> {
            JsonObject payload = new JsonObject();
            payload.addProperty("entryId", selected.getEntryId());
            request(Command.RESTORE_TRASH_MAIL, payload, (data, error) -> {
                if (error != null) {
                    setStatus("Không thể khôi phục thư: " + error);
                } else {
                    setStatus("Đã khôi phục thư.");
                    loadMailList();
                }
            });
        });
    }

    @FXML
    public void handleMarkReadSelected() {
        if (currentFolderIsTrash) {
            return;
        }
        MailSummary selected = mailListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            setStatus("Chọn một thư cần đánh dấu đã đọc.");
            return;
        }
        if (selected.isRead()) {
            setStatus("Thư này đã được đọc.");
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("entryId", selected.getEntryId());
        request(Command.MARK_READ, payload, (data, error) -> {
            if (error != null) {
                setStatus("Không thể đánh dấu thư đã đọc: " + error);
                return;
            }
            selected.setRead(true);
            mailListView.refresh();
            setStatus("Đã đánh dấu thư là đã đọc.");
        });
    }

    @FXML
    public void handleLogout() {
        request(Command.LOGOUT, new JsonObject(), (data, error) -> MailClientApp.switchScene("/fxml/login.fxml"));
    }

    private void setStatus(String message) {
        statusLabel.setText(message);
    }

    private String formatDate(String value) {
        if (value == null || value.isBlank())
            return "";
        try {
            LocalDateTime dateTime = LocalDateTime.parse(value);
            return dateTime.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"));
        } catch (RuntimeException ignored) {
            try {
                return DateTimeFormatter.ofPattern("dd/MM HH:mm")
                        .withZone(ZoneId.systemDefault()).format(Instant.parse(value));
            } catch (RuntimeException ignoredAgain) {
                return value;
            }
        }
    }

    @FunctionalInterface
    private interface RequestCallback {
        void complete(JsonElement data, String error);
    }
}