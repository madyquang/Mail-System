package com.mailsystem.client.controller;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mailsystem.client.MailClientApp;
import com.mailsystem.client.network.ServerConnection;
import com.mailsystem.common.model.Account;
import com.mailsystem.common.model.Folder;
import com.mailsystem.common.model.MailSummary;
import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.EventName;
import com.mailsystem.common.protocol.ProtocolUtil;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Màn hình chính: hồ sơ người dùng, thư mục (kèm số thư chưa đọc), danh sách
 * thư hỗ trợ chọn nhiều. Thao tác trên nhiều thư được gửi thành MỘT request
 * ({entryIds: [...]}) thay vì N request.
 */
public class InboxController {

    private static final String[] AVATAR_COLORS = {
            "#176b60", "#2f6690", "#8a4f7d", "#b5651d", "#4f772d", "#6c5b7b", "#9c3d54" };

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
    private Label selectionLabel;
    @FXML
    private CheckBox selectAllCheck;
    @FXML
    private StackPane avatarPane;
    @FXML
    private Label avatarLabel;
    @FXML
    private Label profileNameLabel;
    @FXML
    private Label profileEmailLabel;
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
        showProfile(MailClientApp.getCurrentAccount());
        configureFolderCells();
        configureMailCells();
        mailListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        mailListView.getSelectionModel().getSelectedItems()
                .addListener((ListChangeListener<MailSummary>) change -> updateSelectionState());

