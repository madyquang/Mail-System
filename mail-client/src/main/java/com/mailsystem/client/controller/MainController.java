// Gói "controller": xử lý sự kiện của từng màn hình (gắn với tệp .fxml).
package com.mailsystem.client.controller;

// Gọi Server, điều hướng, kết nối lại, phiên, kết nối, thông báo hệ thống, các thành phần giao diện tự viết.
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
import com.mailsystem.client.ui.Motion;
import com.mailsystem.client.ui.NavButton;
import com.mailsystem.client.ui.ScheduledCell;
import com.mailsystem.client.ui.Toast;
// DTO và tên EVENT dùng chung; JavaFX.
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

// Danh sách, EnumMap, Map, Set, CompletableFuture, gom luồng dữ liệu.
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

        // Tên hiển thị và icon của mục.
        final String title;
        final String icon;

        View(String title, String icon) {
            this.title = title;
            this.icon = icon;
        }
    }

    // Các thành phần giao diện; @FXML: JavaFX tự gán theo fx:id trong main.fxml.
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
    private HBox titleRow;
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

    // Gọi Server; nút sidebar theo mục; folderId của các mục là thư mục thật (INBOX, SENT, TRASH).
    private final MailApi api = MailApi.get();
    private final Map<View, NavButton> navButtons = new EnumMap<>(View.class);
    private final Map<View, Integer> folderIds = new EnumMap<>(View.class);
    // Nhóm nút sidebar (chỉ chọn một); bộ gộp tải lại 180ms; ảnh đại diện 34px; khung đọc.
    private final ToggleGroup navGroup = new ToggleGroup();
    private final PauseTransition reloadDebounce = new PauseTransition(Duration.millis(180));
    private final Avatar profileAvatar = new Avatar(34);
    private MailReader reader;
    // Mục đang xem; đang ở chế độ tìm kiếm; số lần tải (bỏ kết quả cũ về trễ); thư đang mở;
    // tạm bỏ qua sự kiện chọn (khi code tự chọn lại).
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
    // Các thư mục tự tạo; cột chứa nút thư mục; danh sách thư hẹn giờ; dải "đang kết nối lại"; nút Chuyển tới.
    private final List<Folder> customFolders = new ArrayList<>();
    private final VBox folderBox = new VBox(1);
    private final ListView<ScheduledMail> scheduledList = new ListView<>();
    private final Label reconnectLabel = new Label();
    private final HBox reconnectBanner = new HBox(10);
    private final MenuButton moveButton = new MenuButton();
    /** Định dạng dữ liệu kéo-thả thư (chỉ có nghĩa trong ứng dụng này). */
    private static final javafx.scene.input.DataFormat MAIL_ENTRIES =
            new javafx.scene.input.DataFormat("application/x-mailsystem-entries");

    /** JavaFX gọi sau khi nạp FXML. */
    @FXML
    public void initialize() {
        // Tạo khung đọc, đặt vào cột phải; cột phải co giãn theo cửa sổ.
        reader = new MailReader(this, root);
        readerHost.getChildren().add(reader);
        SplitPane.setResizableWithParent(readerHost, true);

        // Dựng sidebar, mục thư mục, menu hồ sơ, danh sách, dải kết nối lại, nút Chuyển tới.
        buildNavigation();
        buildFolderSection();
        buildProfileMenu();
        configureLists();
        buildReconnectBanner();
        buildMoveButton();
        // Hết thời gian gộp: tải lại danh sách và số đếm.
        reloadDebounce.setOnFinished(event -> {
            loadList(false);
            refreshCounts();
        });

        // Nhận EVENT (từ thread mạng) và xử lý trên luồng giao diện.
        ServerConnection.getInstance().setEventListener(event -> Platform.runLater(() -> onEvent(event)));
        // Hiện trạng thái mã hoá ở góc: chữ và chú thích (giao thức, bộ mã hoá, dấu vân tay).
        Connector.SecurityInfo security = ServerConnection.getInstance().securityInfo();
        boolean encrypted = security != null && security.encrypted();
        connectionLabel.setText(encrypted ? "TLS 1.3 · " + Session.serverAddress() : "KHÔNG mã hoá");
        Tooltip.install(connectionChip, new Tooltip(encrypted
                ? "Kết nối mã hoá " + security.protocol() + "\n" + security.cipherSuite()
                        + "\nDấu vân tay máy chủ:\n" + security.fingerprint()
                : "Kết nối không được mã hoá"));
        // Chân sidebar: email đang đăng nhập.
        footerLabel.setText("Đã đăng nhập: " + (Session.account() == null ? "" : Session.account().getEmail()));

        // Gợi ý cho ô tìm kiếm.
        installSearchSuggestions();

        // Có cảnh thì cài phím tắt.
        root.sceneProperty().addListener((observable, old, scene) -> {
            if (scene != null) {
                installShortcuts(scene);
            }
        });
        // Mở Hộp thư đến; tải số đếm.
        selectView(View.INBOX);
        refreshCounts();
    }

    // ------------------------------------------------------------------ dựng giao diện

    private void buildNavigation() {
        // Tạo nút cho mỗi mục cố định (trừ CUSTOM).
        for (View item : View.values()) {
            if (item == View.CUSTOM) {
                continue;
            }
            // Nút có icon và tên; thuộc nhóm; bấm thì chuyển mục; ghi nhớ và thêm vào sidebar.
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
        // Tiêu đề "Thư mục" và nút + tạo thư mục.
        Label title = new Label("Thư mục");
        title.getStyleClass().add("sidebar-section");
        Button add = new Button();
        add.setGraphic(Icons.of(Icons.PLUS, 16));
        add.getStyleClass().add("icon-button");
        add.setTooltip(new Tooltip("Tạo thư mục mới"));
        add.setOnAction(event -> handleCreateFolder());
        // Hàng tiêu đề: chữ, khoảng trống, nút +.
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        HBox header = new HBox(4, title, spacer, add);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("sidebar-section-row");
        // Chèn ngay sau các mục cố định: hàng tiêu đề + khung cuộn chứa các nút thư mục (cao tối đa 260px).
        int index = sidebar.getChildren().indexOf(navBox) + 1;
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(folderBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMaxHeight(260);
        sidebar.getChildren().addAll(index, List.of(header, scroll));
    }

    private void renderFolders() {
        // Nhớ các nút cũ rồi xoá khỏi cột.
        List<javafx.scene.Node> old = new ArrayList<>(folderBox.getChildren());
        folderBox.getChildren().clear();
        // Tạo nút cho từng thư mục tự tạo.
        for (Folder folder : customFolders) {
            NavButton button = new NavButton(Icons.FOLDER, folder.getFolderName());
            button.setToggleGroup(navGroup);
            // Số thư chưa đọc; chọn sẵn nếu đang mở thư mục này.
            button.setCount(folder.getUnreadCount());
            button.setSelected(view == View.CUSTOM && customFolder != null
                    && customFolder.getFolderId() == folder.getFolderId());
            // Bấm: mở thư mục.
            button.setOnAction(event -> selectFolder(folder));
            // Menu chuột phải: Đổi tên, Xoá thư mục.
            MenuItem rename = new MenuItem("Đổi tên", Icons.of(Icons.RENAME, 16));
            rename.setOnAction(event -> renameFolder(folder));
            MenuItem delete = new MenuItem("Xoá thư mục", Icons.of(Icons.TRASH, 16));
            delete.setOnAction(event -> deleteFolder(folder));
            button.setContextMenu(new javafx.scene.control.ContextMenu(rename, delete));
            // Thả thư vào nút: chuyển tới thư mục.
            installDropTarget(button, entryIds -> moveTo(entryIds, folder.getFolderId(), folder.getFolderName()));
            folderBox.getChildren().add(button);
        }
        // Chưa có thư mục: hiện lời nhắc.
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
        // Cập nhật menu Chuyển tới.
        buildMoveButton();
    }

    private void selectFolder(Folder folder) {
        // Ghi nhớ thư mục rồi chuyển sang mục CUSTOM.
        customFolder = folder;
        selectView(View.CUSTOM);
    }

    private void handleCreateFolder() {
        // Hỏi tên; có tên thì gửi CREATE_FOLDER, thành công thì báo và tải lại số đếm (kèm danh sách thư mục).
        Dialogs.prompt(Navigator.owner(), "Thư mục mới", "Tên thư mục:", "", "Tạo").ifPresent(name ->
                Fx.then(api.createFolder(name), id -> {
                    Toast.success(root, "Đã tạo thư mục \"" + name + "\".");
                    refreshCounts();
                }, error -> Toast.error(root, error)));
    }

    private void renameFolder(Folder folder) {
        // Hỏi tên mới; gửi RENAME_FOLDER; đang mở thư mục này thì đổi tiêu đề; tải lại số đếm.
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
        // Hỏi xác nhận.
        if (!Dialogs.confirm(Navigator.owner(), "Xoá thư mục \"" + folder.getFolderName() + "\"?",
                "Các thư trong thư mục sẽ được chuyển vào Thùng rác (khôi phục được).", "Xoá thư mục", true)) {
            return;
        }
        // Gửi DELETE_FOLDER; báo số thư đã vào Thùng rác; đang mở thư mục này thì về Hộp thư đến.
        Fx.then(api.deleteFolder(folder.getFolderId()), moved -> {
            Toast.success(root, moved > 0 ? "Đã xoá thư mục, " + moved + " thư đã vào Thùng rác." : "Đã xoá thư mục.");
            if (view == View.CUSTOM && customFolder != null && customFolder.getFolderId() == folder.getFolderId()) {
                selectView(View.INBOX);
            }
            refreshCounts();
        }, error -> Toast.error(root, error));
    }

    private void moveTo(List<Integer> entryIds, Integer folderId, String folderName) {
        // Không có đích hoặc không có thư: thôi.
        if (folderId == null || entryIds.isEmpty()) {
            return;
        }
        // Gửi MOVE_MAIL và báo kết quả.
        bulk(api.moveMail(entryIds, folderId), "Đã chuyển %d thư tới " + folderName + ".");
    }

    /** Nút "Chuyển tới" trên thanh thao tác hàng loạt (thay cho kéo-thả khi dùng bàn phím). */
    private void buildMoveButton() {
        // Lần đầu: cài icon, chú thích, chèn trước nút Xoá trên thanh thao tác.
        if (moveButton.getParent() == null) {
            moveButton.setGraphic(Icons.of(Icons.FOLDER_MOVE, 18));
            moveButton.getStyleClass().add("icon-button");
            moveButton.setTooltip(new Tooltip("Chuyển tới thư mục"));
            selectionBar.getChildren().add(selectionBar.getChildren().indexOf(bulkTrashButton), moveButton);
        }
        // Dựng lại menu: Hộp thư đến, các thư mục tự tạo, Thư mục mới...
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
        // Kéo thư (không phải từ chính nút này) qua nút: cho phép thả kiểu MOVE.
        target.setOnDragOver(event -> {
            if (event.getGestureSource() != target && event.getDragboard().hasContent(MAIL_ENTRIES)) {
                event.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
            }
            event.consume();
        });
        // Kéo vào: tô sáng nút; kéo ra: bỏ tô.
        target.setOnDragEntered(event -> {
            if (event.getDragboard().hasContent(MAIL_ENTRIES)) {
                target.getStyleClass().add("drop-target");
            }
        });
        target.setOnDragExited(event -> target.getStyleClass().remove("drop-target"));
        target.setOnDragDropped(event -> {
            // Dữ liệu kéo: chuỗi "id1,id2,..." -> danh sách entryId -> gọi onDrop.
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
            // Báo kết quả thả.
            event.setDropCompleted(done);
            event.consume();
        });
    }

    /** Ô thư có thể kéo đi: kéo một thư chưa chọn thì chỉ kéo thư đó, ngược lại kéo cả nhóm đang chọn. */
    private MailCell mailCell(boolean showFolderTag) {
        // Ô thư với nút ngôi sao; showFolderTag: hiện tên thư mục.
        MailCell cell = new MailCell(this::toggleStar, showFolderTag);
        // Bắt đầu kéo ô.
        cell.setOnDragDetected(event -> {
            // Ô trống hoặc đang ở Thùng rác: không kéo.
            MailSummary item = cell.getItem();
            if (item == null || view == View.TRASH) {
                return;
            }
            // Kéo thư chưa được chọn: chỉ chọn thư đó.
            if (!mailList.getSelectionModel().getSelectedItems().contains(item)) {
                mailList.getSelectionModel().clearSelection();
                mailList.getSelectionModel().select(item);
            }
            // entryId các thư đang chọn; bắt đầu kéo kiểu MOVE.
            List<Integer> entryIds = ids(selectedMails());
            javafx.scene.input.Dragboard board = cell.startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
            // Đặt dữ liệu kéo: "id1,id2,..." theo định dạng riêng MAIL_ENTRIES.
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.put(MAIL_ENTRIES, entryIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
            board.setContent(content);
            // Hình đi theo chuột: nhãn "N thư" nền xanh.
            Label ghost = new Label(selectedMails().size() == 1 ? "1 thư" : selectedMails().size() + " thư");
            ghost.setStyle("-fx-background-color: #0b57d0; -fx-text-fill: white; -fx-padding: 4 10; -fx-background-radius: 12;");
            // Gắn nhãn vào một cảnh tạm để CSS được áp trước khi chụp ảnh.
            new Scene(new StackPane(ghost));
            // Chụp nhãn làm hình kéo.
            board.setDragView(ghost.snapshot(null, null));
            event.consume();
        });
        return cell;
    }

    // ------------------------------------------------------------------ kết nối lại

    private void buildReconnectBanner() {
        // Chữ trạng thái xuống dòng, rộng tối đa 620px; vòng xoay 16px.
        reconnectLabel.setWrapText(true);
        reconnectLabel.setMaxWidth(620);
        javafx.scene.control.ProgressIndicator spin = new javafx.scene.control.ProgressIndicator();
        spin.setMaxSize(16, 16);
        // Dải: icon mất mạng, chữ, vòng xoay; ẩn ban đầu.
        reconnectBanner.getChildren().setAll(Icons.of(Icons.WIFI_OFF, 18), reconnectLabel, spin);
        reconnectBanner.setAlignment(Pos.CENTER_LEFT);
        reconnectBanner.getStyleClass().add("banner-reconnect");
        reconnectBanner.setVisible(false);
        // Dải NỔI ở giữa phía trên (lớp trên cùng của root): hiện / ẩn không đẩy nội dung bên dưới.
        reconnectBanner.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE);
        StackPane.setAlignment(reconnectBanner, Pos.TOP_CENTER);
        StackPane.setMargin(reconnectBanner, new Insets(66, 16, 0, 16));
        // Thêm vào lớp trên cùng.
        root.getChildren().add(reconnectBanner);

        // Theo dõi trạng thái kết nối lại.
        Reconnector reconnector = Reconnector.get();
        // Trạng thái đổi: có chữ thì hiện dải (kèm hiệu ứng nếu đang ẩn), cập nhật chữ và nhãn kết nối ở góc.
        javafx.beans.value.ChangeListener<String> onStatus = (obs, old, text) -> {
            boolean show = text != null;
            if (show && !reconnectBanner.isVisible()) {
                Motion.dropIn(reconnectBanner);
            }
            reconnectBanner.setVisible(show);
            reconnectLabel.setText(text == null ? "" : text);
            connectionLabel.setText(show ? "Đang kết nối lại..." : connectionText());
        };
        // Kết nối lại thành công: báo và tải lại.
        javafx.beans.value.ChangeListener<Number> onReconnected = (obs, old, count) -> {
            Toast.success(root, "Đã kết nối lại tới máy chủ.");
            // Trong lúc mất mạng có thể đã lỡ EVENT: tải lại toàn bộ.
            reloadNow();
        };
        // Đăng ký hai bộ nghe.
        reconnector.statusProperty().addListener(onStatus);
        reconnector.reconnectsProperty().addListener(onReconnected);
        // Màn hình chính bị thay khi đăng xuất: gỡ listener để không giữ controller cũ.
        root.sceneProperty().addListener((obs, old, scene) -> {
            if (scene == null) {
                reconnector.statusProperty().removeListener(onStatus);
                reconnector.reconnectsProperty().removeListener(onReconnected);
            }
        });
        // Áp trạng thái hiện tại ngay (có thể đang kết nối lại).
        onStatus.changed(null, null, reconnector.statusProperty().get());
    }

    private String connectionText() {
        // Chữ ở góc: "TLS 1.3 · host:port" hoặc "KHÔNG mã hoá".
        Connector.SecurityInfo security = ServerConnection.getInstance().securityInfo();
        boolean encrypted = security != null && security.encrypted();
        return encrypted ? "TLS 1.3 · " + Session.serverAddress() : "KHÔNG mã hoá";
    }

    private void buildProfileMenu() {
        // Nút hồ sơ hiện ảnh đại diện.
        profileMenu.setGraphic(profileAvatar);
        // Đầu menu: ảnh 44px, tên, email (mục tuỳ biến, bấm không đóng menu).
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

        // Các mục: Tài khoản & bảo mật, Nhóm thư.
        MenuItem account = new MenuItem("Tài khoản & bảo mật", Icons.of(Icons.SHIELD, 18));
        account.setOnAction(event -> handleOpenAccount());
        MenuItem groups = new MenuItem("Nhóm thư", Icons.of(Icons.GROUPS, 18));
        groups.setOnAction(event -> handleOpenGroups());
        // Giao diện tối (đổi theme ngay).
        javafx.scene.control.CheckMenuItem dark = new javafx.scene.control.CheckMenuItem("Giao diện tối",
                Icons.of(Icons.DARK, 18));
        dark.setSelected(Session.darkMode());
        dark.setOnAction(event -> Navigator.setDarkMode(dark.isSelected()));
        // Gộp thư theo hội thoại (lưu lựa chọn rồi tải lại mục hiện tại).
        javafx.scene.control.CheckMenuItem threaded = new javafx.scene.control.CheckMenuItem("Gộp thư theo hội thoại",
                Icons.of(Icons.THREAD, 18));
        threaded.setSelected(Session.threadedView());
        threaded.setOnAction(event -> {
            Session.setThreadedView(threaded.isSelected());
            selectView(view);
        });
        // Đăng xuất, Đăng xuất khỏi mọi thiết bị.
        MenuItem logout = new MenuItem("Đăng xuất", Icons.of(Icons.LOGOUT, 18));
        logout.setOnAction(event -> handleLogout());
        MenuItem logoutAll = new MenuItem("Đăng xuất khỏi mọi thiết bị", Icons.of(Icons.DEVICES, 18));
        logoutAll.setOnAction(event -> handleLogoutAll());
        // Thứ tự các mục, có vạch ngăn.
        profileMenu.getItems().setAll(headerItem, new SeparatorMenuItem(), account, groups, new SeparatorMenuItem(),
                dark, threaded, new SeparatorMenuItem(), logout, logoutAll);

        // Cập nhật ảnh, tên, email, chú thích theo tài khoản hiện tại.
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
        // Chạy ngay và mỗi khi tài khoản đổi.
        refresh.run();
        Session.accountProperty().addListener((observable, old, value) -> refresh.run());
    }

    private void configureLists() {
        // Danh sách thư: chọn nhiều; ô thư (hiện tên thư mục ở mục Có gắn sao hoặc khi tìm kiếm).
        mailList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        mailList.setCellFactory(list -> mailCell(view == View.STARRED || searching));
        // Đổi lựa chọn: cập nhật khung đọc và thanh thao tác.
        mailList.getSelectionModel().getSelectedItems()
                .addListener((ListChangeListener<MailSummary>) change -> onSelectionChanged());
        // Phím trên danh sách: Delete xoá (Thùng rác: xoá vĩnh viễn), S gắn sao, U chưa đọc.
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

        // Danh sách thư hẹn giờ: kiểu CSS, ẩn ban đầu, ô có nút huỷ, chèn vào ngăn danh sách.
        scheduledList.getStyleClass().add("mail-list");
        scheduledList.setVisible(false);
        scheduledList.setCellFactory(list -> new ScheduledCell(this::cancelScheduled));
        listStack.getChildren().add(2, scheduledList);

        // Danh sách nháp: chọn thì xem trước; bỏ chọn (ở mục Thư nháp) thì hiện trống.
        draftList.setCellFactory(list -> new DraftCell());
        draftList.getSelectionModel().selectedItemProperty().addListener((observable, old, draft) -> {
            if (draft != null) {
                reader.showDraft(draft);
            } else if (view == View.DRAFTS) {
                reader.showEmpty();
            }
        });
        // Bấm đúp chuột trái: tiếp tục soạn.
        draftList.setOnMouseClicked(event -> {
            Draft draft = draftList.getSelectionModel().getSelectedItem();
            if (draft != null && event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                editDraft(draft);
            }
        });
        // Enter: tiếp tục soạn; Delete: xoá nháp.
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
        // Gắn gợi ý cho ô tìm kiếm (hiện cả khi ô trống, không tự chọn dòng đầu).
        AutoComplete.attach(searchField, 0, false, query -> {
            // Ô trống: các từ khoá đã tìm gần đây (bấm thì tìm lại).
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
            // 1 ký tự: không gợi ý.
            if (query.length() < 2) {
                return CompletableFuture.completedFuture(List.of());
            }
            // Gọi song song SEARCH_MAIL (mọi thư mục) và SUGGEST_CONTACTS (3 người), ghép kết quả.
            return api.search(null, query, "").thenCombine(api.suggestContacts(query, false, 3), (mails, people) -> {
                List<AutoComplete.Suggestion> items = new ArrayList<>();
                // Dòng 1: tìm ở mọi thư mục (kèm số thư khớp).
                items.add(new AutoComplete.Suggestion("", Icons.SEARCH, "Tìm \"" + query + "\" trong mọi thư mục",
                        mails.size() + " thư khớp", () -> searchEverywhere(query, 0)));
                // Tối đa 5 thư khớp: bấm thì tìm và mở thư đó.
                mails.stream().limit(5).forEach(mail -> items.add(new AutoComplete.Suggestion("Thư", Icons.MAIL_SEARCH,
                        mail.getSubject() == null || mail.getSubject().isBlank() ? "(Không có tiêu đề)" : mail.getSubject(),
                        senderName(mail) + "  ·  " + Formats.shortDate(mail.getSentAt()),
                        () -> searchEverywhere(query, mail.getEntryId()))));
                // Người gửi khớp: bấm thì lọc theo người gửi ở mọi thư mục.
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
        // Ô lọc người gửi: gợi ý một người; chọn xong thì tìm.
        ContactSuggestions.forSinglePerson(fromFilterField, this::handleSearch);
    }

    private static String senderName(MailSummary mail) {
        // Tên người gửi (trống thì email).
        return mail.getSenderName() == null || mail.getSenderName().isBlank() ? mail.getSenderEmail() : mail.getSenderName();
    }

    /** Tìm trên mọi thư mục; openEntryId > 0 thì tự mở thư đó khi kết quả về. */
    private void searchEverywhere(String keyword, int openEntryId) {
        // Đặt từ khoá, bật tìm mọi thư mục, ghi nhớ thư cần mở, rồi tìm.
        searchField.setText(keyword);
        searchAllFolders = true;
        pendingOpenEntryId = openEntryId;
        handleSearch();
    }

    private void installShortcuts(Scene scene) {
        // Ctrl+N soạn thư; Ctrl+F vào ô tìm kiếm; F5 tải lại.
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN), this::handleCompose);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN), () -> {
            searchField.requestFocus();
            searchField.selectAll();
        });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F5), this::handleRefresh);
        // Esc ở ô tìm kiếm: xoá tìm kiếm.
        searchField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                handleClearSearch();
            }
        });
        // Phím tắt kiểu Gmail khi đang đứng ở danh sách thư: R trả lời, A trả lời tất cả, F chuyển tiếp.
        mailList.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            // Thư đang mở; không có, hoặc đang giữ Ctrl: bỏ qua.
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
        // Ghi nhớ mục; mục cố định thì chọn nút, thư mục tự tạo thì vẽ lại các nút thư mục.
        view = target;
        if (target != View.CUSTOM) {
            navButtons.get(target).setSelected(true);
        } else {
            renderFolders();
        }
        // Thoát chế độ tìm kiếm: xoá cờ, xoá các ô, ẩn nút xoá tìm kiếm.
        searching = false;
        searchAllFolders = false;
        pendingOpenEntryId = 0;
        searchField.clear();
        fromFilterField.clear();
        clearSearchButton.setVisible(false);
        clearSearchButton.setManaged(false);
        // Tiêu đề danh sách (thư mục tự tạo: tên thư mục; mục khác: bỏ ghi nhớ thư mục).
        listTitle.setText(target.title);
        if (target == View.CUSTOM && customFolder != null) {
            listTitle.setText(customFolder.getFolderName());
        } else {
            customFolder = null;
        }
        // Hiện đúng danh sách: thư / nháp / hẹn giờ; ô chọn tất cả chỉ ở danh sách thư.
        boolean drafts = target == View.DRAFTS;
        boolean scheduled = target == View.SCHEDULED;
        mailList.setVisible(!drafts && !scheduled);
        draftList.setVisible(drafts);
        scheduledList.setVisible(scheduled);
        selectAllCheck.setVisible(!drafts && !scheduled);
        selectAllCheck.setManaged(!drafts && !scheduled);
        // Nút Dọn sạch Thùng rác chỉ ở Thùng rác.
        emptyTrashButton.setVisible(target == View.TRASH);
        emptyTrashButton.setManaged(target == View.TRASH);
        // Đặt lại ô thư; xoá các danh sách; bỏ thư đang mở; khung đọc trống; đổi tiêu đề cửa sổ.
        mailList.setCellFactory(list -> mailCell(view == View.STARRED || searching));
        mailList.getItems().clear();
        draftList.getItems().clear();
        scheduledList.getItems().clear();
        openedEntryId = 0;
        currentDetail = null;
        reader.showEmpty();
        updateWindowTitle();
        // Hiệu ứng rồi tải danh sách (có vòng xoay).
        Motion.fadeIn(listStack);
        loadList(true);
    }

    /** Tải danh sách của view hiện tại. loadToken bỏ qua kết quả của request cũ khi người dùng đã chuyển view. */
    private void loadList(boolean showSpinner) {
        // Số lần tải mới; ẩn dải lỗi; hiện vòng xoay nếu cần.
        int token = ++loadToken;
        hideError();
        if (showSpinner) {
            listLoading.setVisible(true);
        }
        // Thư nháp: GET_DRAFTS.
        if (view == View.DRAFTS) {
            Fx.then(api.drafts(), drafts -> {
                // Đã có lần tải mới hơn: bỏ kết quả này.
                if (token != loadToken) {
                    return;
                }
                // Ẩn vòng xoay; nhớ bản nháp đang chọn; đặt danh sách; số đếm ở sidebar.
                listLoading.setVisible(false);
                Integer selected = draftList.getSelectionModel().getSelectedItem() == null
                        ? null : draftList.getSelectionModel().getSelectedItem().getDraftId();
                draftList.getItems().setAll(drafts);
                navButtons.get(View.DRAFTS).setCount(drafts.size());
                // Chọn lại bản nháp cũ (nếu còn).
                if (selected != null) {
                    drafts.stream().filter(draft -> draft.getDraftId() == selected).findFirst()
                            .ifPresent(draft -> draftList.getSelectionModel().select(draft));
                }
                // Số thư; hiện khung trống nếu không có.
                listCount.setText(drafts.isEmpty() ? "" : drafts.size() + " thư");
                showEmptyIfNeeded(drafts.isEmpty());
            }, error -> showLoadError(token, error));
            return;
        }
        // Đã lên lịch: GET_SCHEDULED.
        if (view == View.SCHEDULED) {
            Fx.then(api.scheduled(), mails -> {
                if (token != loadToken) {
                    return;
                }
                // Ẩn vòng xoay; đặt danh sách; số đếm; khung trống nếu không có.
                listLoading.setVisible(false);
                scheduledList.getItems().setAll(mails);
                navButtons.get(View.SCHEDULED).setCount(mails.size());
                listCount.setText(mails.isEmpty() ? "" : mails.size() + " thư chờ gửi");
                showEmptyIfNeeded(mails.isEmpty());
            }, error -> showLoadError(token, error));
            return;
        }

        // Các mục còn lại: chọn request phù hợp.
        CompletableFuture<List<MailSummary>> request;
        // Đang tìm: thư mục tìm = null (mọi thư mục) nếu ở Có gắn sao / tìm mọi nơi, không thì thư mục đang mở.
        if (searching) {
            Integer folderId = view == View.STARRED || searchAllFolders ? null
                    : view == View.CUSTOM && customFolder != null ? Integer.valueOf(customFolder.getFolderId()) : folderIds.get(view);
            request = api.search(folderId, searchField.getText().trim(), fromFilterField.getText().trim());
        } else if (view == View.STARRED) {
            // Có gắn sao.
            request = api.starred();
        } else if (view == View.CUSTOM) {
            // Thư mục tự tạo.
            if (customFolder == null) {
                return;
            }
            request = api.mailList(customFolder.getFolderId(), Session.threadedView());
        } else {
            // Mục là thư mục thật (Hộp thư đến / Đã gửi / Thùng rác).
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
        // Có kết quả: bỏ nếu cũ, ẩn vòng xoay, áp danh sách.
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
        // entryId các thư đang chọn.
        Set<Integer> selected = mailList.getSelectionModel().getSelectedItems().stream()
                .map(MailSummary::getEntryId).collect(Collectors.toSet());
        // Tạm bỏ qua sự kiện chọn trong lúc thay danh sách.
        suppressSelectionEvents = true;
        // Danh sách đang hiện.
        List<MailSummary> current = mailList.getItems();
        if (sameRows(current, mails)) {
            // Cùng các dòng (VD vừa đánh dấu đã đọc): chỉ thay dòng thật sự đổi, danh sách đứng yên.
            for (int i = 0; i < mails.size(); i++) {
                if (!rowKey(current.get(i)).equals(rowKey(mails.get(i)))) {
                    current.set(i, mails.get(i));
                }
            }
        } else {
            // Dòng thay đổi (thư mới, xoá...): giữ thư đang ở đầu khung nhìn ở nguyên chỗ cũ.
            Integer anchorEntry = firstVisibleEntryId();
            mailList.getItems().setAll(mails);
            if (anchorEntry != null) {
                for (int i = 0; i < mails.size(); i++) {
                    if (mails.get(i).getEntryId() == anchorEntry) {
                        mailList.scrollTo(i);
                        break;
                    }
                }
            }
        }
        // Chọn lại các thư đã chọn trước đó (theo entryId).
        mailList.getSelectionModel().clearSelection();
        for (int i = 0; i < mails.size(); i++) {
            if (selected.contains(mails.get(i).getEntryId())) {
                mailList.getSelectionModel().select(i);
            }
        }
        // Có thư cần tự mở (chọn từ gợi ý tìm kiếm): chỉ chọn thư đó và cuộn tới.
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
        // Bật lại sự kiện chọn; cập nhật khung đọc, số thư, khung trống.
        suppressSelectionEvents = false;
        onSelectionChanged();
        updateListCount();
        showEmptyIfNeeded(mails.isEmpty());
    }

    private static boolean sameRows(List<MailSummary> current, List<MailSummary> next) {
        // Khác số dòng: khác.
        if (current.size() != next.size()) {
            return false;
        }
        // Cùng entryId ở mọi vị trí: giống.
        for (int i = 0; i < next.size(); i++) {
            if (current.get(i).getEntryId() != next.get(i).getEntryId()) {
                return false;
            }
        }
        return true;
    }

    /** Những gì hiển thị trên một dòng: khác nhau thì mới vẽ lại dòng đó. */
    private static String rowKey(MailSummary mail) {
        return mail.isRead() + "|" + mail.isStarred() + "|" + mail.getThreadCount() + "|" + mail.getSubject() + "|"
                + mail.getSnippet() + "|" + mail.getSentAt() + "|" + mail.getSenderName() + "|" + mail.isHasAttachments()
                + "|" + mail.getFolderType();
    }

    private Integer firstVisibleEntryId() {
        // Tìm VirtualFlow (phần cuộn bên trong ListView) và ô đầu tiên đang thấy.
        if (mailList.lookup(".virtual-flow") instanceof javafx.scene.control.skin.VirtualFlow<?> flow
                && flow.getFirstVisibleCell() != null) {
            // Ô đầu không phải dòng 0: trả entryId của nó.
            int index = flow.getFirstVisibleCell().getIndex();
            if (index > 0 && index < mailList.getItems().size()) {
                return mailList.getItems().get(index).getEntryId();
            }
        }
        return null; // đang ở đầu danh sách: cứ để thư mới hiện lên trên cùng
    }

    private void updateListCount() {
        // "N thư · M chưa đọc" (Thùng rác không hiện số chưa đọc).
        List<MailSummary> mails = mailList.getItems();
        long unread = mails.stream().filter(mail -> !mail.isRead()).count();
        listCount.setText(mails.isEmpty() ? ""
                : mails.size() + " thư" + (unread > 0 && view != View.TRASH ? " · " + unread + " chưa đọc" : ""));
    }

    private void showEmptyIfNeeded(boolean empty) {
        // Hiện / ẩn khung trống; không trống thì thôi.
        listEmpty.setVisible(empty);
        if (!empty) {
            return;
        }
        // Nội dung khung trống theo trường hợp: đang tìm, hoặc theo từng mục.
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
        // Đặt khung trống.
        listEmpty.getChildren().setAll(state);
    }

    private void showLoadError(int token, String message) {
        // Kết quả cũ: bỏ qua.
        if (token != loadToken) {
            return;
        }
        // Ẩn vòng xoay, hiện dải lỗi.
        listLoading.setVisible(false);
        errorBannerLabel.setText("Không tải được dữ liệu: " + message);
        errorBanner.setVisible(true);
    }

    // Dải lỗi nổi trên danh sách: chỉ ẩn/hiện, không thêm/bớt khỏi bố cục (tránh đẩy danh sách).
    private void hideError() {
        errorBanner.setVisible(false);
    }

    /** Cập nhật số thư chưa đọc / số thư nháp ở sidebar và tiêu đề cửa sổ. */
    private void refreshCounts() {
        // Tải thư mục (GET_FOLDERS) -> số chưa đọc, danh sách thư mục; lỗi thì bỏ qua.
        Fx.then(api.folders(), this::applyFolders, error -> {
        });
        // Không đang ở Thư nháp / Đã lên lịch thì tải riêng số đếm của hai mục đó.
        if (view != View.DRAFTS) {
            Fx.then(api.drafts(), drafts -> navButtons.get(View.DRAFTS).setCount(drafts.size()), error -> {
            });
        }
        if (view != View.SCHEDULED) {
            Fx.then(api.scheduled(), mails -> navButtons.get(View.SCHEDULED).setCount(mails.size()), error -> {
            });
        }
    }

    // Số thư chưa đọc ở Hộp thư đến.
    private int inboxUnread;

    private void applyFolders(List<Folder> folders) {
        // Các thư mục tự tạo, sắp theo tên (chữ thường).
        List<Folder> custom = folders.stream().filter(folder -> "CUSTOM".equals(folder.getFolderType()))
                .sorted(java.util.Comparator.comparing(folder -> folder.getFolderName().toLowerCase()))
                .collect(Collectors.toList());
        // Thay danh sách thư mục tự tạo.
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
        // Vẽ lại các nút thư mục.
        renderFolders();
        // Ghi nhớ folderId của Hộp thư đến / Đã gửi / Thùng rác.
        for (Folder folder : folders) {
            View target = switch (folder.getFolderType()) {
                case "INBOX" -> View.INBOX;
                case "SENT" -> View.SENT;
                case "TRASH" -> View.TRASH;
                default -> null;
            };
            if (target != null) {
                folderIds.put(target, folder.getFolderId());
                // Hộp thư đến: cập nhật số chưa đọc trên nút.
                if (target == View.INBOX) {
                    inboxUnread = folder.getUnreadCount();
                    navButtons.get(View.INBOX).setCount(inboxUnread);
                }
            }
        }
        // Cập nhật tiêu đề cửa sổ và chú thích khay hệ thống.
        updateWindowTitle();
        DesktopNotifier.updateTooltip(inboxUnread);
    }

    private void updateWindowTitle() {
        // "<Tên mục> (số chưa đọc) – Mail System".
        String unread = inboxUnread > 0 ? " (" + inboxUnread + ")" : "";
        String title = view == View.CUSTOM && customFolder != null ? customFolder.getFolderName() : view.title;
        Navigator.primaryStage().setTitle(title + unread + " – Mail System");
    }

    // ------------------------------------------------------------------ chọn & đọc thư

    // Chi tiết thư đang mở (null nếu không có).
    private MailDetail currentDetail;

    private void onSelectionChanged() {
        // Đang thay danh sách bằng code: bỏ qua.
        if (suppressSelectionEvents) {
            return;
        }
        // Cập nhật ô chọn tất cả: chọn hết / chọn một phần / tắt khi danh sách rỗng.
        List<MailSummary> selected = selectedMails();
        int total = mailList.getItems().size();
        selectAllCheck.setSelected(!selected.isEmpty() && selected.size() == total);
        selectAllCheck.setIndeterminate(!selected.isEmpty() && selected.size() < total);
        selectAllCheck.setDisable(total == 0);
        // Đang ở Thùng rác?
        boolean trash = view == View.TRASH;
        // Thanh thao tác thay chỗ hàng tiêu đề (cùng chiều cao) thay vì chen thêm một hàng.
        boolean showBar = !selected.isEmpty();
        // Đổi giữa hàng tiêu đề và thanh thao tác (có hiệu ứng).
        if (showBar != selectionBar.isVisible()) {
            Motion.fadeIn(showBar ? selectionBar : titleRow);
        }
        selectionBar.setVisible(showBar);
        titleRow.setVisible(!showBar);
        // Số thư đang chọn; các nút theo mục (Thùng rác: Khôi phục / Xoá vĩnh viễn).
        selectionLabel.setText("Đã chọn " + selected.size());
        setShown(bulkReadButton, !trash);
        setShown(bulkUnreadButton, !trash);
        setShown(bulkTrashButton, !trash);
        setShown(bulkRestoreButton, trash);
        setShown(bulkDeleteButton, trash);
        // Tất cả đều có sao: nút thành "Bỏ gắn sao".
        boolean allStarred = !selected.isEmpty() && selected.stream().allMatch(MailSummary::isStarred);
        bulkStarButton.setGraphic(Icons.of(allStarred ? Icons.STAR : Icons.STAR_OUTLINE, 18));
        bulkStarTooltip.setText(allStarred ? "Bỏ gắn sao" : "Gắn sao");

        // Một thư: mở; nhiều thư: hiện "Đã chọn N thư"; không thư nào: khung trống.
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
        // Đã mở thư này rồi: thôi.
        if (summary.getEntryId() == openedEntryId) {
            return; // đã mở thư này (VD: danh sách vừa tải lại do EVENT)
        }
        // Ghi nhớ thư đang mở; hiện "đang tải" (sau 200ms).
        openedEntryId = summary.getEntryId();
        reader.showLoading();
        if (summary.getThreadCount() > 1) {
            // Hội thoại: tải mọi thư (Server đánh dấu đã đọc từng thư khi trả về).
            Fx.then(api.thread(summary.getThreadId()), details -> {
                // Đã chuyển sang thư khác hoặc không có thư: bỏ qua.
                if (openedEntryId != summary.getEntryId() || details.isEmpty()) {
                    return;
                }
                // Thư mới nhất là thư "đang mở" (cho phím tắt R / A / F).
                currentDetail = details.get(details.size() - 1);
                reader.showThread(details, Session.account() == null ? "" : Session.account().getEmail());
                // Chưa đọc: đánh dấu đã đọc trong danh sách và tải lại số đếm.
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
        // Thư đơn: GET_MAIL_DETAIL.
        Fx.then(api.detail(summary.getMailId(), summary.getEntryId()), detail -> {
            // Đã chuyển sang thư khác: bỏ qua.
            if (openedEntryId != summary.getEntryId()) {
                return;
            }
            // Hiện thư.
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
        // Bản sao danh sách thư đang chọn.
        return new ArrayList<>(mailList.getSelectionModel().getSelectedItems());
    }

    /** Mọi entryId của các dòng đang chọn (một dòng hội thoại = mọi thư trong hội thoại). */
    private static List<Integer> ids(List<MailSummary> mails) {
        return mails.stream().flatMap(mail -> mail.getThreadEntryIds().stream()).distinct().collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ thao tác hàng loạt

    /** Bấm ô chọn tất cả: chọn hết hoặc bỏ chọn hết. */
    @FXML
    public void handleToggleSelectAll() {
        if (selectAllCheck.isSelected()) {
            mailList.getSelectionModel().selectAll();
        } else {
            mailList.getSelectionModel().clearSelection();
        }
    }

    /** Đánh dấu đã đọc các thư đang chọn. */
    @FXML
    public void handleBulkRead() {
        bulk(api.markRead(ids(selectedMails()), true), "Đã đánh dấu %d thư là đã đọc.");
    }

    /** Đánh dấu chưa đọc các thư đang chọn (không áp dụng ở Thùng rác). */
    @FXML
    public void handleBulkUnread() {
        if (view == View.TRASH || selectedMails().isEmpty()) {
            return;
        }
        openedEntryId = -1; // không tự mở lại (mở thư = đánh dấu đã đọc)
        bulk(api.markRead(ids(selectedMails()), false), "Đã đánh dấu %d thư là chưa đọc.");
        mailList.getSelectionModel().clearSelection();
    }

    /** Gắn / bỏ gắn sao các thư đang chọn: nếu có thư chưa có sao thì gắn hết, không thì bỏ hết. */
    @FXML
    public void handleBulkStar() {
        List<MailSummary> selected = selectedMails();
        if (selected.isEmpty()) {
            return;
        }
        boolean star = !selected.stream().allMatch(MailSummary::isStarred);
        bulk(api.setStarred(ids(selected), star), star ? "Đã gắn sao %d thư." : "Đã bỏ gắn sao %d thư.");
    }

    /** Chuyển các thư đang chọn vào Thùng rác. */
    @FXML
    public void handleBulkTrash() {
        List<MailSummary> selected = selectedMails();
        if (selected.isEmpty() || view == View.TRASH) {
            return;
        }
        bulk(api.moveToTrash(ids(selected)), "Đã chuyển %d thư vào Thùng rác.");
    }

    /** Khôi phục các thư đang chọn (ở Thùng rác). */
    @FXML
    public void handleBulkRestore() {
        List<MailSummary> selected = selectedMails();
        if (!selected.isEmpty()) {
            bulk(api.restore(ids(selected)), "Đã khôi phục %d thư.");
        }
    }

    /** Xoá vĩnh viễn các thư đang chọn (ở Thùng rác), có hỏi xác nhận. */
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

    /** Dọn sạch Thùng rác, có hỏi xác nhận. */
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

    // Chạy thao tác hàng loạt: thành công thì báo "... %d thư", bỏ chọn, tải lại; lỗi thì báo lỗi.
    private void bulk(CompletableFuture<Integer> request, String successFormat) {
        Fx.then(request, processed -> {
            Toast.success(root, String.format(successFormat, processed));
            mailList.getSelectionModel().clearSelection();
            reloadNow();
        }, error -> Toast.error(root, error));
    }

    private void toggleStar(MailSummary mail) {
        // Đảo trạng thái sao của một dòng (dòng hội thoại: mọi thư trong hội thoại).
        boolean star = !mail.isStarred();
        Fx.then(api.setStarred(mail.getThreadEntryIds(), star), processed -> {
            // Thành công: cập nhật dòng; đang mở thư này thì vẽ lại khung đọc.
            mail.setStarred(star);
            mailList.refresh();
            if (currentDetail != null && currentDetail.getEntryId() == mail.getEntryId()) {
                currentDetail.setStarred(star);
                reader.showMail(currentDetail, Session.account().getEmail());
            }
            // Đang ở Có gắn sao mà vừa bỏ sao: tải lại (thư biến khỏi danh sách).
            if (view == View.STARRED && !star) {
                reloadNow();
            }
        }, error -> Toast.error(root, error));
    }

    private void reloadNow() {
        // Huỷ lần tải đang chờ gộp; tải ngay danh sách và số đếm.
        reloadDebounce.stop();
        loadList(false);
        refreshCounts();
    }

    // ------------------------------------------------------------------ MailReader.Actions

    /** Trả lời: mở khung soạn điền sẵn người gửi và trích dẫn. */
    @Override
    public void reply(MailDetail mail) {
        ComposeController.open(ComposeController.Request.reply(mail, false));
    }

    /** Trả lời tất cả. */
    @Override
    public void replyAll(MailDetail mail) {
        ComposeController.open(ComposeController.Request.reply(mail, true));
    }

    /** Chuyển tiếp (kèm tệp đính kèm của thư gốc). */
    @Override
    public void forward(MailDetail mail) {
        ComposeController.open(ComposeController.Request.forward(mail));
    }

    /** Gắn / bỏ sao từ khung đọc: thư có trong danh sách thì dùng hàm của dòng, không thì gửi trực tiếp. */
    @Override
    public void toggleStar(MailDetail mail) {
        mailList.getItems().stream().filter(item -> item.getEntryId() == mail.getEntryId()).findFirst()
                .ifPresentOrElse(this::toggleStar, () -> Fx.then(api.setStarred(List.of(mail.getEntryId()),
                        !mail.isStarred()), processed -> reloadNow(), error -> Toast.error(root, error)));
    }

    /** Đánh dấu chưa đọc thư đang mở (openedEntryId = -1 để không tự mở lại). */
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

    /** Chuyển thư đang mở vào Thùng rác. */
    @Override
    public void trash(MailDetail mail) {
        Fx.then(api.moveToTrash(List.of(mail.getEntryId())), processed -> {
            Toast.success(root, "Đã chuyển thư vào Thùng rác.");
            currentDetail = null;
            reader.showEmpty();
            reloadNow();
        }, error -> Toast.error(root, error));
    }

    /** Khôi phục thư đang mở. */
    @Override
    public void restore(MailDetail mail) {
        Fx.then(api.restore(List.of(mail.getEntryId())), processed -> {
            Toast.success(root, "Đã khôi phục thư.");
            currentDetail = null;
            reader.showEmpty();
            reloadNow();
        }, error -> Toast.error(root, error));
    }

    /** Xoá vĩnh viễn thư đang mở (có hỏi xác nhận). */
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

    /** Tiếp tục soạn bản nháp. */
    @Override
    public void editDraft(Draft draft) {
        ComposeController.open(ComposeController.Request.draft(draft));
    }

    /** Xoá bản nháp (có hỏi xác nhận). */
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
        // Gửi CANCEL_SCHEDULED; Server trả lại nội dung thư.
        Fx.then(api.cancelScheduled(mail.getOutboxId()), data -> {
            // Số tệp đính kèm (không mở lại được, người dùng phải đính kèm lại).
            int files = data.has("attachmentCount") ? data.get("attachmentCount").getAsInt() : 0;
            // Mở khung soạn với nội dung cũ; báo; tải lại.
            ComposeController.open(ComposeController.Request.reopened(data, List.of(), List.of()));
            Toast.info(root, files > 0 ? "Đã huỷ hẹn giờ. Hãy đính kèm lại " + files + " tệp trước khi gửi."
                    : "Đã huỷ hẹn giờ, thư được mở lại để sửa.");
            reloadNow();
        }, error -> Toast.error(root, error));
    }

    // ------------------------------------------------------------------ EVENT real-time

    private void onEvent(EventMessage event) {
        // Đổi tên EVENT thành enum; tên lạ thì bỏ qua.
        EventName name;
        try {
            name = EventName.valueOf(event.getEventName());
        } catch (RuntimeException unknown) {
            return; // Server mới hơn gửi EVENT lạ: bỏ qua thay vì lỗi
        }
        // Xử lý theo loại EVENT.
        switch (name) {
            // Thư mới: thông báo rồi tải lại (gộp).
            case NEW_MAIL -> {
                notifyNewMail(event);
                reloadDebounce.playFromStart();
            }
            // Thư thay đổi: tải lại (gộp).
            case MAIL_READ_UPDATED, MAIL_STARRED, MAIL_DELETED, MAIL_RESTORED, MAIL_MOVED -> reloadDebounce.playFromStart();
            // Thư mục đổi: tải lại số đếm và danh sách thư mục.
            case FOLDERS_UPDATED -> refreshCounts();
            // Hàng đợi hẹn giờ đổi: đang xem thì tải lại danh sách, không thì chỉ số đếm.
            case SCHEDULED_UPDATED -> {
                if (view == View.SCHEDULED) {
                    loadList(false);
                } else {
                    refreshCounts();
                }
            }
            // Thư hẹn giờ gửi thất bại: báo lỗi kèm lý do, tải lại số đếm.
            case SCHEDULED_FAILED -> {
                String reason = text(event, "reason", "Không gửi được thư hẹn giờ.");
                Toast.error(root, "Thư hẹn giờ \"" + text(event, "subject", "") + "\" không gửi được: " + reason
                        + " Thư đã được chuyển về Thư nháp.");
                refreshCounts();
            }
            // Có người đọc thư mình gửi.
            case READ_RECEIPT -> onReadReceipt(event);
            // Nhóm thay đổi.
            case GROUP_UPDATED -> {
                // Được thêm / bị xoá / nhóm bị xoá: thư thông báo đi kèm đã hiện toast + thông báo desktop
                // qua NEW_MAIL. Thành viên tự rời nhóm thì chỉ báo cho chủ nhóm ở đây.
                if ("MEMBER_LEFT".equals(text(event, "action", ""))) {
                    Toast.info(root, text(event, "actorName", "Một thành viên") + " đã rời nhóm \""
                            + text(event, "groupName", "") + "\".");
                }
                // Cửa sổ Nhóm thư đang mở thì tải lại.
                GroupsController.refreshIfOpen();
            }
            // Bản nháp đổi (ở máy khác): tải lại.
            case DRAFTS_UPDATED -> {
                if (view == View.DRAFTS) {
                    loadList(false);
                } else {
                    refreshCounts();
                }
            }
            // Tên hiển thị đổi (ở máy khác): cập nhật phiên.
            case PROFILE_UPDATED -> {
                if (event.getData() != null && event.getData().isJsonObject()
                        && event.getData().getAsJsonObject().has("displayName")) {
                    Session.updateDisplayName(event.getData().getAsJsonObject().get("displayName").getAsString());
                }
            }
            // Phiên bị thu hồi: dừng kết nối lại, gỡ khay, về màn đăng nhập kèm lý do.
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
        // Tên người đọc (trống thì email).
        String reader = text(event, "readerName", "");
        if (reader.isBlank()) {
            reader = text(event, "readerEmail", "Người nhận");
        }
        // Báo "<người> đã đọc thư <tiêu đề>".
        String subject = text(event, "subject", "");
        Toast.info(root, reader + " đã đọc thư \"" + (subject.isBlank() ? "(Không có tiêu đề)" : subject) + "\".");
        // mailId của thư được đọc.
        int mailId = event.getData().getAsJsonObject().has("mailId")
                ? event.getData().getAsJsonObject().get("mailId").getAsInt() : 0;
        // Đang mở đúng thư đó: mở lại để hiện danh sách người đã xem mới.
        if (currentDetail != null && currentDetail.getMailId() == mailId) {
            int entryId = openedEntryId;
            openedEntryId = 0;
            mailList.getItems().stream().filter(item -> item.getEntryId() == entryId).findFirst().ifPresent(this::openMail);
        }
    }

    private static String text(EventMessage event, String field, String fallback) {
        // Không có data, data không phải object, hoặc thiếu trường: trả fallback.
        if (event.getData() == null || !event.getData().isJsonObject() || !event.getData().getAsJsonObject().has(field)) {
            return fallback;
        }
        return event.getData().getAsJsonObject().get(field).getAsString();
    }

    private void notifyNewMail(EventMessage event) {
        // Không có data: thôi.
        if (event.getData() == null || !event.getData().isJsonObject()) {
            return;
        }
        // Email người gửi; thư do chính mình gửi thì không thông báo.
        var data = event.getData().getAsJsonObject();
        String senderEmail = data.has("senderEmail") ? data.get("senderEmail").getAsString() : "";
        Account me = Session.account();
        if (me != null && senderEmail.equalsIgnoreCase(me.getEmail())) {
            return; // thư do chính mình gửi (cập nhật Đã gửi), không thông báo
        }
        // Tên người gửi (trống thì email); tiêu đề.
        String sender = data.has("senderName") && !data.get("senderName").getAsString().isBlank()
                ? data.get("senderName").getAsString() : senderEmail;
        String subject = data.has("subject") ? data.get("subject").getAsString() : "";
        // Cửa sổ đang được focus, hoặc không có khay hệ thống: hiện toast; không thì hiện bong bóng của hệ điều hành.
        boolean focused = Navigator.primaryStage().isFocused();
        if (focused || !DesktopNotifier.notifyNewMail(sender, subject)) {
            Toast.info(root, "Thư mới từ " + sender + ": " + subject);
        }
    }

    // ------------------------------------------------------------------ thanh trên cùng

    /** Tìm kiếm (Enter ở ô tìm hoặc chọn gợi ý). */
    @FXML
    public void handleSearch() {
        // Từ khoá và bộ lọc người gửi; cả hai trống thì xoá tìm kiếm.
        String keyword = searchField.getText() == null ? "" : searchField.getText().trim();
        String from = fromFilterField.getText() == null ? "" : fromFilterField.getText().trim();
        if (keyword.isEmpty() && from.isEmpty()) {
            handleClearSearch();
            return;
        }
        // Ở Thư nháp / Đã lên lịch (không tìm được): chuyển về Hộp thư đến, giữ lại các giá trị tìm kiếm.
        if (view == View.DRAFTS || view == View.SCHEDULED) {
            boolean everywhere = searchAllFolders;
            int pending = pendingOpenEntryId;
            selectView(View.INBOX);
            searchField.setText(keyword);
            fromFilterField.setText(from);
            searchAllFolders = everywhere;
            pendingOpenEntryId = pending;
        }
        // Lưu từ khoá vào lịch sử; bật chế độ tìm; hiện nút xoá tìm kiếm.
        Session.addRecentSearch(keyword);
        searching = true;
        clearSearchButton.setVisible(true);
        clearSearchButton.setManaged(true);
        // Tiêu đề: "Kết quả tìm kiếm · <phạm vi>".
        listTitle.setText("Kết quả tìm kiếm" + (searchAllFolders ? " · Mọi thư mục"
                : view == View.STARRED ? "" : " · " + (view == View.CUSTOM && customFolder != null
                        ? customFolder.getFolderName() : view.title)));
        // Ô thư hiện tên thư mục; bỏ chọn; tải kết quả.
        mailList.setCellFactory(list -> mailCell(true));
        mailList.getSelectionModel().clearSelection();
        loadList(true);
    }

    /** Xoá tìm kiếm: tải lại mục hiện tại (không đang tìm thì thôi). */
    @FXML
    public void handleClearSearch() {
        if (!searching && searchField.getText().isEmpty() && fromFilterField.getText().isEmpty()) {
            return;
        }
        selectView(view);
    }

    /** Bấm Tải lại (hoặc F5). */
    @FXML
    public void handleRefresh() {
        reloadNow();
    }

    /** Bấm Soạn thư (hoặc Ctrl+N): mở khung soạn thư mới. */
    @FXML
    public void handleCompose() {
        ComposeController.open(ComposeController.Request.blank());
    }

    /** Bấm nút menu: ẩn / hiện sidebar. */
    @FXML
    public void handleToggleSidebar() {
        boolean show = !sidebar.isVisible();
        sidebar.setVisible(show);
        sidebar.setManaged(show);
    }

    /** Mở cửa sổ Nhóm thư (đã mở thì đưa lên trước). */
    @FXML
    public void handleOpenGroups() {
        if (!GroupsController.focusIfOpen()) {
            Navigator.openWindow(Navigator.GROUPS, "Nhóm thư – Mail System", 860, 580, false, null);
        }
    }

    /** Mở cửa sổ Tài khoản & bảo mật (modal). */
    public void handleOpenAccount() {
        Navigator.openWindow(Navigator.ACCOUNT, "Tài khoản & bảo mật – Mail System", 720, 760, true, null);
    }

    // Đăng xuất: dừng kết nối lại, gửi LOGOUT; dù thành công hay lỗi cũng về màn đăng nhập.
    private void handleLogout() {
        Reconnector.get().cancel();
        Fx.then(api.logout(), ignored -> afterLogout(null), error -> afterLogout(null));
    }

    private void handleLogoutAll() {
        // Hỏi xác nhận; gửi LOGOUT_ALL; thành công thì về màn đăng nhập kèm số phiên bị thu hồi.
        if (!Dialogs.confirm(Navigator.owner(), "Đăng xuất khỏi mọi thiết bị?",
                "Mọi phiên đăng nhập của tài khoản này trên các máy khác sẽ bị thu hồi ngay lập tức.",
                "Đăng xuất tất cả", true)) {
            return;
        }
        Fx.then(api.logoutAll(), revoked -> afterLogout("Đã đăng xuất khỏi mọi thiết bị ("
                + revoked + " phiên khác đã bị thu hồi)."), error -> Toast.error(root, error));
    }

    // Dừng kết nối lại, gỡ khay, về màn đăng nhập.
    private void afterLogout(String notice) {
        Reconnector.get().cancel();
        DesktopNotifier.shutdown();
        Navigator.showLogin(notice);
    }

    // Ẩn / hiện nút và không chiếm chỗ khi ẩn.
    private static void setShown(Button button, boolean shown) {
        button.setVisible(shown);
        button.setManaged(shown);
    }
}
