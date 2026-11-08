// Gói "dao": các interface truy cập CSDL (lớp thực hiện bằng JDBC nằm ở dao.jdbc).
package com.mailsystem.server.dao;

// Folder: dữ liệu một thư mục.
import com.mailsystem.common.model.Folder;

// Connection: kết nối CSDL (để chạy trong transaction của nơi gọi).
import java.sql.Connection;
// SQLException: lỗi khi chạy câu lệnh SQL.
import java.sql.SQLException;
// List: danh sách có thứ tự.
import java.util.List;
// Optional: kết quả "có thể không có".
import java.util.Optional;

/** DAO cho bảng `folder`. Lớp thực hiện bằng JDBC: JdbcFolderDAO. */
public interface FolderDAO {

    /** Tạo sẵn 3 folder mặc định (INBOX, SENT, TRASH) khi REGISTER thành công. */
    void createDefaultFolders(int accountId) throws SQLException;

    /** Như trên nhưng chạy trên kết nối (transaction) của nơi gọi. */
    void createDefaultFolders(Connection connection, int accountId) throws SQLException;

    /** Mọi thư mục của tài khoản, kèm số thư chưa đọc. */
    List<Folder> findFoldersByAccount(int accountId) throws SQLException;

    /**
     * Lấy folderId của 1 loại folder mặc định (VD: "INBOX") của 1 account -
     * cần dùng khi SEND_MAIL (bỏ vào SENT của sender, INBOX của recipient).
     */
    Optional<Integer> findFolderIdByType(int accountId, String folderType) throws SQLException;

    /** Như trên nhưng chạy trên kết nối (transaction) của nơi gọi. */
    Optional<Integer> findFolderIdByType(Connection connection, int accountId, String folderType)
            throws SQLException;

    /** Thư mục (bất kỳ loại) của tài khoản; rỗng nếu không thuộc tài khoản. */
    Optional<Folder> findById(int accountId, int folderId) throws SQLException;

    /** Số thư mục tự tạo (CUSTOM) của tài khoản. */
    int countCustom(int accountId) throws SQLException;

    /** Tạo thư mục tự tạo. @return folder_id mới */
    int createCustom(int accountId, String name) throws SQLException;

    /** Đổi tên thư mục tự tạo. @return false nếu không tìm thấy thư mục CUSTOM của tài khoản */
    boolean renameCustom(int accountId, int folderId, String name) throws SQLException;

    /** Chuyển thư trong thư mục vào Thùng rác rồi xoá thư mục (một transaction). @return số thư đã chuyển */
    int deleteCustom(int accountId, int folderId) throws SQLException;
}