        folderListView.getSelectionModel().selectedItemProperty().addListener((observable, oldFolder, folder) -> {
            if (folder != null && folder.getFolderId() != currentFolderId) {
                currentFolderId = folder.getFolderId();
                folderTitle.setText(folder.getFolderName());
                currentFolderIsTrash = "TRASH".equalsIgnoreCase(folder.getFolderType());
                setShown(moveToTrashButton, !currentFolderIsTrash);
                setShown(markReadButton, !currentFolderIsTrash);
                setShown(deleteTrashMailButton, currentFolderIsTrash);
                setShown(emptyTrashButton, currentFolderIsTrash);
                setShown(restoreTrashMailButton, currentFolderIsTrash);
                mailListView.getItems().clear();
                loadMailList();
            }
        });
        mailListView.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                handleOpenMail();
            }
        });
        mailListView.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                handleOpenMail();
            } else if (event.getCode() == KeyCode.DELETE) {
                if (currentFolderIsTrash) {
                    handleDeleteSelectedTrashMail();
                } else {
                    handleDeleteSelected();
                }
            }
        });
        updateSelectionState();
        loadFolders();
        registerEventListener();
    }

    // ------------------------------------------------------------------ hồ sơ

    private void showProfile(Account account) {
        String email = account == null || account.getEmail() == null ? "" : account.getEmail();
        String name = account == null || account.getDisplayName() == null || account.getDisplayName().isBlank()
                ? email
                : account.getDisplayName();
        profileNameLabel.setText(name.isBlank() ? "Người dùng" : name);
        profileEmailLabel.setText(email);
        avatarLabel.setText(initials(name.isBlank() ? "?" : name));
        String color = AVATAR_COLORS[Math.floorMod(email.toLowerCase().hashCode(), AVATAR_COLORS.length)];
        avatarPane.setStyle("-fx-background-color: " + color + ";");
    }

    /** "Nguyễn Văn An" -> "NA"; "an@mail.local" -> "A". */
    static String initials(String name) {
        String base = name.contains("@") ? name.substring(0, name.indexOf('@')) : name;
        String[] words = base.trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) {
            return "?";
        }
        String first = words[0].substring(0, 1);
        String last = words.length > 1 ? words[words.length - 1].substring(0, 1) : "";
        return (first + last).toUpperCase();
    }

    // ------------------------------------------------------------------ hiển thị

    private void configureFolderCells() {
        folderListView.setCellFactory(view -> new ListCell<>() {
            private final Label name = new Label();
            private final Label badge = new Label();
            private final Region spacer = new Region();
            private final HBox row = new HBox(8, name, spacer, badge);

            {
                name.getStyleClass().add("folder-name");
                badge.getStyleClass().add("folder-badge");
                HBox.setHgrow(spacer, Priority.ALWAYS);
            }

            @Override
            protected void updateItem(Folder folder, boolean empty) {
                super.updateItem(folder, empty);
                setText(null);
                if (empty || folder == null) {
                    setGraphic(null);
                    return;
                }
                name.setText(folder.getFolderName());
                // Thùng rác/Đã gửi không hiện số chưa đọc (không có ý nghĩa với người dùng).
                boolean showBadge = folder.getUnreadCount() > 0
                        && !"TRASH".equalsIgnoreCase(folder.getFolderType())
                        && !"SENT".equalsIgnoreCase(folder.getFolderType());
                badge.setText(folder.getUnreadCount() > 99 ? "99+" : Integer.toString(folder.getUnreadCount()));
                badge.setVisible(showBadge);
                setGraphic(row);
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
                getStyleClass().remove("unread-mail");
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
                if (!mail.isRead()) {
                    getStyleClass().add("unread-mail");
                }
                setGraphic(text);
            }
        });
    }

    private void updateSelectionState() {
        List<MailSummary> selected = selectedMails();
        int total = mailListView.getItems().size();
        boolean none = selected.isEmpty();
        selectionLabel.setText(none ? "" : "Đã chọn " + selected.size() + " thư");
        selectAllCheck.setSelected(!none && selected.size() == total);
        selectAllCheck.setIndeterminate(!none && selected.size() < total);
        selectAllCheck.setDisable(total == 0);
        markReadButton.setDisable(none || selected.stream().allMatch(MailSummary::isRead));
        moveToTrashButton.setDisable(none);
        restoreTrashMailButton.setDisable(none);
        deleteTrashMailButton.setDisable(none);
        emptyTrashButton.setDisable(total == 0);
    }

    // ------------------------------------------------------------------ tải dữ liệu

    private void loadFolders() {
        setStatus("Đang tải thư mục...");
        request(Command.GET_FOLDERS, new JsonObject(), (data, error) -> {
            if (error != null) {
                setStatus("Không tải được thư mục: " + error);
                return;
            }
            List<Folder> folders = parseFolders(data);
            if (folders == null) {
                setStatus("Máy chủ chưa trả danh sách thư mục hợp lệ.");
                return;
            }
            folderListView.getItems().setAll(folders);
            Folder inbox = folders.stream()
                    .filter(folder -> "INBOX".equalsIgnoreCase(folder.getFolderType()))
                    .findFirst().orElse(folders.isEmpty() ? null : folders.get(0));
            if (inbox != null) {
                folderListView.getSelectionModel().select(inbox);
            } else {
                setStatus("Tài khoản chưa có thư mục nào.");
            }
        });
    }

    /** Cập nhật số thư chưa đọc mà không đổi thư mục đang chọn. */
    private void refreshFolderCounts() {
        request(Command.GET_FOLDERS, new JsonObject(), (data, error) -> {
            List<Folder> folders = error == null ? parseFolders(data) : null;
            if (folders == null) {
                return;
            }
            Map<Integer, Folder> byId = folders.stream()
                    .collect(Collectors.toMap(Folder::getFolderId, Function.identity()));
            for (Folder folder : folderListView.getItems()) {
                Folder fresh = byId.get(folder.getFolderId());
                if (fresh != null) {
                    folder.setUnreadCount(fresh.getUnreadCount());
                }
            }
            folderListView.refresh();
        });
    }

    private List<Folder> parseFolders(JsonElement data) {
        if (data == null || !data.isJsonArray()) {
            return null;
        }
        List<Folder> folders = new ArrayList<>();
        for (JsonElement item : data.getAsJsonArray()) {
            folders.add(ProtocolUtil.fromJson(item.toString(), Folder.class));
        }
        return folders;
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
        int requestedFolder = currentFolderId;
        request(command, payload, (data, error) -> {
            if (requestedFolder != currentFolderId) {
                return; // người dùng đã chuyển thư mục khác trong lúc chờ
            }
            if (error != null) {
                setStatus("Không tải được thư: " + error);
                return;
            }
            if (data == null || !data.isJsonArray()) {
                setStatus("Máy chủ chưa trả danh sách thư hợp lệ.");
                return;
            }
            // Giữ lại lựa chọn hiện tại: EVENT real-time có thể tới đúng lúc người
            // dùng đang chọn nhiều thư.
            Set<Integer> previouslySelected = selectedMails().stream()
                    .map(MailSummary::getEntryId).collect(Collectors.toSet());
            List<MailSummary> mails = new ArrayList<>();
            for (JsonElement item : data.getAsJsonArray()) {
                mails.add(ProtocolUtil.fromJson(item.toString(), MailSummary.class));
            }
            mailListView.getItems().setAll(mails);
            for (int i = 0; i < mails.size(); i++) {
                if (previouslySelected.contains(mails.get(i).getEntryId())) {
                    mailListView.getSelectionModel().select(i);
                }
            }
            long unread = mails.stream().filter(mail -> !mail.isRead()).count();
            setStatus(mails.isEmpty()
                    ? (searching ? "Không có thư phù hợp." : "Thư mục này chưa có thư.")
                    : mails.size() + " thư" + (unread > 0 && !currentFolderIsTrash ? " · " + unread + " chưa đọc" : ""));
            updateSelectionState();
        });
    }

    private void reloadAfterChange() {
        loadMailList();
        refreshFolderCounts();
    }

    private void registerEventListener() {
        ServerConnection.getInstance().setEventListener(event -> Platform.runLater(() -> {
            if (EventName.NEW_MAIL.name().equals(event.getEventName())
                    || EventName.MAIL_READ_UPDATED.name().equals(event.getEventName())
                    || EventName.MAIL_DELETED.name().equals(event.getEventName())
                    || EventName.MAIL_RESTORED.name().equals(event.getEventName())) {
                reloadAfterChange();
            }
        }));
    }

    private void request(Command command, JsonObject payload, RequestCallback callback) {
        ServerConnection.getInstance().sendRequest(command, payload)
                .whenComplete((response, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        callback.complete(null, LoginController.describe(error));
                    } else if (!response.isOk()) {
                        callback.complete(null, response.getMessage());
                    } else {
                        callback.complete(response.getData(), null);
                    }
                }));
    }

    // ------------------------------------------------------------------ thao tác

    @FXML
    public void handleSearch() {
        searching = (searchField.getText() != null && !searchField.getText().isBlank())
                || (fromFilterField.getText() != null && !fromFilterField.getText().isBlank());
        loadMailList();
    }

    @FXML
    public void handleRefresh() {
        handleSearch();
        refreshFolderCounts();
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
    public void handleToggleSelectAll() {
        if (selectAllCheck.isSelected()) {
            mailListView.getSelectionModel().selectAll();
        } else {
            mailListView.getSelectionModel().clearSelection();
        }
    }

    @FXML
    public void handleOpenMail() {
        MailSummary selected = mailListView.getSelectionModel().getSelectedItem();
        if (selected == null)
            return;
        MailClientApp.setSelectedMail(selected.getMailId(), selected.getEntryId());
        MailClientApp.switchScene("/fxml/mail_detail.fxml");
    }

    @FXML
    public void handleDeleteSelected() {
        if (currentFolderIsTrash) {
            return;
        }
        List<MailSummary> selected = requireSelection("chuyển vào Thùng rác");
        if (selected.isEmpty() || !confirm("Chuyển vào Thùng rác",
                "Chuyển " + describeCount(selected) + " vào Thùng rác?")) {
            return;
        }
        runBatch(Command.DELETE_MAIL, selected, "Đã chuyển %d thư vào Thùng rác.",
                "Không chuyển được thư vào Thùng rác: ");
    }

    @FXML
    public void handleDeleteSelectedTrashMail() {
        if (!currentFolderIsTrash) {
            return;
        }
        List<MailSummary> selected = requireSelection("xóa vĩnh viễn");
        if (selected.isEmpty() || !confirm("Xóa vĩnh viễn",
                describeCount(selected) + " sẽ bị xóa vĩnh viễn khỏi tài khoản của bạn.")) {
            return;
        }
        runBatch(Command.DELETE_TRASH_MAIL, selected, "Đã xóa vĩnh viễn %d thư.", "Không thể xóa vĩnh viễn thư: ");
    }

    @FXML
    public void handleRestoreSelectedTrashMail() {
        List<MailSummary> selected = requireSelection("khôi phục");
        if (selected.isEmpty() || !confirm("Khôi phục thư",
                "Khôi phục " + describeCount(selected) + " về thư mục trước khi xóa?")) {
            return;
        }
        runBatch(Command.RESTORE_TRASH_MAIL, selected, "Đã khôi phục %d thư.", "Không thể khôi phục thư: ");
    }

    @FXML
    public void handleMarkReadSelected() {
        if (currentFolderIsTrash) {
            return;
        }
        List<MailSummary> unread = requireSelection("đánh dấu đã đọc").stream()
                .filter(mail -> !mail.isRead()).collect(Collectors.toList());
        if (unread.isEmpty()) {
            setStatus("Các thư đã chọn đều đã được đọc.");
            return;
        }
        runBatch(Command.MARK_READ, unread, "Đã đánh dấu %d thư là đã đọc.", "Không thể đánh dấu thư đã đọc: ");
    }

    @FXML
    public void handleEmptyTrash() {
        if (!confirm("Dọn sạch Thùng rác",
                "Tất cả thư trong Thùng rác sẽ bị xóa vĩnh viễn khỏi tài khoản của bạn.")) {
            return;
        }
        request(Command.EMPTY_TRASH, new JsonObject(), (data, error) -> {
            if (error != null) {
                setStatus("Không thể dọn sạch Thùng rác: " + error);
            } else {
                setStatus(data != null && data.isJsonPrimitive()
                        && data.getAsJsonPrimitive().isNumber()
                        && data.getAsInt() == 0
                                ? "Thùng rác đã trống."
                                : "Đã xóa tất cả thư trong Thùng rác.");
                reloadAfterChange();
            }
        });
    }

    /** Gửi MỘT request cho tất cả thư đã chọn: {entryIds: [...]}. */
    private void runBatch(Command command, List<MailSummary> mails, String successFormat, String errorPrefix) {
        JsonArray entryIds = new JsonArray();
        Set<Integer> unique = new HashSet<>();
        for (MailSummary mail : mails) {
            if (unique.add(mail.getEntryId())) {
                entryIds.add(mail.getEntryId());
            }
        }
        JsonObject payload = new JsonObject();
        payload.add("entryIds", entryIds);
        setStatus("Đang xử lý " + entryIds.size() + " thư...");
        request(command, payload, (data, error) -> {
            if (error != null) {
                setStatus(errorPrefix + error);
            } else {
                int processed = data != null && data.isJsonObject() && data.getAsJsonObject().has("processed")
                        ? data.getAsJsonObject().get("processed").getAsInt()
                        : entryIds.size();
                String message = String.format(successFormat, processed);
                if (processed < entryIds.size()) {
                    message += " (" + (entryIds.size() - processed) + " thư không còn tồn tại)";
                }
                setStatus(message);
            }
            mailListView.getSelectionModel().clearSelection();
            reloadAfterChange();
        });
    }

    private List<MailSummary> selectedMails() {
        return new ArrayList<>(mailListView.getSelectionModel().getSelectedItems());
    }

    private List<MailSummary> requireSelection(String action) {
        List<MailSummary> selected = selectedMails();
        if (selected.isEmpty()) {
            setStatus("Chọn ít nhất một thư để " + action + ".");
        }
        return selected;
    }

    private String describeCount(List<MailSummary> mails) {
        return mails.size() == 1 ? "thư này" : mails.size() + " thư đã chọn";
    }

    private boolean confirm(String header, String message) {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.CANCEL, ButtonType.OK);
        confirmation.setHeaderText(header);
        return confirmation.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    @FXML
    public void handleLogout() {
        request(Command.LOGOUT, new JsonObject(), (data, error) -> {
            MailClientApp.setCurrentAccount(null);
            MailClientApp.switchScene("/fxml/login.fxml");
        });
    }

    private static void setShown(Button button, boolean shown) {
        button.setVisible(shown);
        button.setManaged(shown);
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
