// Gói "service": logic nghiệp vụ phía Server.
package com.mailsystem.server.service;

// JSON, DTO tài khoản, địa chỉ nhóm, Gson, câu trả lời.
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mailsystem.common.model.Account;
import com.mailsystem.common.protocol.GroupAddresses;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.ResponseMessage;
// DAO.
import com.mailsystem.server.dao.AccountDAO;
import com.mailsystem.server.dao.GroupDAO;
import com.mailsystem.server.dao.jdbc.JdbcAccountDAO;
import com.mailsystem.server.dao.jdbc.JdbcFolderDAO;
import com.mailsystem.server.dao.jdbc.JdbcMailDAO;
import com.mailsystem.server.dao.jdbc.JdbcGroupDAO;

// Lỗi CSDL, Optional.
import java.sql.SQLException;
import java.util.Optional;

/** Xử lý các lệnh về nhóm thư: tạo, xoá, thêm/xoá thành viên, rời nhóm, liệt kê. */
public class GroupService {

    // DAO nhóm và tài khoản.
    private final GroupDAO groupDAO;
    private final AccountDAO accountDAO;
    /** null = không gửi thông báo (test với DAO giả). */
    private final GroupNotifier notifier;

    /** Constructor dùng khi chạy thật: DAO bằng JDBC và có gửi thông báo. */
    public GroupService() {
        this(new JdbcGroupDAO(), new JdbcAccountDAO(),
                new GroupNotifier(new JdbcMailDAO(), new JdbcFolderDAO(), new JdbcAccountDAO()));
    }

    // Constructor cho unit test: không gửi thông báo.
    GroupService(GroupDAO groupDAO, AccountDAO accountDAO) {
        this(groupDAO, accountDAO, null);
    }

    // Constructor đầy đủ.
    GroupService(GroupDAO groupDAO, AccountDAO accountDAO, GroupNotifier notifier) {
        this.groupDAO = groupDAO;
        this.accountDAO = accountDAO;
        this.notifier = notifier;
    }

    /** Báo cho người bị ảnh hưởng (thư thông báo + EVENT); lỗi đọc DB ở đây không làm hỏng thao tác chính. */
    private void notifyMember(GroupNotifier.Action action, int groupId, String groupName, int actorId, int targetId) {
        // Không có notifier (test): bỏ qua.
        if (notifier == null) {
            return;
        }
        // Số thành viên chỉ cần khi báo "được thêm vào".
        int memberCount = 0;
        try {
            memberCount = action == GroupNotifier.Action.ADDED ? groupDAO.getMemberAccountIds(groupId).size() : 0;
        } catch (SQLException ignored) {
            // Không đếm được: thư thông báo không ghi số thành viên.
        }
        // Địa chỉ nhóm để ghi trong thư.
        String groupAddress = null;
        try {
            groupAddress = groupDAO.findGroupAddress(groupId).orElse(null);
        } catch (SQLException ignored) {
            // không đọc được địa chỉ: thư thông báo nhắc tên nhóm thay cho địa chỉ
        }
        notifier.notify(action, groupId, groupName, groupAddress, actorId, targetId, memberCount);
    }

