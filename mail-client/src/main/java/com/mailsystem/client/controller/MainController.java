package com.mailsystem.client.controller;

import com.mailsystem.client.api.MailApi;
import com.mailsystem.client.app.Navigator;
import com.mailsystem.client.app.Reconnector;
import com.mailsystem.client.app.Session;
import com.mailsystem.client.network.Connector;
import com.mailsystem.client.network.ServerConnection;
import com.mailsystem.client.service.DesktopNotifier;
import com.mailsystem.client.ui.AutoComplete;
import com.mailsystem.client.ui.Avatar;
import com.mailsystem.client.ui.ContactSuggestions;
import com.mailsystem.client.ui.Formats;
import com.mailsystem.client.ui.Dialogs;
import com.mailsystem.client.ui.DraftCell;
import com.mailsystem.client.ui.EmptyState;
import com.mailsystem.client.ui.Fx;
import com.mailsystem.client.ui.Icons;
import com.mailsystem.client.ui.MailCell;
import com.mailsystem.client.ui.MailReader;
import com.mailsystem.client.ui.NavButton;
import com.mailsystem.client.ui.ScheduledCell;
import com.mailsystem.client.ui.Toast;
import com.mailsystem.common.model.Account;
import com.mailsystem.common.model.Draft;
import com.mailsystem.common.model.Folder;
import com.mailsystem.common.model.MailDetail;
import com.mailsystem.common.model.MailSummary;
import com.mailsystem.common.model.ScheduledMail;
import com.mailsystem.common.protocol.EventMessage;
import com.mailsystem.common.protocol.EventName;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Màn hình chính (bố cục 3 cột). Dữ liệu luôn lấy từ Server; EVENT real-time
 * chỉ là tín hiệu để tải lại. Nhiều EVENT dồn dập được gộp thành một lần tải
 * lại (debounce) để không gửi request thừa.
 */
public class MainController implements MailReader.Actions {

    /**
     * Các mục ở sidebar. DRAFTS, STARRED, SCHEDULED là "view" phía Client, không
     * phải thư mục trong DB. CUSTOM là thư mục người dùng tự tạo (nhiều cái, hiện
     * ở mục "Thư mục" bên dưới).
     */
    enum View {
        INBOX("Hộp thư đến", Icons.INBOX),
        STARRED("Có gắn sao", Icons.STAR_OUTLINE),
        SENT("Đã gửi", Icons.SENT),
        SCHEDULED("Đã lên lịch", Icons.SCHEDULE),
        DRAFTS("Thư nháp", Icons.DRAFT),
        TRASH("Thùng rác", Icons.TRASH),
        CUSTOM("Thư mục", Icons.FOLDER);

        final String title;
        final String icon;

        View(String title, String icon) {
            this.title = title;
            this.icon = icon;
        }
    }

    @FXML
    private StackPane root;
    @FXML
    private VBox sidebar;
    @FXML
    private VBox navBox;
    @FXML
    private Label footerLabel;
    @FXML
    private TextField searchField;
    @FXML
    private TextField fromFilterField;
    @FXML
    private Button clearSearchButton;
    @FXML
    private Label connectionLabel;
    @FXML
    private HBox connectionChip;
    @FXML
    private MenuButton profileMenu;
    @FXML
    private Tooltip profileTooltip;
    @FXML
    private SplitPane splitPane;
    @FXML
    private CheckBox selectAllCheck;
    @FXML
    private Label listTitle;
    @FXML
    private Label listCount;
    @FXML
    private Button emptyTrashButton;
    @FXML
    private HBox selectionBar;
    @FXML
    private Label selectionLabel;
    @FXML
    private Button bulkReadButton;
    @FXML
    private Button bulkUnreadButton;
    @FXML
    private Button bulkStarButton;
    @FXML
    private Tooltip bulkStarTooltip;
    @FXML
    private Button bulkTrashButton;
    @FXML
    private Button bulkRestoreButton;
    @FXML
    private Button bulkDeleteButton;
    @FXML
    private HBox errorBanner;
    @FXML
    private Label errorBannerLabel;
    @FXML
    private ListView<MailSummary> mailList;
    @FXML
    private ListView<Draft> draftList;
    @FXML
    private StackPane listStack;
    @FXML
    private StackPane listEmpty;
    @FXML
    private StackPane listLoading;
    @FXML
    private StackPane readerHost;

    private final MailApi api = MailApi.get();
    private final Map<View, NavButton> navButtons = new EnumMap<>(View.class);
    private final Map<View, Integer> folderIds = new EnumMap<>(View.class);
    private final ToggleGroup navGroup = new ToggleGroup();
    private final PauseTransition reloadDebounce = new PauseTransition(Duration.millis(180));
    private final Avatar profileAvatar = new Avatar(34);
    private MailReader reader;
    private View view = View.INBOX;
    private boolean searching;
    private int loadToken;
    private int openedEntryId;
    private boolean suppressSelectionEvents;
    /** Tìm trên mọi thư mục (chọn từ gợi ý) thay vì chỉ thư mục đang mở. */
    private boolean searchAllFolders;
    /** Thư cần tự mở sau khi kết quả tìm kiếm về (chọn thư từ gợi ý). */
    private int pendingOpenEntryId;
    /** Thư mục tự tạo đang mở (view == CUSTOM). */
    private Folder customFolder;
    private final List<Folder> customFolders = new ArrayList<>();
    private final VBox folderBox = new VBox(1);
    private final ListView<ScheduledMail> scheduledList = new ListView<>();
    private final Label reconnectLabel = new Label();
    private final HBox reconnectBanner = new HBox(10);
    private final MenuButton moveButton = new MenuButton();
    /** Định dạng dữ liệu kéo-thả thư (chỉ có nghĩa trong ứng dụng này). */
    private static final javafx.scene.input.DataFormat MAIL_ENTRIES =
            new javafx.scene.input.DataFormat("application/x-mailsystem-entries");

    @FXML
    public void initialize() {
        reader = new MailReader(this, root);
        readerHost.getChildren().add(reader);
        SplitPane.setResizableWithParent(readerHost, true);

        buildNavigation();
        buildFolderSection();
        buildProfileMenu();
        configureLists();
        buildReconnectBanner();
        buildMoveButton();
        reloadDebounce.setOnFinished(event -> {
            loadList(false);
            refreshCounts();
        });

        ServerConnection.getInstance().setEventListener(event -> Platform.runLater(() -> onEvent(event)));
        Connector.SecurityInfo security = ServerConnection.getInstance().securityInfo();
        boolean encrypted = security != null && security.encrypted();
        connectionLabel.setText(encrypted ? "TLS 1.3 · " + Session.serverAddress() : "KHÔNG mã hoá");
        Tooltip.install(connectionChip, new Tooltip(encrypted
                ? "Kết nối mã hoá " + security.protocol() + "\n" + security.cipherSuite()
                        + "\nDấu vân tay máy chủ:\n" + security.fingerprint()
                : "Kết nối không được mã hoá"));
        footerLabel.setText("Đã đăng nhập: " + (Session.account() == null ? "" : Session.account().getEmail()));

        installSearchSuggestions();

        root.sceneProperty().addListener((observable, old, scene) -> {
            if (scene != null) {
                installShortcuts(scene);
            }
        });
        selectView(View.INBOX);
        refreshCounts();
    }

