package com.mailsystem.client.controller;

import com.mailsystem.client.api.MailApi;
import com.mailsystem.client.app.Session;
import com.mailsystem.client.ui.ContactSuggestions;
import com.mailsystem.client.ui.Dialogs;
import com.mailsystem.client.ui.EmptyState;
import com.mailsystem.client.ui.Fx;
import com.mailsystem.client.ui.Icons;
import com.mailsystem.client.ui.Toast;
import com.mailsystem.common.model.MailGroupDTO;
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

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Quản lý nhóm thư: tạo nhóm, xem/thêm/xoá thành viên, rời hoặc xoá nhóm (theo quyền chủ nhóm). */
public class GroupsController {

    @FXML
    private StackPane root;
    @FXML
    private TextField newGroupField;
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

    private final MailApi api = MailApi.get();
    /** Cửa sổ Nhóm thư đang mở (nếu có), để EVENT GROUP_UPDATED tải lại danh sách. */
    private static java.lang.ref.WeakReference<GroupsController> open = new java.lang.ref.WeakReference<>(null);

    /** Gọi trên UI thread khi Server báo nhóm của mình thay đổi (được thêm, bị xoá, nhóm bị xoá...). */
    public static void refreshIfOpen() {
        GroupsController controller = open.get();
        if (controller != null && controller.root.getScene() != null
                && controller.root.getScene().getWindow() != null && controller.root.getScene().getWindow().isShowing()) {
            MailGroupDTO selected = controller.groupList.getSelectionModel().getSelectedItem();
            controller.load(selected == null ? null : selected.getGroupId());
        }
    }

