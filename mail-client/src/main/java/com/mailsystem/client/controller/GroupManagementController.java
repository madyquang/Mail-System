package com.mailsystem.client.controller;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mailsystem.client.MailClientApp;
import com.mailsystem.client.network.ServerConnection;
import com.mailsystem.common.model.MailGroupDTO;
import com.mailsystem.common.protocol.Command;
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

public class GroupManagementController {

    @FXML
    private ListView<MailGroupDTO> groupListView;
    @FXML
    private ListView<String> memberListView;
    @FXML
    private TextField newGroupNameField;
    @FXML
    private TextField memberEmailField;
    @FXML
    private Label groupNameLabel;
    @FXML
    private Label groupInfoLabel;
    @FXML
    private Label statusLabel;
    @FXML
    private Button addMemberButton;
    @FXML
    private Button removeMemberButton;
    @FXML
    private Button leaveGroupButton;
    @FXML
    private Button deleteGroupButton;

    @FXML
    public void initialize() {
        groupListView.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(MailGroupDTO group, boolean empty) {
                super.updateItem(group, empty);
                setText(empty || group == null ? null
                        : group.getGroupName() + (group.isOwner() ? "  ·  Chủ nhóm" : ""));
            }
        });
        groupListView.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> showGroup(selected));
        memberListView.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> updateActions(groupListView.getSelectionModel().getSelectedItem()));
        loadGroups(null);
    }

    @FXML
    public void handleCreateGroup() {
        String groupName = newGroupNameField.getText() == null ? "" : newGroupNameField.getText().trim();
        if (groupName.isBlank()) {
            setStatus("Nhập tên nhóm cần tạo.");
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("groupName", groupName);
        request(Command.CREATE_GROUP, payload, (data, error) -> {
            if (error != null) {
                setStatus("Không thể tạo nhóm: " + error);
                return;
            }
            newGroupNameField.clear();
            setStatus("Đã tạo nhóm và thêm bạn làm chủ nhóm.");
            loadGroups(readGroupId(data));
        });
    }

    @FXML
    public void handleAddMember() {
        MailGroupDTO selected = groupListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            setStatus("Chọn nhóm cần thêm thành viên.");
            return;
        }
        String email = memberEmailField.getText() == null ? "" : memberEmailField.getText().trim();
        if (email.isBlank()) {
            setStatus("Nhập email của thành viên.");
            return;
        }
        JsonObject payload = groupPayload(selected);
        payload.addProperty("email", email);
        request(Command.ADD_MEMBER, payload, (data, error) -> {
            if (error != null) {
                setStatus("Không thể thêm thành viên: " + error);
                return;
            }
            memberEmailField.clear();
            setStatus("Đã thêm thành viên.");
            loadGroups(selected.getGroupId());
        });
    }

    @FXML
    public void handleRemoveMember() {
        MailGroupDTO selected = groupListView.getSelectionModel().getSelectedItem();
        String email = memberListView.getSelectionModel().getSelectedItem();
        if (selected == null || email == null) {
            setStatus("Chọn thành viên cần xóa khỏi nhóm.");
            return;
        }
        JsonObject payload = groupPayload(selected);
        payload.addProperty("email", email);
        request(Command.REMOVE_MEMBER, payload, (data, error) -> {
            if (error != null) {
                setStatus("Không thể xóa thành viên: " + error);
                return;
            }
            setStatus("Đã xóa thành viên khỏi nhóm.");
            loadGroups(selected.getGroupId());
        });
    }

    @FXML
    public void handleLeaveGroup() {
        MailGroupDTO selected = groupListView.getSelectionModel().getSelectedItem();
        if (selected == null || !confirm("Rời khỏi nhóm \"" + selected.getGroupName() + "\"?")) {
            return;
        }
        request(Command.LEAVE_GROUP, groupPayload(selected), (data, error) -> {
            if (error != null) {
                setStatus("Không thể rời nhóm: " + error);
                return;
            }
            setStatus("Đã rời nhóm.");
            loadGroups(null);
        });
    }

    @FXML
    public void handleDeleteGroup() {
        MailGroupDTO selected = groupListView.getSelectionModel().getSelectedItem();
        if (selected == null || !confirm("Xóa nhóm \"" + selected.getGroupName()
                + "\" và toàn bộ danh sách thành viên?")) {
            return;
        }
        request(Command.DELETE_GROUP, groupPayload(selected), (data, error) -> {
            if (error != null) {
                setStatus("Không thể xóa nhóm: " + error);
                return;
            }
            setStatus("Đã xóa nhóm.");
            loadGroups(null);
        });
    }

    @FXML
    public void handleBack() {
        MailClientApp.switchScene("/fxml/inbox.fxml");
    }

    private void loadGroups(Integer selectedGroupId) {
        request(Command.GET_MY_GROUPS, new JsonObject(), (data, error) -> {
            if (error != null) {
                setStatus("Không tải được danh sách nhóm: " + error);
                return;
            }
            if (data == null || !data.isJsonArray()) {
                setStatus("Máy chủ chưa trả danh sách nhóm hợp lệ.");
                return;
            }
            groupListView.getItems().clear();
            for (JsonElement element : data.getAsJsonArray()) {
                groupListView.getItems().add(ProtocolUtil.fromJson(element.toString(), MailGroupDTO.class));
            }
            MailGroupDTO toSelect = groupListView.getItems().stream()
                    .filter(group -> selectedGroupId != null && group.getGroupId() == selectedGroupId)
                    .findFirst().orElse(groupListView.getItems().isEmpty() ? null : groupListView.getItems().get(0));
            groupListView.getSelectionModel().select(toSelect);
            if (toSelect == null) {
                showGroup(null);
                setStatus("Bạn chưa tham gia nhóm thư nào.");
            }
        });
    }

    private void showGroup(MailGroupDTO group) {
        if (group == null) {
            groupNameLabel.setText("Chọn một nhóm");
            groupInfoLabel.setText("");
            memberListView.getItems().clear();
            updateActions(null);
            return;
        }
        groupNameLabel.setText(group.getGroupName());
        groupInfoLabel.setText("Chủ nhóm: " + group.getOwnerEmail());
        memberListView.getItems().setAll(
                group.getMemberEmails() == null ? java.util.List.of() : group.getMemberEmails());
        updateActions(group);
    }

    private void updateActions(MailGroupDTO group) {
        boolean selected = group != null;
        boolean owner = selected && group.isOwner();
        String selectedMember = memberListView.getSelectionModel().getSelectedItem();
        boolean ownerSelected = owner && selectedMember != null
                && selectedMember.equalsIgnoreCase(group.getOwnerEmail());
        addMemberButton.setDisable(!owner);
        removeMemberButton.setDisable(!owner || selectedMember == null || ownerSelected);
        leaveGroupButton.setDisable(!selected || owner);
        deleteGroupButton.setDisable(!owner);
    }

    private JsonObject groupPayload(MailGroupDTO group) {
        JsonObject payload = new JsonObject();
        payload.addProperty("groupId", group.getGroupId());
        return payload;
    }

    private Integer readGroupId(JsonElement data) {
        if (data == null || !data.isJsonObject() || !data.getAsJsonObject().has("groupId")) {
            return null;
        }
        try {
            int groupId = data.getAsJsonObject().get("groupId").getAsInt();
            return groupId > 0 ? groupId : null;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private boolean confirm(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.CANCEL, ButtonType.OK);
        alert.setHeaderText("Xác nhận thao tác");
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
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

    private void setStatus(String message) {
        statusLabel.setText(message);
    }

    @FunctionalInterface
    private interface RequestCallback {
        void complete(JsonElement data, String error);
    }
}
