package com.mailsystem.server.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mailsystem.common.model.Account;
import com.mailsystem.common.protocol.GroupAddresses;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.ResponseMessage;
import com.mailsystem.server.dao.AccountDAO;
import com.mailsystem.server.dao.GroupDAO;
import com.mailsystem.server.dao.jdbc.JdbcAccountDAO;
import com.mailsystem.server.dao.jdbc.JdbcFolderDAO;
import com.mailsystem.server.dao.jdbc.JdbcMailDAO;
import com.mailsystem.server.dao.jdbc.JdbcGroupDAO;

import java.sql.SQLException;
import java.util.Optional;

public class GroupService {

    private final GroupDAO groupDAO;
    private final AccountDAO accountDAO;
    /** null = không gửi thông báo (test với DAO giả). */
    private final GroupNotifier notifier;

    public GroupService() {
        this(new JdbcGroupDAO(), new JdbcAccountDAO(),
                new GroupNotifier(new JdbcMailDAO(), new JdbcFolderDAO(), new JdbcAccountDAO()));
    }

    GroupService(GroupDAO groupDAO, AccountDAO accountDAO) {
        this(groupDAO, accountDAO, null);
    }

    GroupService(GroupDAO groupDAO, AccountDAO accountDAO, GroupNotifier notifier) {
        this.groupDAO = groupDAO;
        this.accountDAO = accountDAO;
        this.notifier = notifier;
    }

    /** Báo cho người bị ảnh hưởng (thư thông báo + EVENT); lỗi đọc DB ở đây không làm hỏng thao tác chính. */
    private void notifyMember(GroupNotifier.Action action, int groupId, String groupName, int actorId, int targetId) {
        if (notifier == null) {
            return;
        }
        int memberCount = 0;
        try {
            memberCount = action == GroupNotifier.Action.ADDED ? groupDAO.getMemberAccountIds(groupId).size() : 0;
        } catch (SQLException ignored) {
        }
        String groupAddress = null;
        try {
            groupAddress = groupDAO.findGroupAddress(groupId).orElse(null);
        } catch (SQLException ignored) {
            // không đọc được địa chỉ: thư thông báo nhắc tên nhóm thay cho địa chỉ
        }
        notifier.notify(action, groupId, groupName, groupAddress, actorId, targetId, memberCount);
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
        // Địa chỉ nhóm: người tạo tự nhập (phần trước @group.local) hoặc để trống = sinh từ tên.
        String requestedAddress = readString(payload, "address").trim();
        String localPart = null;
        if (!requestedAddress.isEmpty()) {
            localPart = GroupAddresses.normalizeLocalPart(requestedAddress);
            if (localPart == null) {
                return ResponseMessage.error(requestId, "Địa chỉ nhóm chỉ gồm chữ không dấu, số, dấu chấm hoặc gạch ngang "
                        + "(tối đa " + GroupAddresses.MAX_LOCAL_PART + " ký tự), VD lop-ltm.");
            }
        }
        try {
            if (groupDAO.findGroupIdByName(groupName).isPresent()) {
                return ResponseMessage.error(requestId, "Tên nhóm đã được sử dụng.");
            }
            String address;
            if (localPart != null) {
                address = GroupAddresses.address(localPart);
                if (groupDAO.findGroupIdByAddress(address).isPresent()) {
                    return ResponseMessage.error(requestId, "Địa chỉ nhóm " + address + " đã được dùng, hãy chọn địa chỉ khác.");
                }
            } else {
                address = freeAddress(GroupAddresses.slug(groupName));
            }
            int groupId = groupDAO.createGroupWithOwner(groupName, address, accountId);
            JsonObject data = new JsonObject();
            data.addProperty("groupId", groupId);
            data.addProperty("groupAddress", address);
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

    /** "lop-ltm" đã có thì thử "lop-ltm-2", "lop-ltm-3"... */
    private String freeAddress(String baseLocalPart) throws SQLException {
        String candidate = GroupAddresses.address(baseLocalPart);
        for (int suffix = 2; groupDAO.findGroupIdByAddress(candidate).isPresent(); suffix++) {
            String tail = "-" + suffix;
            String base = baseLocalPart.length() + tail.length() > GroupAddresses.MAX_LOCAL_PART
                    ? baseLocalPart.substring(0, GroupAddresses.MAX_LOCAL_PART - tail.length()) : baseLocalPart;
            candidate = GroupAddresses.address(base + tail);
        }
        return candidate;
    }

    /** Lúc Server khởi động: nhóm tạo trước khi có địa chỉ được sinh địa chỉ từ tên. @return số nhóm đã điền */
    public int backfillGroupAddresses() {
        int filled = 0;
        try {
            for (var group : groupDAO.findGroupsWithoutAddress().entrySet()) {
                groupDAO.updateGroupAddress(group.getKey(), freeAddress(GroupAddresses.slug(group.getValue())));
                filled++;
            }
        } catch (SQLException error) {
            logDatabaseError("backfillGroupAddresses", error);
        }
        return filled;
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
            // Lấy tên + danh sách thành viên TRƯỚC khi xoá để còn báo cho họ.
            String groupName = groupDAO.findGroupName(groupId).orElse("");
            java.util.List<Integer> members = groupDAO.getMemberAccountIds(groupId);
            groupDAO.deleteGroup(groupId);
            for (int memberId : members) {
                if (memberId != accountId) {
                    notifyMember(GroupNotifier.Action.DELETED, groupId, groupName, accountId, memberId);
                }
            }
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
            notifyMember(GroupNotifier.Action.ADDED, groupId, groupDAO.findGroupName(groupId).orElse(""),
                    accountId, member.get().getAccountId());
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
            notifyMember(GroupNotifier.Action.REMOVED, groupId, groupDAO.findGroupName(groupId).orElse(""),
                    accountId, memberId);
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
            // Thành viên tự rời: báo chủ nhóm (chỉ EVENT, không gửi thư).
            notifyMember(GroupNotifier.Action.MEMBER_LEFT, groupId, groupDAO.findGroupName(groupId).orElse(""),
                    accountId, ownerId.get());
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