    @FXML
    public void initialize() {
        open = new java.lang.ref.WeakReference<>(this);
        groupList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(MailGroupDTO group, boolean empty) {
                super.updateItem(group, empty);
                if (empty || group == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Label name = new Label(group.getGroupName());
                name.setStyle("-fx-font-weight: bold;");
                Label meta = new Label((group.isOwner() ? "Bạn là chủ nhóm · " : "")
                        + (group.getMemberEmails() == null ? 0 : group.getMemberEmails().size()) + " thành viên");
                meta.getStyleClass().add("hint");
                HBox row = new HBox(10, Icons.of(group.isOwner() ? Icons.SHIELD : Icons.GROUPS, 20), new VBox(2, name, meta));
                row.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(row);
            }
        });
        groupList.getSelectionModel().selectedItemProperty().addListener((observable, old, group) -> showGroup(group));
        memberList.getSelectionModel().selectedItemProperty().addListener((observable, old, email) -> updateButtons());
        groupsEmpty.getChildren().setAll(new EmptyState().show(Icons.GROUPS, "Chưa có nhóm nào",
                "Tạo nhóm để gửi thư cho nhiều người cùng lúc."));
        detailEmpty.getChildren().setAll(new EmptyState().show(Icons.GROUPS, "Chọn một nhóm",
                "Thành viên và thao tác của nhóm sẽ hiện ở đây."));
        ContactSuggestions.forSinglePerson(memberEmailField, null);
        load(null);
    }

    private void load(Integer selectGroupId) {
        Fx.then(api.groups(), groups -> {
            groupList.getItems().setAll(groups);
            groupsEmpty.setVisible(groups.isEmpty());
            if (selectGroupId != null) {
                groups.stream().filter(group -> group.getGroupId() == selectGroupId).findFirst()
                        .ifPresent(group -> groupList.getSelectionModel().select(group));
            }
            if (groupList.getSelectionModel().getSelectedItem() == null) {
                showGroup(null);
            }
        }, error -> Toast.error(root, error));
    }

    private void showGroup(MailGroupDTO group) {
        detailBox.setVisible(group != null);
        detailEmpty.setVisible(group == null);
        if (group == null) {
            return;
        }
        groupNameLabel.setText(group.getGroupName());
        ownerLabel.setText("Chủ nhóm: " + group.getOwnerEmail());
        List<String> members = group.getMemberEmails() == null ? List.of() : group.getMemberEmails();
        memberList.getItems().setAll(members);
        memberCountLabel.setText(members.size() + " THÀNH VIÊN");
        addMemberRow.setVisible(group.isOwner());
        addMemberRow.setManaged(group.isOwner());
        updateButtons();
    }

    private void updateButtons() {
        MailGroupDTO group = groupList.getSelectionModel().getSelectedItem();
        boolean owner = group != null && group.isOwner();
        String member = memberList.getSelectionModel().getSelectedItem();
        String me = Session.account() == null ? "" : Session.account().getEmail();
        setShown(removeMemberButton, owner);
        removeMemberButton.setDisable(member == null || member.equalsIgnoreCase(me));
        setShown(deleteButton, owner);
        setShown(leaveButton, group != null && !owner);
    }

    @FXML
    public void handleCreate() {
        String name = newGroupField.getText() == null ? "" : newGroupField.getText().trim();
        if (name.isEmpty()) {
            Toast.error(root, "Nhập tên nhóm.");
            return;
        }
        if (name.contains("@") || name.contains(",")) {
            Toast.error(root, "Tên nhóm không được chứa @ hoặc dấu phẩy.");
            return;
        }
        run(api.createGroup(name), groupId -> {
            newGroupField.clear();
            Toast.success(root, "Đã tạo nhóm \"" + name + "\".");
            load(groupId);
        });
    }

    @FXML
    public void handleAddMember() {
        MailGroupDTO group = groupList.getSelectionModel().getSelectedItem();
        String email = memberEmailField.getText() == null ? "" : memberEmailField.getText().trim();
        if (group == null || email.isEmpty()) {
            return;
        }
        run(api.addMember(group.getGroupId(), email), ignored -> {
            memberEmailField.clear();
            Toast.success(root, "Đã thêm " + email + ".");
            load(group.getGroupId());
        });
    }

    @FXML
    public void handleRemoveMember() {
        MailGroupDTO group = groupList.getSelectionModel().getSelectedItem();
        String member = memberList.getSelectionModel().getSelectedItem();
        if (group == null || member == null || !Dialogs.confirm(window(), "Xoá thành viên?",
                "Xoá " + member + " khỏi nhóm " + group.getGroupName() + "?", "Xoá", true)) {
            return;
        }
        run(api.removeMember(group.getGroupId(), member), ignored -> {
            Toast.success(root, "Đã xoá " + member + " khỏi nhóm.");
            load(group.getGroupId());
        });
    }

    @FXML
    public void handleLeave() {
        MailGroupDTO group = groupList.getSelectionModel().getSelectedItem();
        if (group == null || !Dialogs.confirm(window(), "Rời nhóm?",
                "Bạn sẽ không nhận thư gửi tới nhóm " + group.getGroupName() + " nữa.", "Rời nhóm", true)) {
            return;
        }
        run(api.leaveGroup(group.getGroupId()), ignored -> {
            Toast.success(root, "Đã rời nhóm " + group.getGroupName() + ".");
            load(null);
        });
    }

    @FXML
    public void handleDelete() {
        MailGroupDTO group = groupList.getSelectionModel().getSelectedItem();
        if (group == null || !Dialogs.confirm(window(), "Xoá nhóm " + group.getGroupName() + "?",
                "Nhóm và danh sách thành viên sẽ bị xoá. Thư đã gửi trước đây vẫn được giữ.", "Xoá nhóm", true)) {
            return;
        }
        run(api.deleteGroup(group.getGroupId()), ignored -> {
            Toast.success(root, "Đã xoá nhóm " + group.getGroupName() + ".");
            load(null);
        });
    }

    private <T> void run(CompletableFuture<T> request, java.util.function.Consumer<T> onSuccess) {
        Fx.then(request, onSuccess, error -> Toast.error(root, error));
    }

    private javafx.stage.Window window() {
        return root.getScene().getWindow();
    }

    private static void setShown(Button button, boolean shown) {
        button.setVisible(shown);
        button.setManaged(shown);
    }
}
