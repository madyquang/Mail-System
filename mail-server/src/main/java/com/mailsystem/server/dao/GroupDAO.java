// Gói "dao": các interface truy cập CSDL (lớp thực hiện bằng JDBC nằm ở dao.jdbc).
package com.mailsystem.server.dao;

// MailGroupDTO: dữ liệu một nhóm + thành viên.
import com.mailsystem.common.model.MailGroupDTO;

// SQLException: lỗi khi chạy câu lệnh SQL.
import java.sql.SQLException;
// List: danh sách có thứ tự.
import java.util.List;
// Optional: kết quả "có thể không có".
import java.util.Optional;

/**
 * DAO cho bảng `mail_group`, `group_member`. Lớp thực hiện bằng JDBC: JdbcGroupDAO.
 */
public interface GroupDAO {

    /** Thêm nhóm (chưa có địa chỉ, chưa có thành viên). @return group_id mới */
    int insertGroup(String groupName, int ownerId) throws SQLException;

    /** Tạo nhóm kèm địa chỉ (<tên>@group.local) và thêm chủ nhóm làm thành viên, trong một giao dịch. */
    int createGroupWithOwner(String groupName, String groupAddress, int ownerId) throws SQLException;

    /** Nhóm có địa chỉ này (không phân biệt hoa thường). */
    Optional<Integer> findGroupIdByAddress(String groupAddress) throws SQLException;

    /** Các nhóm chưa có địa chỉ (nhóm tạo trước khi có tính năng): groupId -> tên nhóm. */
    java.util.Map<Integer, String> findGroupsWithoutAddress() throws SQLException;

    /** Gán địa chỉ cho nhóm. */
    void updateGroupAddress(int groupId, String groupAddress) throws SQLException;

    /** Địa chỉ của nhóm (rỗng nếu nhóm chưa có địa chỉ hoặc không tồn tại). */
    Optional<String> findGroupAddress(int groupId) throws SQLException;

    /** Thêm thành viên vào nhóm. */
    void insertMember(int groupId, int accountId) throws SQLException;

    /** Xoá thành viên khỏi nhóm. */
    void removeMember(int groupId, int accountId) throws SQLException;

    /** Xoá nhóm (thành viên bị xoá theo nhờ ON DELETE CASCADE). */
    void deleteGroup(int groupId) throws SQLException;

    /**
     * Trả về owner_id của group, dùng để kiểm tra quyền (chỉ owner mới được
     * xoá/thêm/bớt thành viên).
     */
    Optional<Integer> findOwnerId(int groupId) throws SQLException;

    /** Tên nhóm, dùng trong thư thông báo khi thêm / xoá thành viên. */
    Optional<String> findGroupName(int groupId) throws SQLException;

    /** Các nhóm mà tài khoản là thành viên (kèm danh sách email thành viên). */
    List<MailGroupDTO> findGroupsByMember(int accountId) throws SQLException;

    /**
     * Mở rộng 1 group thành danh sách accountId thành viên - dùng khi SEND_MAIL gửi
     * tới 1 group.
     */
    List<Integer> getMemberAccountIds(int groupId) throws SQLException;

    /** Tìm nhóm theo tên (gửi bằng tên nhóm vẫn được). */
    Optional<Integer> findGroupIdByName(String groupName) throws SQLException;

    /** Tài khoản có phải thành viên của nhóm không. */
    boolean isMember(int groupId, int accountId) throws SQLException;
}