    // ------------------------------------------------------------------ dựng giao diện

    private void buildNavigation() {
        for (View item : View.values()) {
            if (item == View.CUSTOM) {
                continue;
            }
            NavButton button = new NavButton(item.icon, item.title);
            button.setToggleGroup(navGroup);
            button.setOnAction(event -> selectView(item));
            navButtons.put(item, button);
            navBox.getChildren().add(button);
        }
        // Kéo thư thả vào Hộp thư đến (chuyển về) hoặc Thùng rác (xoá).
        installDropTarget(navButtons.get(View.INBOX), entryIds -> moveTo(entryIds, folderIds.get(View.INBOX), "Hộp thư đến"));
        installDropTarget(navButtons.get(View.TRASH), entryIds -> bulk(api.moveToTrash(entryIds), "Đã chuyển %d thư vào Thùng rác."));
        // Không cho bỏ chọn mục đang chọn bằng cách bấm lại vào nó.
        navGroup.selectedToggleProperty().addListener((observable, old, selected) -> {
            if (selected == null && old != null) {
                old.setSelected(true);
            }
        });
    }

    // ------------------------------------------------------------------ thư mục tự tạo

    private void buildFolderSection() {
        Label title = new Label("Thư mục");
        title.getStyleClass().add("sidebar-section");
        Button add = new Button();
        add.setGraphic(Icons.of(Icons.PLUS, 16));
        add.getStyleClass().add("icon-button");
        add.setTooltip(new Tooltip("Tạo thư mục mới"));
        add.setOnAction(event -> handleCreateFolder());
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        HBox header = new HBox(4, title, spacer, add);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("sidebar-section-row");
        int index = sidebar.getChildren().indexOf(navBox) + 1;
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(folderBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMaxHeight(260);
        sidebar.getChildren().addAll(index, List.of(header, scroll));
    }

    private void renderFolders() {
        List<javafx.scene.Node> old = new ArrayList<>(folderBox.getChildren());
        folderBox.getChildren().clear();
        for (Folder folder : customFolders) {
            NavButton button = new NavButton(Icons.FOLDER, folder.getFolderName());
            button.setToggleGroup(navGroup);
            button.setCount(folder.getUnreadCount());
            button.setSelected(view == View.CUSTOM && customFolder != null
                    && customFolder.getFolderId() == folder.getFolderId());
            button.setOnAction(event -> selectFolder(folder));
            MenuItem rename = new MenuItem("Đổi tên", Icons.of(Icons.RENAME, 16));
            rename.setOnAction(event -> renameFolder(folder));
            MenuItem delete = new MenuItem("Xoá thư mục", Icons.of(Icons.TRASH, 16));
            delete.setOnAction(event -> deleteFolder(folder));
            button.setContextMenu(new javafx.scene.control.ContextMenu(rename, delete));
            installDropTarget(button, entryIds -> moveTo(entryIds, folder.getFolderId(), folder.getFolderName()));
            folderBox.getChildren().add(button);
        }
        if (customFolders.isEmpty()) {
            Label hint = new Label("Bấm + để tạo thư mục, rồi kéo thư vào.");
            hint.getStyleClass().add("hint");
            hint.setWrapText(true);
            hint.setPadding(new Insets(2, 8, 4, 18));
            folderBox.getChildren().add(hint);
        }
        // Gỡ nút cũ khỏi ToggleGroup SAU khi nút mới đã được chọn (tránh nhóm mất lựa chọn).
        old.forEach(node -> {
            if (node instanceof NavButton button) {
                button.setToggleGroup(null);
            }
        });
        buildMoveButton();
    }

    private void selectFolder(Folder folder) {
        customFolder = folder;
        selectView(View.CUSTOM);
    }

    private void handleCreateFolder() {
        Dialogs.prompt(Navigator.owner(), "Thư mục mới", "Tên thư mục:", "", "Tạo").ifPresent(name ->
                Fx.then(api.createFolder(name), id -> {
                    Toast.success(root, "Đã tạo thư mục \"" + name + "\".");
                    refreshCounts();
                }, error -> Toast.error(root, error)));
    }

    private void renameFolder(Folder folder) {
        Dialogs.prompt(Navigator.owner(), "Đổi tên thư mục", "Tên mới:", folder.getFolderName(), "Lưu").ifPresent(name ->
                Fx.then(api.renameFolder(folder.getFolderId(), name), ignored -> {
                    if (customFolder != null && customFolder.getFolderId() == folder.getFolderId()) {
                        customFolder.setFolderName(name);
                        listTitle.setText(name);
                    }
                    refreshCounts();
                }, error -> Toast.error(root, error)));
    }

    private void deleteFolder(Folder folder) {
        if (!Dialogs.confirm(Navigator.owner(), "Xoá thư mục \"" + folder.getFolderName() + "\"?",
                "Các thư trong thư mục sẽ được chuyển vào Thùng rác (khôi phục được).", "Xoá thư mục", true)) {
            return;
        }
        Fx.then(api.deleteFolder(folder.getFolderId()), moved -> {
            Toast.success(root, moved > 0 ? "Đã xoá thư mục, " + moved + " thư đã vào Thùng rác." : "Đã xoá thư mục.");
            if (view == View.CUSTOM && customFolder != null && customFolder.getFolderId() == folder.getFolderId()) {
                selectView(View.INBOX);
            }
            refreshCounts();
        }, error -> Toast.error(root, error));
    }

    private void moveTo(List<Integer> entryIds, Integer folderId, String folderName) {
        if (folderId == null || entryIds.isEmpty()) {
            return;
        }
        bulk(api.moveMail(entryIds, folderId), "Đã chuyển %d thư tới " + folderName + ".");
    }

    /** Nút "Chuyển tới" trên thanh thao tác hàng loạt (thay cho kéo-thả khi dùng bàn phím). */
    private void buildMoveButton() {
        if (moveButton.getParent() == null) {
            moveButton.setGraphic(Icons.of(Icons.FOLDER_MOVE, 18));
            moveButton.getStyleClass().add("icon-button");
            moveButton.setTooltip(new Tooltip("Chuyển tới thư mục"));
            selectionBar.getChildren().add(selectionBar.getChildren().indexOf(bulkTrashButton), moveButton);
        }
        moveButton.getItems().clear();
        MenuItem inbox = new MenuItem(View.INBOX.title, Icons.of(Icons.INBOX, 16));
        inbox.setOnAction(event -> moveTo(ids(selectedMails()), folderIds.get(View.INBOX), View.INBOX.title));
        moveButton.getItems().add(inbox);
        if (!customFolders.isEmpty()) {
            moveButton.getItems().add(new SeparatorMenuItem());
        }
        for (Folder folder : customFolders) {
            MenuItem item = new MenuItem(folder.getFolderName(), Icons.of(Icons.FOLDER, 16));
            item.setOnAction(event -> moveTo(ids(selectedMails()), folder.getFolderId(), folder.getFolderName()));
            moveButton.getItems().add(item);
        }
        MenuItem create = new MenuItem("Thư mục mới...", Icons.of(Icons.FOLDER_PLUS, 16));
        create.setOnAction(event -> handleCreateFolder());
        moveButton.getItems().addAll(new SeparatorMenuItem(), create);
    }

    /** Mục sidebar nhận thư được kéo từ danh sách. */
    private void installDropTarget(NavButton target, java.util.function.Consumer<List<Integer>> onDrop) {
        target.setOnDragOver(event -> {
            if (event.getGestureSource() != target && event.getDragboard().hasContent(MAIL_ENTRIES)) {
                event.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
            }
            event.consume();
        });
        target.setOnDragEntered(event -> {
            if (event.getDragboard().hasContent(MAIL_ENTRIES)) {
                target.getStyleClass().add("drop-target");
            }
        });
        target.setOnDragExited(event -> target.getStyleClass().remove("drop-target"));
        target.setOnDragDropped(event -> {
            Object content = event.getDragboard().getContent(MAIL_ENTRIES);
            boolean done = false;
            if (content instanceof String text && !text.isBlank()) {
                List<Integer> entryIds = new ArrayList<>();
                for (String id : text.split(",")) {
                    entryIds.add(Integer.parseInt(id.trim()));
                }
                onDrop.accept(entryIds);
                done = true;
            }
            event.setDropCompleted(done);
            event.consume();
        });
    }

    /** Ô thư có thể kéo đi: kéo một thư chưa chọn thì chỉ kéo thư đó, ngược lại kéo cả nhóm đang chọn. */
    private MailCell mailCell(boolean showFolderTag) {
        MailCell cell = new MailCell(this::toggleStar, showFolderTag);
        cell.setOnDragDetected(event -> {
            MailSummary item = cell.getItem();
            if (item == null || view == View.TRASH) {
                return;
            }
            if (!mailList.getSelectionModel().getSelectedItems().contains(item)) {
                mailList.getSelectionModel().clearSelection();
                mailList.getSelectionModel().select(item);
            }
            List<Integer> entryIds = ids(selectedMails());
            javafx.scene.input.Dragboard board = cell.startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.put(MAIL_ENTRIES, entryIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
            board.setContent(content);
            Label ghost = new Label(selectedMails().size() == 1 ? "1 thư" : selectedMails().size() + " thư");
            ghost.setStyle("-fx-background-color: #0b57d0; -fx-text-fill: white; -fx-padding: 4 10; -fx-background-radius: 12;");
            new Scene(new StackPane(ghost));
            board.setDragView(ghost.snapshot(null, null));
            event.consume();
        });
        return cell;
    }

    // ------------------------------------------------------------------ kết nối lại

    private void buildReconnectBanner() {
        reconnectLabel.setWrapText(true);
        reconnectLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(reconnectLabel, javafx.scene.layout.Priority.ALWAYS);
        javafx.scene.control.ProgressIndicator spin = new javafx.scene.control.ProgressIndicator();
        spin.setMaxSize(16, 16);
        reconnectBanner.getChildren().setAll(Icons.of(Icons.WIFI_OFF, 18), reconnectLabel, spin);
        reconnectBanner.setAlignment(Pos.CENTER_LEFT);
        reconnectBanner.getStyleClass().add("banner-reconnect");
        reconnectBanner.setVisible(false);
        reconnectBanner.setManaged(false);
        javafx.scene.layout.BorderPane layout = (javafx.scene.layout.BorderPane) root.getChildren().get(0);
        javafx.scene.Node appBar = layout.getTop();
        layout.setTop(new VBox(appBar, reconnectBanner));

        Reconnector reconnector = Reconnector.get();
        javafx.beans.value.ChangeListener<String> onStatus = (obs, old, text) -> {
            boolean show = text != null;
            reconnectBanner.setVisible(show);
            reconnectBanner.setManaged(show);
            reconnectLabel.setText(text == null ? "" : text);
            connectionLabel.setText(show ? "Đang kết nối lại..." : connectionText());
        };
        javafx.beans.value.ChangeListener<Number> onReconnected = (obs, old, count) -> {
            Toast.success(root, "Đã kết nối lại tới máy chủ.");
            // Trong lúc mất mạng có thể đã lỡ EVENT: tải lại toàn bộ.
            reloadNow();
        };
        reconnector.statusProperty().addListener(onStatus);
        reconnector.reconnectsProperty().addListener(onReconnected);
        // Màn hình chính bị thay khi đăng xuất: gỡ listener để không giữ controller cũ.
        root.sceneProperty().addListener((obs, old, scene) -> {
            if (scene == null) {
                reconnector.statusProperty().removeListener(onStatus);
                reconnector.reconnectsProperty().removeListener(onReconnected);
            }
        });
        onStatus.changed(null, null, reconnector.statusProperty().get());
    }

    private String connectionText() {
        Connector.SecurityInfo security = ServerConnection.getInstance().securityInfo();
        boolean encrypted = security != null && security.encrypted();
        return encrypted ? "TLS 1.3 · " + Session.serverAddress() : "KHÔNG mã hoá";
    }

    private void buildProfileMenu() {
        profileMenu.setGraphic(profileAvatar);
        Avatar menuAvatar = new Avatar(44);
        Label name = new Label();
        name.getStyleClass().add("title-md");
        Label email = new Label();
        email.getStyleClass().add("text-muted");
        HBox header = new HBox(12, menuAvatar, new VBox(2, name, email));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(4, 4, 8, 4));
        CustomMenuItem headerItem = new CustomMenuItem(header, false);
        headerItem.getStyleClass().add("menu-header");

        MenuItem account = new MenuItem("Tài khoản & bảo mật", Icons.of(Icons.SHIELD, 18));
        account.setOnAction(event -> handleOpenAccount());
        MenuItem groups = new MenuItem("Nhóm thư", Icons.of(Icons.GROUPS, 18));
        groups.setOnAction(event -> handleOpenGroups());
        javafx.scene.control.CheckMenuItem dark = new javafx.scene.control.CheckMenuItem("Giao diện tối",
                Icons.of(Icons.DARK, 18));
        dark.setSelected(Session.darkMode());
        dark.setOnAction(event -> Navigator.setDarkMode(dark.isSelected()));
        javafx.scene.control.CheckMenuItem threaded = new javafx.scene.control.CheckMenuItem("Gộp thư theo hội thoại",
                Icons.of(Icons.THREAD, 18));
        threaded.setSelected(Session.threadedView());
        threaded.setOnAction(event -> {
            Session.setThreadedView(threaded.isSelected());
            selectView(view);
        });
        MenuItem logout = new MenuItem("Đăng xuất", Icons.of(Icons.LOGOUT, 18));
        logout.setOnAction(event -> handleLogout());
        MenuItem logoutAll = new MenuItem("Đăng xuất khỏi mọi thiết bị", Icons.of(Icons.DEVICES, 18));
        logoutAll.setOnAction(event -> handleLogoutAll());
        profileMenu.getItems().setAll(headerItem, new SeparatorMenuItem(), account, groups, new SeparatorMenuItem(),
                dark, threaded, new SeparatorMenuItem(), logout, logoutAll);

        Runnable refresh = () -> {
            Account current = Session.account();
            if (current == null) {
                return;
            }
            String display = current.getDisplayName() == null || current.getDisplayName().isBlank()
                    ? current.getEmail() : current.getDisplayName();
            profileAvatar.set(display, current.getEmail());
            menuAvatar.set(display, current.getEmail());
            name.setText(display);
            email.setText(current.getEmail());
            profileTooltip.setText(display + "\n" + current.getEmail());
        };
        refresh.run();
        Session.accountProperty().addListener((observable, old, value) -> refresh.run());
    }

    private void configureLists() {
        mailList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        mailList.setCellFactory(list -> mailCell(view == View.STARRED || searching));
        mailList.getSelectionModel().getSelectedItems()
                .addListener((ListChangeListener<MailSummary>) change -> onSelectionChanged());
        mailList.setOnKeyPressed(event -> {
            switch (event.getCode()) {
                case DELETE -> {
                    if (view == View.TRASH) {
                        handleBulkDeleteForever();
                    } else {
                        handleBulkTrash();
                    }
                }
                case S -> handleBulkStar();
                case U -> handleBulkUnread();
                default -> {
                }
            }
        });

        scheduledList.getStyleClass().add("mail-list");
        scheduledList.setVisible(false);
        scheduledList.setCellFactory(list -> new ScheduledCell(this::cancelScheduled));
        listStack.getChildren().add(2, scheduledList);

        draftList.setCellFactory(list -> new DraftCell());
        draftList.getSelectionModel().selectedItemProperty().addListener((observable, old, draft) -> {
            if (draft != null) {
                reader.showDraft(draft);
            } else if (view == View.DRAFTS) {
                reader.showEmpty();
            }
        });
        draftList.setOnMouseClicked(event -> {
            Draft draft = draftList.getSelectionModel().getSelectedItem();
            if (draft != null && event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                editDraft(draft);
            }
        });
        draftList.setOnKeyPressed(event -> {
            Draft draft = draftList.getSelectionModel().getSelectedItem();
            if (draft == null) {
                return;
            }
            if (event.getCode() == KeyCode.ENTER) {
                editDraft(draft);
            } else if (event.getCode() == KeyCode.DELETE) {
                deleteDraft(draft);
            }
        });
    }

    /**
     * Gợi ý ở ô tìm thư: ô trống → tìm kiếm gần đây; gõ ≥ 2 ký tự → hành động
     * "tìm ở mọi thư mục", tối đa 5 thư khớp (bấm là mở ngay) và người gửi khớp.
     */
    private void installSearchSuggestions() {
        AutoComplete.attach(searchField, 0, false, query -> {
            if (query.isEmpty()) {
                List<AutoComplete.Suggestion> recent = new ArrayList<>();
                for (String old : Session.recentSearches()) {
                    recent.add(new AutoComplete.Suggestion("Tìm kiếm gần đây", Icons.HISTORY, old, null, () -> {
                        searchField.setText(old);
                        handleSearch();
                    }));
                }
                return CompletableFuture.completedFuture(recent);
            }
            if (query.length() < 2) {
                return CompletableFuture.completedFuture(List.of());
            }
            return api.search(null, query, "").thenCombine(api.suggestContacts(query, false, 3), (mails, people) -> {
                List<AutoComplete.Suggestion> items = new ArrayList<>();
                items.add(new AutoComplete.Suggestion("", Icons.SEARCH, "Tìm \"" + query + "\" trong mọi thư mục",
                        mails.size() + " thư khớp", () -> searchEverywhere(query, 0)));
                mails.stream().limit(5).forEach(mail -> items.add(new AutoComplete.Suggestion("Thư", Icons.MAIL_SEARCH,
                        mail.getSubject() == null || mail.getSubject().isBlank() ? "(Không có tiêu đề)" : mail.getSubject(),
                        senderName(mail) + "  ·  " + Formats.shortDate(mail.getSentAt()),
                        () -> searchEverywhere(query, mail.getEntryId()))));
                people.forEach(person -> items.add(new AutoComplete.Suggestion("Thư từ người gửi", Icons.PERSON_SEARCH,
                        person.getDisplayName() == null || person.getDisplayName().isBlank()
                                ? person.getEmail() : person.getDisplayName(),
                        person.getEmail(), () -> {
                            searchField.clear();
                            fromFilterField.setText(person.getEmail());
                            searchEverywhere("", 0);
                        })));
                return items;
            });
        });
        ContactSuggestions.forSinglePerson(fromFilterField, this::handleSearch);
    }

    private static String senderName(MailSummary mail) {
        return mail.getSenderName() == null || mail.getSenderName().isBlank() ? mail.getSenderEmail() : mail.getSenderName();
    }

    /** Tìm trên mọi thư mục; openEntryId > 0 thì tự mở thư đó khi kết quả về. */
    private void searchEverywhere(String keyword, int openEntryId) {
        searchField.setText(keyword);
        searchAllFolders = true;
        pendingOpenEntryId = openEntryId;
        handleSearch();
    }

    private void installShortcuts(Scene scene) {
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN), this::handleCompose);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN), () -> {
            searchField.requestFocus();
            searchField.selectAll();
        });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F5), this::handleRefresh);
        searchField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                handleClearSearch();
            }
        });
        // Phím tắt kiểu Gmail khi đang đứng ở danh sách thư: R trả lời, A trả lời tất cả, F chuyển tiếp.
        mailList.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            MailDetail opened = currentDetail;
            if (opened == null || event.isShortcutDown()) {
                return;
            }
            if (event.getCode() == KeyCode.R) {
                reply(opened);
            } else if (event.getCode() == KeyCode.A) {
                replyAll(opened);
            } else if (event.getCode() == KeyCode.F) {
                forward(opened);
            }
        });
    }

    // ------------------------------------------------------------------ điều hướng & tải dữ liệu

    private void selectView(View target) {
        view = target;
        if (target != View.CUSTOM) {
            navButtons.get(target).setSelected(true);
        } else {
            renderFolders();
        }
        searching = false;
        searchAllFolders = false;
        pendingOpenEntryId = 0;
        searchField.clear();
        fromFilterField.clear();
        clearSearchButton.setVisible(false);
        clearSearchButton.setManaged(false);
        listTitle.setText(target.title);
        if (target == View.CUSTOM && customFolder != null) {
            listTitle.setText(customFolder.getFolderName());
        } else {
            customFolder = null;
        }
        boolean drafts = target == View.DRAFTS;
        boolean scheduled = target == View.SCHEDULED;
        mailList.setVisible(!drafts && !scheduled);
        draftList.setVisible(drafts);
        scheduledList.setVisible(scheduled);
        selectAllCheck.setVisible(!drafts && !scheduled);
        selectAllCheck.setManaged(!drafts && !scheduled);
        emptyTrashButton.setVisible(target == View.TRASH);
        emptyTrashButton.setManaged(target == View.TRASH);
        mailList.setCellFactory(list -> mailCell(view == View.STARRED || searching));
        mailList.getItems().clear();
        draftList.getItems().clear();
        scheduledList.getItems().clear();
        openedEntryId = 0;
        currentDetail = null;
        reader.showEmpty();
        updateWindowTitle();
        loadList(true);
    }

    /** Tải danh sách của view hiện tại. loadToken bỏ qua kết quả của request cũ khi người dùng đã chuyển view. */
    private void loadList(boolean showSpinner) {
        int token = ++loadToken;
        hideError();
        if (showSpinner) {
            listLoading.setVisible(true);
        }
        if (view == View.DRAFTS) {
            Fx.then(api.drafts(), drafts -> {
                if (token != loadToken) {
                    return;
                }
                listLoading.setVisible(false);
                Integer selected = draftList.getSelectionModel().getSelectedItem() == null
                        ? null : draftList.getSelectionModel().getSelectedItem().getDraftId();
                draftList.getItems().setAll(drafts);
                navButtons.get(View.DRAFTS).setCount(drafts.size());
                if (selected != null) {
                    drafts.stream().filter(draft -> draft.getDraftId() == selected).findFirst()
                            .ifPresent(draft -> draftList.getSelectionModel().select(draft));
                }
                listCount.setText(drafts.isEmpty() ? "" : drafts.size() + " thư");
                showEmptyIfNeeded(drafts.isEmpty());
            }, error -> showLoadError(token, error));
            return;
        }
        if (view == View.SCHEDULED) {
            Fx.then(api.scheduled(), mails -> {
                if (token != loadToken) {
                    return;
                }
                listLoading.setVisible(false);
                scheduledList.getItems().setAll(mails);
                navButtons.get(View.SCHEDULED).setCount(mails.size());
                listCount.setText(mails.isEmpty() ? "" : mails.size() + " thư chờ gửi");
                showEmptyIfNeeded(mails.isEmpty());
            }, error -> showLoadError(token, error));
            return;
        }

        CompletableFuture<List<MailSummary>> request;
        if (searching) {
            Integer folderId = view == View.STARRED || searchAllFolders ? null
                    : view == View.CUSTOM && customFolder != null ? Integer.valueOf(customFolder.getFolderId()) : folderIds.get(view);
            request = api.search(folderId, searchField.getText().trim(), fromFilterField.getText().trim());
        } else if (view == View.STARRED) {
            request = api.starred();
        } else if (view == View.CUSTOM) {
            if (customFolder == null) {
                return;
            }
            request = api.mailList(customFolder.getFolderId(), Session.threadedView());
        } else {
            Integer folderId = folderIds.get(view);
            if (folderId == null) {
                // Chưa biết id thư mục (lần đầu vào): lấy danh sách thư mục trước.
                Fx.then(api.folders(), folders -> {
                    applyFolders(folders);
                    if (folderIds.get(view) != null && token == loadToken) {
                        loadList(showSpinner);
                    }
                }, error -> showLoadError(token, error));
                return;
            }
            // Gộp hội thoại ở Hộp thư đến / Đã gửi; Thùng rác luôn hiện từng thư.
            request = api.mailList(folderId, Session.threadedView() && view != View.TRASH);
        }
        Fx.then(request, mails -> {
            if (token != loadToken) {
                return;
            }
            listLoading.setVisible(false);
            applyMails(mails);
        }, error -> showLoadError(token, error));
    }

    /** Thay danh sách nhưng giữ nguyên các thư đang chọn (EVENT có thể tới đúng lúc đang chọn). */
    private void applyMails(List<MailSummary> mails) {
        Set<Integer> selected = mailList.getSelectionModel().getSelectedItems().stream()
                .map(MailSummary::getEntryId).collect(Collectors.toSet());
        suppressSelectionEvents = true;
        mailList.getItems().setAll(mails);
        for (int i = 0; i < mails.size(); i++) {
            if (selected.contains(mails.get(i).getEntryId())) {
                mailList.getSelectionModel().select(i);
            }
        }
        if (pendingOpenEntryId > 0) {
            int target = pendingOpenEntryId;
            pendingOpenEntryId = 0;
            mailList.getSelectionModel().clearSelection();
            for (int i = 0; i < mails.size(); i++) {
                if (mails.get(i).getEntryId() == target) {
                    mailList.getSelectionModel().select(i);
                    mailList.scrollTo(i);
                }
            }
        }
        suppressSelectionEvents = false;
        onSelectionChanged();
        updateListCount();
        showEmptyIfNeeded(mails.isEmpty());
    }

    private void updateListCount() {
        List<MailSummary> mails = mailList.getItems();
        long unread = mails.stream().filter(mail -> !mail.isRead()).count();
        listCount.setText(mails.isEmpty() ? ""
                : mails.size() + " thư" + (unread > 0 && view != View.TRASH ? " · " + unread + " chưa đọc" : ""));
    }

    private void showEmptyIfNeeded(boolean empty) {
        listEmpty.setVisible(empty);
        if (!empty) {
            return;
        }
        EmptyState state = new EmptyState();
        if (searching) {
            state.show(Icons.SEARCH, "Không tìm thấy thư phù hợp", "Thử từ khoá khác hoặc bỏ bộ lọc người gửi.");
        } else {
            switch (view) {
                case INBOX -> state.show(Icons.INBOX, "Hộp thư đến trống", "Thư mới sẽ xuất hiện ngay khi có người gửi cho bạn.");
                case STARRED -> state.show(Icons.STAR_OUTLINE, "Chưa có thư gắn sao", "Gắn sao để đánh dấu thư quan trọng (phím S).");
                case SENT -> state.show(Icons.SENT, "Chưa gửi thư nào", "Bấm Soạn thư (Ctrl+N) để gửi thư đầu tiên.");
                case DRAFTS -> state.show(Icons.DRAFT, "Không có thư nháp", "Thư đang soạn sẽ được tự lưu vào đây.");
                case SCHEDULED -> state.show(Icons.SCHEDULE, "Không có thư hẹn giờ",
                        "Trong khung soạn thư, bấm biểu tượng lịch cạnh nút Gửi để hẹn giờ gửi.");
                case CUSTOM -> state.show(Icons.FOLDER, "Thư mục trống", "Kéo thư từ danh sách thả vào thư mục ở thanh bên.");
                case TRASH -> state.show(Icons.TRASH, "Thùng rác trống", "Thư bạn xoá sẽ nằm ở đây cho tới khi xoá vĩnh viễn.");
            }
        }
        listEmpty.getChildren().setAll(state);
    }

    private void showLoadError(int token, String message) {
        if (token != loadToken) {
            return;
        }
        listLoading.setVisible(false);
        errorBannerLabel.setText("Không tải được dữ liệu: " + message);
        errorBanner.setVisible(true);
        errorBanner.setManaged(true);
    }

    private void hideError() {
        errorBanner.setVisible(false);
        errorBanner.setManaged(false);
    }

    /** Cập nhật số thư chưa đọc / số thư nháp ở sidebar và tiêu đề cửa sổ. */
    private void refreshCounts() {
        Fx.then(api.folders(), this::applyFolders, error -> {
        });
        if (view != View.DRAFTS) {
            Fx.then(api.drafts(), drafts -> navButtons.get(View.DRAFTS).setCount(drafts.size()), error -> {
            });
        }
        if (view != View.SCHEDULED) {
            Fx.then(api.scheduled(), mails -> navButtons.get(View.SCHEDULED).setCount(mails.size()), error -> {
            });
        }
    }

    private int inboxUnread;

    private void applyFolders(List<Folder> folders) {
        List<Folder> custom = folders.stream().filter(folder -> "CUSTOM".equals(folder.getFolderType()))
                .sorted(java.util.Comparator.comparing(folder -> folder.getFolderName().toLowerCase()))
                .collect(Collectors.toList());
        customFolders.clear();
        customFolders.addAll(custom);
        if (customFolder != null) {
            // Thư mục đang mở có thể vừa bị đổi tên / xoá ở máy khác.
            int openId = customFolder.getFolderId();
            customFolder = custom.stream().filter(folder -> folder.getFolderId() == openId).findFirst().orElse(null);
            if (customFolder == null && view == View.CUSTOM) {
                selectView(View.INBOX);
            } else if (customFolder != null && !searching) {
                listTitle.setText(customFolder.getFolderName());
            }
        }
        renderFolders();
        for (Folder folder : folders) {
            View target = switch (folder.getFolderType()) {
                case "INBOX" -> View.INBOX;
                case "SENT" -> View.SENT;
                case "TRASH" -> View.TRASH;
                default -> null;
            };
            if (target != null) {
                folderIds.put(target, folder.getFolderId());
                if (target == View.INBOX) {
                    inboxUnread = folder.getUnreadCount();
                    navButtons.get(View.INBOX).setCount(inboxUnread);
                }
            }
        }
        updateWindowTitle();
        DesktopNotifier.updateTooltip(inboxUnread);
    }

    private void updateWindowTitle() {
        String unread = inboxUnread > 0 ? " (" + inboxUnread + ")" : "";
        String title = view == View.CUSTOM && customFolder != null ? customFolder.getFolderName() : view.title;
        Navigator.primaryStage().setTitle(title + unread + " – Mail System");
    }

    // ------------------------------------------------------------------ chọn & đọc thư

    private MailDetail currentDetail;

    private void onSelectionChanged() {
        if (suppressSelectionEvents) {
            return;
        }
        List<MailSummary> selected = selectedMails();
        int total = mailList.getItems().size();
        selectAllCheck.setSelected(!selected.isEmpty() && selected.size() == total);
        selectAllCheck.setIndeterminate(!selected.isEmpty() && selected.size() < total);
        selectAllCheck.setDisable(total == 0);
        boolean trash = view == View.TRASH;
        selectionBar.setVisible(!selected.isEmpty());
        selectionBar.setManaged(!selected.isEmpty());
        selectionLabel.setText("Đã chọn " + selected.size());
        setShown(bulkReadButton, !trash);
        setShown(bulkUnreadButton, !trash);
        setShown(bulkTrashButton, !trash);
        setShown(bulkRestoreButton, trash);
        setShown(bulkDeleteButton, trash);
        boolean allStarred = !selected.isEmpty() && selected.stream().allMatch(MailSummary::isStarred);
        bulkStarButton.setGraphic(Icons.of(allStarred ? Icons.STAR : Icons.STAR_OUTLINE, 18));
        bulkStarTooltip.setText(allStarred ? "Bỏ gắn sao" : "Gắn sao");

        if (selected.size() == 1) {
            openMail(selected.get(0));
        } else if (selected.size() > 1) {
            openedEntryId = 0;
            currentDetail = null;
            reader.showSelection(selected.size());
        } else if (openedEntryId != 0 || currentDetail == null) {
            openedEntryId = 0;
            currentDetail = null;
            reader.showEmpty();
        }
    }

    private void openMail(MailSummary summary) {
        if (summary.getEntryId() == openedEntryId) {
            return; // đã mở thư này (VD: danh sách vừa tải lại do EVENT)
        }
        openedEntryId = summary.getEntryId();
        reader.showLoading();
        if (summary.getThreadCount() > 1) {
            // Hội thoại: tải mọi thư (Server đánh dấu đã đọc từng thư khi trả về).
            Fx.then(api.thread(summary.getThreadId()), details -> {
                if (openedEntryId != summary.getEntryId() || details.isEmpty()) {
                    return;
                }
                currentDetail = details.get(details.size() - 1);
                reader.showThread(details, Session.account() == null ? "" : Session.account().getEmail());
                if (!summary.isRead()) {
                    summary.setRead(true);
                    mailList.refresh();
                    updateListCount();
                    refreshCounts();
                }
            }, error -> {
                openedEntryId = 0;
                reader.showError(error);
            });
            return;
        }
        Fx.then(api.detail(summary.getMailId(), summary.getEntryId()), detail -> {
            if (openedEntryId != summary.getEntryId()) {
                return;
            }
            currentDetail = detail;
            reader.showMail(detail, Session.account() == null ? "" : Session.account().getEmail());
            if (!summary.isRead() && !"TRASH".equals(detail.getFolderType())) {
                summary.setRead(true); // Server đã tự đánh dấu đã đọc khi mở thư
                mailList.refresh();
                updateListCount();
                refreshCounts();
            }
        }, error -> {
            openedEntryId = 0;
            reader.showError(error);
        });
    }

    private List<MailSummary> selectedMails() {
        return new ArrayList<>(mailList.getSelectionModel().getSelectedItems());
    }

    /** Mọi entryId của các dòng đang chọn (một dòng hội thoại = mọi thư trong hội thoại). */
    private static List<Integer> ids(List<MailSummary> mails) {
        return mails.stream().flatMap(mail -> mail.getThreadEntryIds().stream()).distinct().collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ thao tác hàng loạt

    @FXML
    public void handleToggleSelectAll() {
        if (selectAllCheck.isSelected()) {
            mailList.getSelectionModel().selectAll();
        } else {
            mailList.getSelectionModel().clearSelection();
        }
    }

    @FXML
    public void handleBulkRead() {
        bulk(api.markRead(ids(selectedMails()), true), "Đã đánh dấu %d thư là đã đọc.");
    }

    @FXML
    public void handleBulkUnread() {
        if (view == View.TRASH || selectedMails().isEmpty()) {
            return;
        }
        openedEntryId = -1; // không tự mở lại (mở thư = đánh dấu đã đọc)
        bulk(api.markRead(ids(selectedMails()), false), "Đã đánh dấu %d thư là chưa đọc.");
        mailList.getSelectionModel().clearSelection();
    }

    @FXML
    public void handleBulkStar() {
        List<MailSummary> selected = selectedMails();
        if (selected.isEmpty()) {
            return;
        }
        boolean star = !selected.stream().allMatch(MailSummary::isStarred);
        bulk(api.setStarred(ids(selected), star), star ? "Đã gắn sao %d thư." : "Đã bỏ gắn sao %d thư.");
    }

    @FXML
    public void handleBulkTrash() {
        List<MailSummary> selected = selectedMails();
        if (selected.isEmpty() || view == View.TRASH) {
            return;
        }
        bulk(api.moveToTrash(ids(selected)), "Đã chuyển %d thư vào Thùng rác.");
    }

    @FXML
    public void handleBulkRestore() {
        List<MailSummary> selected = selectedMails();
        if (!selected.isEmpty()) {
            bulk(api.restore(ids(selected)), "Đã khôi phục %d thư.");
        }
    }

    @FXML
    public void handleBulkDeleteForever() {
        List<MailSummary> selected = selectedMails();
        if (selected.isEmpty() || view != View.TRASH) {
            return;
        }
        if (Dialogs.confirm(Navigator.owner(), "Xoá vĩnh viễn " + selected.size() + " thư?",
                "Thư sẽ bị xoá khỏi tài khoản của bạn và không thể khôi phục.", "Xoá vĩnh viễn", true)) {
            bulk(api.deleteForever(ids(selected)), "Đã xoá vĩnh viễn %d thư.");
        }
    }

    @FXML
    public void handleEmptyTrash() {
        if (mailList.getItems().isEmpty()) {
            Toast.info(root, "Thùng rác đã trống.");
            return;
        }
        if (Dialogs.confirm(Navigator.owner(), "Dọn sạch Thùng rác?",
                "Tất cả " + mailList.getItems().size() + " thư trong Thùng rác sẽ bị xoá vĩnh viễn.", "Dọn sạch", true)) {
            bulk(api.emptyTrash(), "Đã xoá vĩnh viễn %d thư.");
        }
    }

    private void bulk(CompletableFuture<Integer> request, String successFormat) {
        Fx.then(request, processed -> {
            Toast.success(root, String.format(successFormat, processed));
            mailList.getSelectionModel().clearSelection();
            reloadNow();
        }, error -> Toast.error(root, error));
    }

    private void toggleStar(MailSummary mail) {
        boolean star = !mail.isStarred();
        Fx.then(api.setStarred(mail.getThreadEntryIds(), star), processed -> {
            mail.setStarred(star);
            mailList.refresh();
            if (currentDetail != null && currentDetail.getEntryId() == mail.getEntryId()) {
                currentDetail.setStarred(star);
                reader.showMail(currentDetail, Session.account().getEmail());
            }
            if (view == View.STARRED && !star) {
                reloadNow();
            }
        }, error -> Toast.error(root, error));
    }

    private void reloadNow() {
        reloadDebounce.stop();
        loadList(false);
        refreshCounts();
    }

    // ------------------------------------------------------------------ MailReader.Actions

    @Override
    public void reply(MailDetail mail) {
        ComposeController.open(ComposeController.Request.reply(mail, false));
    }

    @Override
    public void replyAll(MailDetail mail) {
        ComposeController.open(ComposeController.Request.reply(mail, true));
    }

    @Override
    public void forward(MailDetail mail) {
        ComposeController.open(ComposeController.Request.forward(mail));
    }

    @Override
    public void toggleStar(MailDetail mail) {
        mailList.getItems().stream().filter(item -> item.getEntryId() == mail.getEntryId()).findFirst()
                .ifPresentOrElse(this::toggleStar, () -> Fx.then(api.setStarred(List.of(mail.getEntryId()),
                        !mail.isStarred()), processed -> reloadNow(), error -> Toast.error(root, error)));
    }

    @Override
    public void markUnread(MailDetail mail) {
        openedEntryId = -1;
        Fx.then(api.markRead(List.of(mail.getEntryId()), false), processed -> {
            Toast.success(root, "Đã đánh dấu là chưa đọc.");
            mailList.getSelectionModel().clearSelection();
            currentDetail = null;
            reader.showEmpty();
            reloadNow();
        }, error -> Toast.error(root, error));
    }

    @Override
    public void trash(MailDetail mail) {
        Fx.then(api.moveToTrash(List.of(mail.getEntryId())), processed -> {
            Toast.success(root, "Đã chuyển thư vào Thùng rác.");
            currentDetail = null;
            reader.showEmpty();
            reloadNow();
        }, error -> Toast.error(root, error));
    }

    @Override
    public void restore(MailDetail mail) {
        Fx.then(api.restore(List.of(mail.getEntryId())), processed -> {
            Toast.success(root, "Đã khôi phục thư.");
            currentDetail = null;
            reader.showEmpty();
            reloadNow();
        }, error -> Toast.error(root, error));
    }

    @Override
    public void deleteForever(MailDetail mail) {
        if (!Dialogs.confirm(Navigator.owner(), "Xoá vĩnh viễn thư này?",
                "Thư sẽ bị xoá khỏi tài khoản của bạn và không thể khôi phục.", "Xoá vĩnh viễn", true)) {
            return;
        }
        Fx.then(api.deleteForever(List.of(mail.getEntryId())), processed -> {
            Toast.success(root, "Đã xoá vĩnh viễn thư.");
            currentDetail = null;
            reader.showEmpty();
            reloadNow();
        }, error -> Toast.error(root, error));
    }

    @Override
    public void editDraft(Draft draft) {
        ComposeController.open(ComposeController.Request.draft(draft));
    }

    @Override
    public void deleteDraft(Draft draft) {
        if (!Dialogs.confirm(Navigator.owner(), "Xoá thư nháp?", "Bản nháp này sẽ bị xoá.", "Xoá", true)) {
            return;
        }
        Fx.then(api.deleteDrafts(List.of(draft.getDraftId())), deleted -> {
            Toast.success(root, "Đã xoá thư nháp.");
            reader.showEmpty();
            reloadNow();
        }, error -> Toast.error(root, error));
    }

    /** Huỷ thư hẹn giờ và mở lại khung soạn với nội dung đó. */
    private void cancelScheduled(ScheduledMail mail) {
        Fx.then(api.cancelScheduled(mail.getOutboxId()), data -> {
            int files = data.has("attachmentCount") ? data.get("attachmentCount").getAsInt() : 0;
            ComposeController.open(ComposeController.Request.reopened(data, List.of(), List.of()));
            Toast.info(root, files > 0 ? "Đã huỷ hẹn giờ. Hãy đính kèm lại " + files + " tệp trước khi gửi."
                    : "Đã huỷ hẹn giờ, thư được mở lại để sửa.");
            reloadNow();
        }, error -> Toast.error(root, error));
    }

    // ------------------------------------------------------------------ EVENT real-time

    private void onEvent(EventMessage event) {
        EventName name;
        try {
            name = EventName.valueOf(event.getEventName());
        } catch (RuntimeException unknown) {
            return; // Server mới hơn gửi EVENT lạ: bỏ qua thay vì lỗi
        }
        switch (name) {
            case NEW_MAIL -> {
                notifyNewMail(event);
                reloadDebounce.playFromStart();
            }
            case MAIL_READ_UPDATED, MAIL_STARRED, MAIL_DELETED, MAIL_RESTORED, MAIL_MOVED -> reloadDebounce.playFromStart();
            case FOLDERS_UPDATED -> refreshCounts();
            case SCHEDULED_UPDATED -> {
                if (view == View.SCHEDULED) {
                    loadList(false);
                } else {
                    refreshCounts();
                }
            }
            case SCHEDULED_FAILED -> {
                String reason = text(event, "reason", "Không gửi được thư hẹn giờ.");
                Toast.error(root, "Thư hẹn giờ \"" + text(event, "subject", "") + "\" không gửi được: " + reason
                        + " Thư đã được chuyển về Thư nháp.");
                refreshCounts();
            }
            case READ_RECEIPT -> onReadReceipt(event);
            case GROUP_UPDATED -> {
                // Được thêm / bị xoá / nhóm bị xoá: thư thông báo đi kèm đã hiện toast + thông báo desktop
                // qua NEW_MAIL. Thành viên tự rời nhóm thì chỉ báo cho chủ nhóm ở đây.
                if ("MEMBER_LEFT".equals(text(event, "action", ""))) {
                    Toast.info(root, text(event, "actorName", "Một thành viên") + " đã rời nhóm \""
                            + text(event, "groupName", "") + "\".");
                }
                GroupsController.refreshIfOpen();
            }
            case DRAFTS_UPDATED -> {
                if (view == View.DRAFTS) {
                    loadList(false);
                } else {
                    refreshCounts();
                }
            }
            case PROFILE_UPDATED -> {
                if (event.getData() != null && event.getData().isJsonObject()
                        && event.getData().getAsJsonObject().has("displayName")) {
                    Session.updateDisplayName(event.getData().getAsJsonObject().get("displayName").getAsString());
                }
            }
            case SESSION_REVOKED -> {
                String reason = event.getData() != null && event.getData().isJsonObject()
                        && event.getData().getAsJsonObject().has("reason")
                        ? event.getData().getAsJsonObject().get("reason").getAsString()
                        : "Phiên đăng nhập đã bị thu hồi.";
                Reconnector.get().cancel();
                DesktopNotifier.shutdown();
                Navigator.showLogin(reason);
            }
        }
    }

    /** Người nhận vừa mở thư mình gửi: báo nhẹ, và cập nhật khung đọc nếu đang xem đúng thư đó. */
    private void onReadReceipt(EventMessage event) {
        String reader = text(event, "readerName", "");
        if (reader.isBlank()) {
            reader = text(event, "readerEmail", "Người nhận");
        }
        String subject = text(event, "subject", "");
        Toast.info(root, reader + " đã đọc thư \"" + (subject.isBlank() ? "(Không có tiêu đề)" : subject) + "\".");
        int mailId = event.getData().getAsJsonObject().has("mailId")
                ? event.getData().getAsJsonObject().get("mailId").getAsInt() : 0;
        if (currentDetail != null && currentDetail.getMailId() == mailId) {
            int entryId = openedEntryId;
            openedEntryId = 0;
            mailList.getItems().stream().filter(item -> item.getEntryId() == entryId).findFirst().ifPresent(this::openMail);
        }
    }

    private static String text(EventMessage event, String field, String fallback) {
        if (event.getData() == null || !event.getData().isJsonObject() || !event.getData().getAsJsonObject().has(field)) {
            return fallback;
        }
        return event.getData().getAsJsonObject().get(field).getAsString();
    }

    private void notifyNewMail(EventMessage event) {
        if (event.getData() == null || !event.getData().isJsonObject()) {
            return;
        }
        var data = event.getData().getAsJsonObject();
        String senderEmail = data.has("senderEmail") ? data.get("senderEmail").getAsString() : "";
        Account me = Session.account();
        if (me != null && senderEmail.equalsIgnoreCase(me.getEmail())) {
            return; // thư do chính mình gửi (cập nhật Đã gửi), không thông báo
        }
        String sender = data.has("senderName") && !data.get("senderName").getAsString().isBlank()
                ? data.get("senderName").getAsString() : senderEmail;
        String subject = data.has("subject") ? data.get("subject").getAsString() : "";
        boolean focused = Navigator.primaryStage().isFocused();
        if (focused || !DesktopNotifier.notifyNewMail(sender, subject)) {
            Toast.info(root, "Thư mới từ " + sender + ": " + subject);
        }
    }

    // ------------------------------------------------------------------ thanh trên cùng

    @FXML
    public void handleSearch() {
        String keyword = searchField.getText() == null ? "" : searchField.getText().trim();
        String from = fromFilterField.getText() == null ? "" : fromFilterField.getText().trim();
        if (keyword.isEmpty() && from.isEmpty()) {
            handleClearSearch();
            return;
        }
        if (view == View.DRAFTS || view == View.SCHEDULED) {
            boolean everywhere = searchAllFolders;
            int pending = pendingOpenEntryId;
            selectView(View.INBOX);
            searchField.setText(keyword);
            fromFilterField.setText(from);
            searchAllFolders = everywhere;
            pendingOpenEntryId = pending;
        }
        Session.addRecentSearch(keyword);
        searching = true;
        clearSearchButton.setVisible(true);
        clearSearchButton.setManaged(true);
        listTitle.setText("Kết quả tìm kiếm" + (searchAllFolders ? " · Mọi thư mục"
                : view == View.STARRED ? "" : " · " + (view == View.CUSTOM && customFolder != null
                        ? customFolder.getFolderName() : view.title)));
        mailList.setCellFactory(list -> mailCell(true));
        mailList.getSelectionModel().clearSelection();
        loadList(true);
    }

    @FXML
    public void handleClearSearch() {
        if (!searching && searchField.getText().isEmpty() && fromFilterField.getText().isEmpty()) {
            return;
        }
        selectView(view);
    }

    @FXML
    public void handleRefresh() {
        reloadNow();
    }

    @FXML
    public void handleCompose() {
        ComposeController.open(ComposeController.Request.blank());
    }

    @FXML
    public void handleToggleSidebar() {
        boolean show = !sidebar.isVisible();
        sidebar.setVisible(show);
        sidebar.setManaged(show);
    }

    @FXML
    public void handleOpenGroups() {
        Navigator.openWindow(Navigator.GROUPS, "Nhóm thư – Mail System", 860, 580, false, null);
    }

    public void handleOpenAccount() {
        Navigator.openWindow(Navigator.ACCOUNT, "Tài khoản & bảo mật – Mail System", 720, 760, true, null);
    }

    private void handleLogout() {
        Reconnector.get().cancel();
        Fx.then(api.logout(), ignored -> afterLogout(null), error -> afterLogout(null));
    }

    private void handleLogoutAll() {
        if (!Dialogs.confirm(Navigator.owner(), "Đăng xuất khỏi mọi thiết bị?",
                "Mọi phiên đăng nhập của tài khoản này trên các máy khác sẽ bị thu hồi ngay lập tức.",
                "Đăng xuất tất cả", true)) {
            return;
        }
        Fx.then(api.logoutAll(), revoked -> afterLogout("Đã đăng xuất khỏi mọi thiết bị ("
                + revoked + " phiên khác đã bị thu hồi)."), error -> Toast.error(root, error));
    }

    private void afterLogout(String notice) {
        Reconnector.get().cancel();
        DesktopNotifier.shutdown();
        Navigator.showLogin(notice);
    }

    private static void setShown(Button button, boolean shown) {
        button.setVisible(shown);
        button.setManaged(shown);
    }
}
