package com.mailsystem.server.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mailsystem.common.model.Account;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.ResponseMessage;
import com.mailsystem.server.dao.AccountDAO;
import com.mailsystem.server.dao.GroupDAO;
import com.mailsystem.server.dao.JdbcAccountDAO;
import com.mailsystem.server.dao.JdbcGroupDAO;

import java.sql.SQLException;
import java.util.Optional;

public class GroupService {

    private final GroupDAO groupDAO;
    private final AccountDAO accountDAO;

    public GroupService() {
        this(new JdbcGroupDAO(), new JdbcAccountDAO());
    }

    GroupService(GroupDAO groupDAO, AccountDAO accountDAO) {
        this.groupDAO = groupDAO;
        this.accountDAO = accountDAO;
    }

    public ResponseMessage createGroup(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) {
            return ResponseMessage.error(requestId, "Bạn chưa đăng nhập.");
        }
        String groupName = readString(payload, "groupName").trim();
        if (groupName.isBlank() || groupName.length() > 100
                || groupName.contains("@") || groupName.contains(",")) {
            return ResponseMessage.error(requestId,
                    "Tên nhóm phải có từ 1 đến 100 ký tự, không chứa @ hoặc dấu phẩy.");
        }
        try {
            if (groupDAO.findGroupIdByName(groupName).isPresent()) {
                return ResponseMessage.error(requestId, "Tên nhóm đã được sử dụng.");
            }
            int groupId = groupDAO.createGroupWithOwner(groupName, accountId);
            JsonObject data = new JsonObject();
            data.addProperty("groupId", groupId);
            return ResponseMessage.ok(requestId, data);
        } catch (SQLException error) {
            // Hai client tạo cùng tên gần như đồng thời: cả hai đều qua bước
            // kiểm tra ở trên, ràng buộc UNIQUE trong DB chặn bản ghi thứ hai.
            if (isConstraintViolation(error)) {
                return ResponseMessage.error(requestId, "Tên nhóm đã được sử dụng.");
            }
            logDatabaseError("createGroup", error);
            return ResponseMessage.error(requestId, "Không thể tạo nhóm thư.");
        }
    }

    public ResponseMessage deleteGroup(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) {
            return ResponseMessage.error(requestId, "Bạn chưa đăng nhập.");
        }
        Integer groupId = readPositiveInt(payload, "groupId");
        if (groupId == null) {
            return ResponseMessage.error(requestId, "Nhóm thư không hợp lệ.");
        }
        try {
            Optional<Integer> ownerId = groupDAO.findOwnerId(groupId);
            if (ownerId.isEmpty()) {
                return ResponseMessage.error(requestId, "Không tìm thấy nhóm thư.");
            }
            if (!ownerId.get().equals(accountId)) {
                return ResponseMessage.error(requestId, "Chỉ chủ nhóm mới được xóa nhóm.");
            }
            groupDAO.deleteGroup(groupId);
            return ResponseMessage.ok(requestId, null);
        } catch (SQLException error) {
            logDatabaseError("deleteGroup", error);
            return ResponseMessage.error(requestId, "Không thể xóa nhóm thư.");
        }
    }

    public ResponseMessage addMember(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) {
            return ResponseMessage.error(requestId, "Bạn chưa đăng nhập.");
        }
        Integer groupId = readPositiveInt(payload, "groupId");
        String email = readString(payload, "email").trim().toLowerCase(java.util.Locale.ROOT);
        if (groupId == null || email.isBlank() || email.length() > 150
                || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            return ResponseMessage.error(requestId, "Nhóm hoặc email thành viên không hợp lệ.");
        }
        try {
            Optional<Integer> ownerId = groupDAO.findOwnerId(groupId);
            if (ownerId.isEmpty()) {
                return ResponseMessage.error(requestId, "Không tìm thấy nhóm thư.");
            }
            if (!ownerId.get().equals(accountId)) {
                return ResponseMessage.error(requestId, "Chỉ chủ nhóm mới được thêm thành viên.");
            }
            Optional<Account> member = accountDAO.findByEmail(email);
            if (member.isEmpty()) {
                return ResponseMessage.error(requestId, "Không tìm thấy tài khoản: " + email);
            }
            if (groupDAO.isMember(groupId, member.get().getAccountId())) {
                return ResponseMessage.error(requestId, "Tài khoản này đã là thành viên của nhóm.");
            }
            groupDAO.insertMember(groupId, member.get().getAccountId());
            return ResponseMessage.ok(requestId, null);
        } catch (SQLException error) {
            if (isConstraintViolation(error)) {
                return ResponseMessage.error(requestId, "Tài khoản này đã là thành viên của nhóm.");
            }
            logDatabaseError("addMember", error);
            return ResponseMessage.error(requestId, "Không thể thêm thành viên vào nhóm.");
        }
    }

    public ResponseMessage removeMember(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) {
            return ResponseMessage.error(requestId, "Bạn chưa đăng nhập.");
        }
        Integer groupId = readPositiveInt(payload, "groupId");
        String email = readString(payload, "email").trim().toLowerCase(java.util.Locale.ROOT);
        if (groupId == null || email.isBlank()) {
            return ResponseMessage.error(requestId, "Nhóm hoặc thành viên không hợp lệ.");
        }
        try {
            Optional<Integer> ownerId = groupDAO.findOwnerId(groupId);
            if (ownerId.isEmpty()) {
                return ResponseMessage.error(requestId, "Không tìm thấy nhóm thư.");
            }
            if (!ownerId.get().equals(accountId)) {
                return ResponseMessage.error(requestId, "Chỉ chủ nhóm mới được xóa thành viên.");
            }
            Optional<Account> member = accountDAO.findByEmail(email);
            if (member.isEmpty()) {
                return ResponseMessage.error(requestId, "Không tìm thấy tài khoản thành viên.");
            }
            int memberId = member.get().getAccountId();
            if (ownerId.get().equals(memberId)) {
                return ResponseMessage.error(requestId, "Chủ nhóm không thể xóa chính mình khỏi nhóm.");
            }
            if (!groupDAO.isMember(groupId, memberId)) {
                return ResponseMessage.error(requestId, "Tài khoản này không phải thành viên của nhóm.");
            }
            groupDAO.removeMember(groupId, memberId);
            return ResponseMessage.ok(requestId, null);
        } catch (SQLException error) {
            logDatabaseError("removeMember", error);
            return ResponseMessage.error(requestId, "Không thể xóa thành viên khỏi nhóm.");
        }
    }

    public ResponseMessage leaveGroup(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) {
            return ResponseMessage.error(requestId, "Bạn chưa đăng nhập.");
        }
        Integer groupId = readPositiveInt(payload, "groupId");
        if (groupId == null) {
            return ResponseMessage.error(requestId, "Nhóm thư không hợp lệ.");
        }
        try {
            Optional<Integer> ownerId = groupDAO.findOwnerId(groupId);
            if (ownerId.isEmpty()) {
                return ResponseMessage.error(requestId, "Không tìm thấy nhóm thư.");
            }
            if (ownerId.get().equals(accountId)) {
                return ResponseMessage.error(requestId, "Chủ nhóm không thể rời nhóm; hãy xóa nhóm nếu cần.");
            }
            if (!groupDAO.isMember(groupId, accountId)) {
                return ResponseMessage.error(requestId, "Bạn không phải thành viên của nhóm này.");
            }
            groupDAO.removeMember(groupId, accountId);
            return ResponseMessage.ok(requestId, null);
        } catch (SQLException error) {
            logDatabaseError("leaveGroup", error);
            return ResponseMessage.error(requestId, "Không thể rời nhóm thư.");
        }
    }

    public ResponseMessage getMyGroups(String requestId, Integer accountId) {
        if (accountId == null) {
            return ResponseMessage.error(requestId, "Bạn chưa đăng nhập.");
        }
        try {
            var groups = groupDAO.findGroupsByMember(accountId);
            return ResponseMessage.ok(requestId, ProtocolUtil.getGson().toJsonTree(groups));
        } catch (SQLException error) {
            logDatabaseError("getMyGroups", error);
            return ResponseMessage.error(requestId, "Không thể tải danh sách nhóm thư.");
        }
    }

    private boolean isConstraintViolation(SQLException error) {
        for (SQLException current = error; current != null; current = current.getNextException()) {
            if (current.getSQLState() != null && current.getSQLState().startsWith("23")) {
                return true;
            }
        }
        return false;
    }

    private void logDatabaseError(String operation, SQLException error) {
        System.err.println("[GroupService] " + operation + " failed: " + error.getMessage());
        error.printStackTrace(System.err);
    }

    private String readString(JsonElement payload, String property) {
        if (payload == null || !payload.isJsonObject()) {
            return "";
        }
        JsonElement value = payload.getAsJsonObject().get(property);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : "";
    }

    private Integer readPositiveInt(JsonElement payload, String property) {
        if (payload == null || !payload.isJsonObject()) {
            return null;
        }
        JsonElement value = payload.getAsJsonObject().get(property);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            int number = value.getAsInt();
            return number > 0 ? number : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }
}
