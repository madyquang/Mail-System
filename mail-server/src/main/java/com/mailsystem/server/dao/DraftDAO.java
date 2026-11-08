// Gói "dao": các interface truy cập CSDL (lớp thực hiện bằng JDBC nằm ở dao.jdbc).
package com.mailsystem.server.dao;

// Draft: dữ liệu một bản nháp.
import com.mailsystem.common.model.Draft;

// SQLException: lỗi khi chạy câu lệnh SQL.
import java.sql.SQLException;
// List: danh sách có thứ tự.
import java.util.List;
// Optional: kết quả "có thể không có".
import java.util.Optional;

/** DAO cho bảng `draft` (thư nháp). Mọi thao tác đều giới hạn theo accountId chủ sở hữu. Lớp thực hiện: JdbcDraftDAO. */
public interface DraftDAO {

    /**
     * Thêm bản nháp mới.
     *
     * @return draft_id vừa được tạo
     */
    int insert(int accountId, Draft draft) throws SQLException;

    /** @return false nếu bản nháp không tồn tại hoặc không thuộc tài khoản */
    boolean update(int accountId, Draft draft) throws SQLException;

    /** Mọi bản nháp của tài khoản (mới sửa xếp trước). */
    List<Draft> findByAccount(int accountId) throws SQLException;

    /** Một bản nháp của tài khoản; rỗng nếu không có hoặc không thuộc tài khoản. */
    Optional<Draft> findById(int accountId, int draftId) throws SQLException;

    /** @return số bản nháp đã xoá */
    int delete(int accountId, List<Integer> draftIds) throws SQLException;
}
