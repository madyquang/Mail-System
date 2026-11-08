// Gói "controller": xử lý sự kiện của từng màn hình (gắn với tệp .fxml).
package com.mailsystem.client.controller;

// Gọi Server, phiên, các thành phần giao diện tự viết, DTO nhóm, tiện ích địa chỉ nhóm; JavaFX.
import com.mailsystem.client.api.MailApi;
import com.mailsystem.client.app.Session;
import com.mailsystem.client.ui.ContactSuggestions;
import com.mailsystem.client.ui.Dialogs;
import com.mailsystem.client.ui.EmptyState;
import com.mailsystem.client.ui.Fx;
import com.mailsystem.client.ui.Icons;
import com.mailsystem.client.ui.Toast;
import com.mailsystem.common.model.MailGroupDTO;
import com.mailsystem.common.protocol.GroupAddresses;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

// Danh sách, CompletableFuture.
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Quản lý nhóm thư: tạo nhóm, xem/thêm/xoá thành viên, rời hoặc xoá nhóm (theo quyền chủ nhóm). */
public class GroupsController {

    // Các thành phần giao diện; @FXML: JavaFX tự gán theo fx:id trong groups.fxml.
    @FXML
    private StackPane root;
    @FXML
    private TextField newGroupField;
    @FXML
    private TextField newAddressField;
    @FXML
    private Label addressHint;
    @FXML
    private Label groupAddressLabel;
    @FXML
    private Button copyAddressButton;
    /** Người dùng đã tự sửa địa chỉ: thôi tự điền theo tên nhóm. */
    private boolean addressEdited;
    // Đang tự điền địa chỉ bằng code (để bộ nghe không coi là người dùng sửa).
    private boolean fillingAddress;
    @FXML
    private ListView<MailGroupDTO> groupList;
    @FXML
    private StackPane groupsEmpty;
    @FXML
    private VBox detailBox;
    @FXML
    private StackPane detailEmpty;
    @FXML
    private Label groupNameLabel;
    @FXML
    private Label ownerLabel;
    @FXML
    private HBox addMemberRow;
    @FXML
    private TextField memberEmailField;
    @FXML
    private Label memberCountLabel;
    @FXML
    private ListView<String> memberList;
    @FXML
    private Button removeMemberButton;
    @FXML
    private Button leaveButton;
    @FXML
    private Button deleteButton;

    // Đối tượng gọi Server.
    private final MailApi api = MailApi.get();
    /** Cửa sổ Nhóm thư đang mở (nếu có), để EVENT GROUP_UPDATED tải lại danh sách. */
    private static java.lang.ref.WeakReference<GroupsController> open = new java.lang.ref.WeakReference<>(null);

    /** Cửa sổ Nhóm thư đã mở thì đưa lên trước thay vì mở thêm cửa sổ mới. @return true nếu đã có */
    public static boolean focusIfOpen() {
        // Lấy controller đang mở (tham chiếu yếu: cửa sổ đóng thì có thể thành null).
        GroupsController controller = open.get();
        // Còn cửa sổ và đang hiện: bỏ thu nhỏ, đưa lên trước, lấy focus.
        if (controller != null && controller.root.getScene() != null
                && controller.root.getScene().getWindow() instanceof javafx.stage.Stage stage && stage.isShowing()) {
            stage.setIconified(false);
            stage.toFront();
            stage.requestFocus();
            return true;
        }
        return false;
    }

    /** Gọi trên UI thread khi Server báo nhóm của mình thay đổi (được thêm, bị xoá, nhóm bị xoá...). */
    public static void refreshIfOpen() {
        // Cửa sổ đang mở và hiện: tải lại, giữ nhóm đang chọn.
        GroupsController controller = open.get();
        if (controller != null && controller.root.getScene() != null
                && controller.root.getScene().getWindow() != null && controller.root.getScene().getWindow().isShowing()) {
            MailGroupDTO selected = controller.groupList.getSelectionModel().getSelectedItem();
            controller.load(selected == null ? null : selected.getGroupId());
        }
    }