    /** CREATE_GROUP {groupName, address?} → {groupId, groupAddress}. */
    public ResponseMessage createGroup(String requestId, Integer accountId, JsonElement payload) {
        // Phòng hờ (CommandRouter đã chặn khi chưa đăng nhập).
        if (accountId == null) {
            return ResponseMessage.error(requestId, "Bạn chưa đăng nhập.");
        }
        String groupName = readString(payload, "groupName").trim();
        // Tên rỗng, quá dài, hoặc chứa @ / dấu phẩy (dễ nhầm với địa chỉ / danh sách người nhận).
        if (groupName.isBlank() || groupName.length() > 100
                || groupName.contains("@") || groupName.contains(",")) {
            return ResponseMessage.error(requestId,
                    "Tên nhóm phải có từ 1 đến 100 ký tự, không chứa @ hoặc dấu phẩy.");
        }
        // Địa chỉ nhóm: người tạo tự nhập (phần trước @group.local) hoặc để trống = sinh từ tên.
        String requestedAddress = readString(payload, "address").trim();
        String localPart = null;
        if (!requestedAddress.isEmpty()) {
            // Chuẩn hoá và kiểm tra ký tự hợp lệ.
            localPart = GroupAddresses.normalizeLocalPart(requestedAddress);
            if (localPart == null) {
                return ResponseMessage.error(requestId, "Địa chỉ nhóm chỉ gồm chữ không dấu, số, dấu chấm hoặc gạch ngang "
                        + "(tối đa " + GroupAddresses.MAX_LOCAL_PART + " ký tự), VD lop-ltm.");
            }
        }
        try {
            // Tên nhóm phải duy nhất.
            if (groupDAO.findGroupIdByName(groupName).isPresent()) {
                return ResponseMessage.error(requestId, "Tên nhóm đã được sử dụng.");
            }
            String address;
            if (localPart != null) {
                // Người dùng tự chọn địa chỉ: trùng thì báo lỗi.
                address = GroupAddresses.address(localPart);
                if (groupDAO.findGroupIdByAddress(address).isPresent()) {
                    return ResponseMessage.error(requestId, "Địa chỉ nhóm " + address + " đã được dùng, hãy chọn địa chỉ khác.");
                }
            } else {
                // Không chọn: sinh từ tên, trùng thì thêm hậu tố -2, -3...
                address = freeAddress(GroupAddresses.slug(groupName));
            }
            // Tạo nhóm và thêm người tạo làm thành viên.
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
        // Thử địa chỉ gốc trước.
        String candidate = GroupAddresses.address(baseLocalPart);
        // Còn trùng thì tăng hậu tố.
        for (int suffix = 2; groupDAO.findGroupIdByAddress(candidate).isPresent(); suffix++) {
            String tail = "-" + suffix;
            // Cắt phần gốc nếu thêm hậu tố làm quá độ dài tối đa.
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
            // Mỗi nhóm chưa có địa chỉ: sinh địa chỉ còn trống từ tên rồi lưu.
            for (var group : groupDAO.findGroupsWithoutAddress().entrySet()) {
                groupDAO.updateGroupAddress(group.getKey(), freeAddress(GroupAddresses.slug(group.getValue())));
                filled++;
            }
        } catch (SQLException error) {
            logDatabaseError("backfillGroupAddresses", error);
        }
        return filled;
    }

    /** DELETE_GROUP {groupId}: chỉ chủ nhóm. */
    public ResponseMessage deleteGroup(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) {
            return ResponseMessage.error(requestId, "Bạn chưa đăng nhập.");
        }
        Integer groupId = readPositiveInt(payload, "groupId");
        if (groupId == null) {
            return ResponseMessage.error(requestId, "Nhóm thư không hợp lệ.");
        }
        try {
            // Nhóm có tồn tại và người gọi có phải chủ nhóm không.
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
            // Báo cho mọi thành viên trừ chủ nhóm.
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

    /** ADD_MEMBER {groupId, email}: chỉ chủ nhóm. */
    public ResponseMessage addMember(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) {
            return ResponseMessage.error(requestId, "Bạn chưa đăng nhập.");
        }
        Integer groupId = readPositiveInt(payload, "groupId");
        String email = readString(payload, "email").trim().toLowerCase(java.util.Locale.ROOT);
        // Kiểm tra mã nhóm và định dạng email.
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
            // Người được thêm phải có tài khoản.
            Optional<Account> member = accountDAO.findByEmail(email);
            if (member.isEmpty()) {
                return ResponseMessage.error(requestId, "Không tìm thấy tài khoản: " + email);
            }
            // Đã là thành viên.
            if (groupDAO.isMember(groupId, member.get().getAccountId())) {
                return ResponseMessage.error(requestId, "Tài khoản này đã là thành viên của nhóm.");
            }
            // Thêm rồi báo cho người được thêm.
            groupDAO.insertMember(groupId, member.get().getAccountId());
            notifyMember(GroupNotifier.Action.ADDED, groupId, groupDAO.findGroupName(groupId).orElse(""),
                    accountId, member.get().getAccountId());
            return ResponseMessage.ok(requestId, null);
        } catch (SQLException error) {
            // Thêm trùng cùng lúc: lỗi trùng khoá chính.
            if (isConstraintViolation(error)) {
                return ResponseMessage.error(requestId, "Tài khoản này đã là thành viên của nhóm.");
            }
            logDatabaseError("addMember", error);
            return ResponseMessage.error(requestId, "Không thể thêm thành viên vào nhóm.");
        }
    }

    /** REMOVE_MEMBER {groupId, email}: chỉ chủ nhóm, không xoá được chính chủ nhóm. */
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
            // Chủ nhóm không tự xoá mình (muốn thì xoá cả nhóm).
            if (ownerId.get().equals(memberId)) {
                return ResponseMessage.error(requestId, "Chủ nhóm không thể xóa chính mình khỏi nhóm.");
            }
            if (!groupDAO.isMember(groupId, memberId)) {
                return ResponseMessage.error(requestId, "Tài khoản này không phải thành viên của nhóm.");
            }
            // Xoá rồi báo cho người bị xoá.
            groupDAO.removeMember(groupId, memberId);
            notifyMember(GroupNotifier.Action.REMOVED, groupId, groupDAO.findGroupName(groupId).orElse(""),
                    accountId, memberId);
            return ResponseMessage.ok(requestId, null);
        } catch (SQLException error) {
            logDatabaseError("removeMember", error);
            return ResponseMessage.error(requestId, "Không thể xóa thành viên khỏi nhóm.");
        }
    }

    /** LEAVE_GROUP {groupId}: thành viên tự rời (chủ nhóm không được rời). */
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

    /** GET_MY_GROUPS → danh sách MailGroupDTO. */
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

    // Lỗi vi phạm ràng buộc (SQLState "23..."), duyệt cả chuỗi lỗi nối tiếp.
    private boolean isConstraintViolation(SQLException error) {
        for (SQLException current = error; current != null; current = current.getNextException()) {
            if (current.getSQLState() != null && current.getSQLState().startsWith("23")) {
                return true;
            }
        }
        return false;
    }

    // In lỗi CSDL kèm vết ngăn xếp.
    private void logDatabaseError(String operation, SQLException error) {
        System.err.println("[GroupService] " + operation + " failed: " + error.getMessage());
        error.printStackTrace(System.err);
    }

    // Đọc trường chuỗi trong payload; thiếu hoặc sai kiểu thì "".
    private String readString(JsonElement payload, String property) {
        if (payload == null || !payload.isJsonObject()) {
            return "";
        }
        JsonElement value = payload.getAsJsonObject().get(property);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : "";
    }

    // Đọc số nguyên dương trong payload; không hợp lệ thì null.
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