    /** JavaFX gọi sau khi nạp FXML. */
    @FXML
    public void initialize() {
        // Ghi nhận cửa sổ đang mở.
        open = new java.lang.ref.WeakReference<>(this);
        // Ô của danh sách nhóm: icon, tên in đậm, dòng phụ (địa chỉ · số thành viên · chủ nhóm).
        groupList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(MailGroupDTO group, boolean empty) {
                // Gọi lớp cha; ô trống thì không hiển thị gì.
                super.updateItem(group, empty);
                if (empty || group == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                // Tên nhóm in đậm.
                Label name = new Label(group.getGroupName());
                name.setStyle("-fx-font-weight: bold;");
                // Dòng phụ: địa chỉ nhóm (nếu có), số thành viên, "chủ nhóm" nếu mình là chủ.
                Label meta = new Label((group.getGroupAddress() == null ? "" : group.getGroupAddress() + " · ")
                        + (group.getMemberEmails() == null ? 0 : group.getMemberEmails().size()) + " thành viên"
                        + (group.isOwner() ? " · chủ nhóm" : ""));
                meta.getStyleClass().add("hint");
                // Nhãn co theo bề rộng ô (không đẩy ô rộng ra ngoài khung): chữ dài hiện "…", di chuột xem đủ.
                for (Label label : new Label[] {name, meta}) {
                    label.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
                    label.setMinWidth(0);
                    label.setPrefWidth(1);
                    label.setMaxWidth(Double.MAX_VALUE);
                }
                // Cột chữ được co nhỏ và giãn hết chỗ còn lại.
                VBox text = new VBox(2, name, meta);
                text.setMinWidth(0);
                HBox.setHgrow(text, javafx.scene.layout.Priority.ALWAYS);
                // Hàng: icon khiên (chủ nhóm) hoặc icon nhóm, rồi cột chữ.
                HBox row = new HBox(10, Icons.of(group.isOwner() ? Icons.SHIELD : Icons.GROUPS, 20), text);
                row.setAlignment(Pos.CENTER_LEFT);
                // Hiện hàng; rộng ưu tiên 0; chú thích hiện đủ tên và dòng phụ.
                setText(null);
                setGraphic(row);
                setPrefWidth(0);
                setTooltip(new javafx.scene.control.Tooltip(group.getGroupName() + "\n" + meta.getText()));
            }
        });
        // Chọn nhóm: hiện chi tiết; chọn thành viên: cập nhật các nút.
        groupList.getSelectionModel().selectedItemProperty().addListener((observable, old, group) -> showGroup(group));
        memberList.getSelectionModel().selectedItemProperty().addListener((observable, old, email) -> updateButtons());
        // Khung trống khi chưa có nhóm / chưa chọn nhóm.
        groupsEmpty.getChildren().setAll(new EmptyState().show(Icons.GROUPS, "Chưa có nhóm nào",
                "Tạo nhóm để gửi thư cho nhiều người cùng lúc."));
        detailEmpty.getChildren().setAll(new EmptyState().show(Icons.GROUPS, "Chọn một nhóm",
                "Thành viên và thao tác của nhóm sẽ hiện ở đây."));
        // Gợi ý một người khi gõ email thành viên.
        ContactSuggestions.forSinglePerson(memberEmailField, null);
        // Gõ tên nhóm thì địa chỉ tự điền (bỏ dấu, chữ thường, "-"), cho tới khi người dùng tự sửa địa chỉ.
        // Tên nhóm đổi.
        newGroupField.textProperty().addListener((observable, old, name) -> {
            // Chưa tự sửa địa chỉ: tự điền slug của tên (bật cờ fillingAddress trong lúc điền).
            if (!addressEdited) {
                fillingAddress = true;
                newAddressField.setText(name == null || name.isBlank() ? "" : GroupAddresses.slug(name));
                fillingAddress = false;
            }
        });
        // Ô địa chỉ đổi.
        newAddressField.textProperty().addListener((observable, old, value) -> {
            // Do người dùng sửa: có nội dung thì coi là đã tự sửa (xoá trắng thì tự điền lại).
            if (!fillingAddress) {
                addressEdited = !value.isBlank();
            }
            // Hợp lệ khi trống hoặc chuẩn hoá được; đổi lời nhắc và màu theo kết quả.
            boolean valid = value.isBlank() || GroupAddresses.normalizeLocalPart(value) != null;
            addressHint.setText(valid ? "Địa chỉ tự sinh từ tên nhóm, có thể sửa trước khi tạo."
                    : "Chỉ dùng chữ không dấu, số, dấu chấm hoặc gạch ngang (VD lop-ltm).");
            addressHint.getStyleClass().setAll("label", valid ? "hint" : "error-text");
        });
        // Tải danh sách nhóm.
        load(null);
    }

    // Tải danh sách nhóm (GET_MY_GROUPS), chọn lại nhóm selectGroupId nếu có.
    private void load(Integer selectGroupId) {
        Fx.then(api.groups(), groups -> {
            // Đặt danh sách; hiện khung trống nếu không có nhóm.
            groupList.getItems().setAll(groups);
            groupsEmpty.setVisible(groups.isEmpty());
            // Tìm nhóm theo id và chọn.
            if (selectGroupId != null) {
                groups.stream().filter(group -> group.getGroupId() == selectGroupId).findFirst()
                        .ifPresent(group -> groupList.getSelectionModel().select(group));
            }
            // Không có nhóm nào được chọn: ẩn chi tiết.
            if (groupList.getSelectionModel().getSelectedItem() == null) {
                showGroup(null);
            }
        // Lỗi: hiện toast.
        }, error -> Toast.error(root, error));
    }

    // Hiện chi tiết nhóm (null: hiện khung "Chọn một nhóm").
    private void showGroup(MailGroupDTO group) {
        detailBox.setVisible(group != null);
        detailEmpty.setVisible(group == null);
        if (group == null) {
            return;
        }
        // Tên nhóm; địa chỉ (hoặc "chưa có"); nút sao chép chỉ hiện khi có địa chỉ; chủ nhóm.
        groupNameLabel.setText(group.getGroupName());
        boolean hasAddress = group.getGroupAddress() != null && !group.getGroupAddress().isBlank();
        groupAddressLabel.setText(hasAddress ? group.getGroupAddress() : "(chưa có địa chỉ)");
        copyAddressButton.setVisible(hasAddress);
        ownerLabel.setText("Chủ nhóm: " + group.getOwnerEmail());
        // Danh sách thành viên và số lượng.
        List<String> members = group.getMemberEmails() == null ? List.of() : group.getMemberEmails();
        memberList.getItems().setAll(members);
        memberCountLabel.setText(members.size() + " THÀNH VIÊN");
        // Hàng thêm thành viên chỉ hiện với chủ nhóm.
        addMemberRow.setVisible(group.isOwner());
        addMemberRow.setManaged(group.isOwner());
        updateButtons();
    }

    // Ẩn / hiện / khoá các nút theo quyền.
    private void updateButtons() {
        // Nhóm đang chọn, mình có là chủ không, thành viên đang chọn, email của mình.
        MailGroupDTO group = groupList.getSelectionModel().getSelectedItem();
        boolean owner = group != null && group.isOwner();
        String member = memberList.getSelectionModel().getSelectedItem();
        String me = Session.account() == null ? "" : Session.account().getEmail();
        // Nút Xoá thành viên: chỉ chủ nhóm thấy; khoá khi chưa chọn ai hoặc chọn chính mình.
        setShown(removeMemberButton, owner);
        removeMemberButton.setDisable(member == null || member.equalsIgnoreCase(me));
        // Nút Xoá nhóm: chỉ chủ nhóm; nút Rời nhóm: chỉ thành viên thường.
        setShown(deleteButton, owner);
        setShown(leaveButton, group != null && !owner);
    }

    /** Bấm "Tạo nhóm". */
    @FXML
    public void handleCreate() {
        // Tên nhóm (bỏ khoảng trắng); trống thì báo lỗi.
        String name = newGroupField.getText() == null ? "" : newGroupField.getText().trim();
        if (name.isEmpty()) {
            Toast.error(root, "Nhập tên nhóm.");
            return;
        }
        // Tên không được có @ hoặc dấu phẩy (dễ nhầm với địa chỉ khi soạn thư).
        if (name.contains("@") || name.contains(",")) {
            Toast.error(root, "Tên nhóm không được chứa @ hoặc dấu phẩy.");
            return;
        }
        // Địa chỉ nếu nhập phải hợp lệ.
        String address = newAddressField.getText() == null ? "" : newAddressField.getText().trim();
        if (!address.isEmpty() && GroupAddresses.normalizeLocalPart(address) == null) {
            Toast.error(root, "Địa chỉ nhóm chỉ dùng chữ không dấu, số, dấu chấm hoặc gạch ngang.");
            newAddressField.requestFocus();
            return;
        }
        // Người dùng không tự sửa địa chỉ: gửi rỗng để Server tự chọn địa chỉ còn trống (lop-ltm-2...).
        run(api.createGroup(name, addressEdited ? address : ""), groupId -> {
            // Thành công: xoá các ô, báo, tải lại và chọn nhóm mới.
            newGroupField.clear();
            addressEdited = false;
            newAddressField.clear();
            Toast.success(root, "Đã tạo nhóm \"" + name + "\".");
            load(groupId);
        });
    }

    /** Bấm nút sao chép địa chỉ nhóm vào clipboard. */
    @FXML
    public void handleCopyAddress() {
        MailGroupDTO group = groupList.getSelectionModel().getSelectedItem();
        if (group != null && group.getGroupAddress() != null) {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(group.getGroupAddress());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            Toast.success(root, "Đã sao chép " + group.getGroupAddress());
        }
    }

    /** Bấm "Thêm" thành viên (chỉ chủ nhóm thấy). */
    @FXML
    public void handleAddMember() {
        // Nhóm đang chọn và email cần thêm; thiếu thì thôi.
        MailGroupDTO group = groupList.getSelectionModel().getSelectedItem();
        String email = memberEmailField.getText() == null ? "" : memberEmailField.getText().trim();
        if (group == null || email.isEmpty()) {
            return;
        }
        // Gửi ADD_MEMBER; thành công thì xoá ô, báo, tải lại.
        run(api.addMember(group.getGroupId(), email), ignored -> {
            memberEmailField.clear();
            Toast.success(root, "Đã thêm " + email + ".");
            load(group.getGroupId());
        });
    }

    /** Bấm "Xoá thành viên". */
    @FXML
    public void handleRemoveMember() {
        // Thiếu nhóm / thành viên, hoặc người dùng không xác nhận: thôi.
        MailGroupDTO group = groupList.getSelectionModel().getSelectedItem();
        String member = memberList.getSelectionModel().getSelectedItem();
        if (group == null || member == null || !Dialogs.confirm(window(), "Xoá thành viên?",
                "Xoá " + member + " khỏi nhóm " + group.getGroupName() + "?", "Xoá", true)) {
            return;
        }
        // Gửi REMOVE_MEMBER; thành công thì báo và tải lại.
        run(api.removeMember(group.getGroupId(), member), ignored -> {
            Toast.success(root, "Đã xoá " + member + " khỏi nhóm.");
            load(group.getGroupId());
        });
    }

    /** Bấm "Rời nhóm" (thành viên thường). */
    @FXML
    public void handleLeave() {
        // Hỏi xác nhận.
        MailGroupDTO group = groupList.getSelectionModel().getSelectedItem();
        if (group == null || !Dialogs.confirm(window(), "Rời nhóm?",
                "Bạn sẽ không nhận thư gửi tới nhóm " + group.getGroupName() + " nữa.", "Rời nhóm", true)) {
            return;
        }
        // Gửi LEAVE_GROUP; thành công thì báo và tải lại (không chọn nhóm nào).
        run(api.leaveGroup(group.getGroupId()), ignored -> {
            Toast.success(root, "Đã rời nhóm " + group.getGroupName() + ".");
            load(null);
        });
    }

    /** Bấm "Xoá nhóm" (chủ nhóm). */
    @FXML
    public void handleDelete() {
        // Hỏi xác nhận.
        MailGroupDTO group = groupList.getSelectionModel().getSelectedItem();
        if (group == null || !Dialogs.confirm(window(), "Xoá nhóm " + group.getGroupName() + "?",
                "Nhóm và danh sách thành viên sẽ bị xoá. Thư đã gửi trước đây vẫn được giữ.", "Xoá nhóm", true)) {
            return;
        }
        // Gửi DELETE_GROUP; thành công thì báo và tải lại.
        run(api.deleteGroup(group.getGroupId()), ignored -> {
            Toast.success(root, "Đã xoá nhóm " + group.getGroupName() + ".");
            load(null);
        });
    }

    // Chạy request: thành công gọi onSuccess (luồng giao diện), lỗi hiện toast.
    private <T> void run(CompletableFuture<T> request, java.util.function.Consumer<T> onSuccess) {
        Fx.then(request, onSuccess, error -> Toast.error(root, error));
    }

    // Cửa sổ chứa màn hình này (làm cha cho hộp thoại).
    private javafx.stage.Window window() {
        return root.getScene().getWindow();
    }

    // Ẩn / hiện nút và không chiếm chỗ khi ẩn.
    private static void setShown(Button button, boolean shown) {
        button.setVisible(shown);
        button.setManaged(shown);
    }
}
