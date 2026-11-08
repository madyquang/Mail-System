// Gói "dao.jdbc": các lớp DAO viết bằng JDBC thuần.
package com.mailsystem.server.dao.jdbc;

// Interface mà lớp này thực hiện.
import com.mailsystem.server.dao.FolderDAO;

// DTO thư mục; kết nối CSDL.
import com.mailsystem.common.model.Folder;
import com.mailsystem.server.db.DatabaseConnection;

// Các lớp JDBC; danh sách, Optional.
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Truy cập bảng folder bằng JDBC. Mọi câu lệnh đều có điều kiện account_id. */
public class JdbcFolderDAO implements FolderDAO {

    @Override
    public void createDefaultFolders(int accountId) throws SQLException {
        // Mượn kết nối riêng rồi gọi bản dùng kết nối.
        try (Connection connection = DatabaseConnection.getConnection()) {
            createDefaultFolders(connection, accountId);
        }
    }

    @Override
    public void createDefaultFolders(Connection connection, int accountId) throws SQLException {
        String sql = "INSERT INTO folder (account_id, folder_name, folder_type) VALUES (?, ?, ?)";
        // 3 thư mục mặc định: {tên hiển thị, loại}.
        String[][] defaults = {
                { "Hộp thư đến", "INBOX" },
                { "Đã gửi", "SENT" },
                { "Thùng rác", "TRASH" }
        };
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String[] folder : defaults) {
                statement.setInt(1, accountId);
                statement.setString(2, folder[0]);
                statement.setString(3, folder[1]);
                // Gom vào lô.
                statement.addBatch();
            }
            // Chạy cả lô một lần.
            statement.executeBatch();
        }
    }

    @Override
    public List<Folder> findFoldersByAccount(int accountId) throws SQLException {
        // LEFT JOIN để thư mục rỗng vẫn có mặt với unread_count = 0.
        // Chỉ đếm bản thư chưa đọc, chưa xoá, của chính tài khoản.
        String sql = "SELECT f.folder_id, f.folder_name, f.folder_type, COUNT(mr.entry_id) AS unread_count "
                + "FROM folder f "
                + "LEFT JOIN mail_recipient mr ON mr.folder_id = f.folder_id AND mr.account_id = f.account_id "
                + "AND mr.is_read = FALSE AND mr.is_deleted = FALSE "
                + "WHERE f.account_id = ? "
                + "GROUP BY f.folder_id, f.folder_name, f.folder_type "
                + "ORDER BY f.folder_id";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                List<Folder> folders = new ArrayList<>();
                while (result.next()) {
                    folders.add(readFolder(result));
                }
                return folders;
            }
        }
    }

    @Override
    public Optional<Integer> findFolderIdByType(int accountId, String folderType) throws SQLException {
        // Mượn kết nối riêng rồi gọi bản dùng kết nối.
        try (Connection connection = DatabaseConnection.getConnection()) {
            return findFolderIdByType(connection, accountId, folderType);
        }
    }

    @Override
    public Optional<Integer> findFolderIdByType(Connection connection, int accountId, String folderType)
            throws SQLException {
        // Thư mục theo loại (INBOX/SENT/TRASH) của tài khoản.
        String sql = "SELECT folder_id FROM folder WHERE account_id = ? AND folder_type = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, accountId);
            statement.setString(2, folderType);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getInt("folder_id")) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<Folder> findById(int accountId, int folderId) throws SQLException {
        // Không cần đếm thư chưa đọc ở đây nên trả 0 cố định.
        String sql = "SELECT folder_id, folder_name, folder_type, 0 AS unread_count FROM folder "
                + "WHERE account_id = ? AND folder_id = ?";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, accountId);
            statement.setInt(2, folderId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readFolder(result)) : Optional.empty();
            }
        }
    }

    @Override
    public int countCustom(int accountId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM folder WHERE account_id = ? AND folder_type = 'CUSTOM'";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                // COUNT(*) luôn trả đúng một dòng.
                result.next();
                return result.getInt(1);
            }
        }
    }

    @Override
    public int createCustom(int accountId, String name) throws SQLException {
        String sql = "INSERT INTO folder (account_id, folder_name, folder_type) VALUES (?, ?, 'CUSTOM')";
        // RETURN_GENERATED_KEYS: lấy folder_id vừa tạo.
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, accountId);
            statement.setString(2, name);
            // Trùng tên sẽ ném SQLException (ràng buộc UNIQUE account_id + folder_name).
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    @Override
    public boolean renameCustom(int accountId, int folderId, String name) throws SQLException {
        // Chỉ đổi được thư mục CUSTOM của chính tài khoản.
        String sql = "UPDATE folder SET folder_name = ? WHERE account_id = ? AND folder_id = ? AND folder_type = 'CUSTOM'";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            statement.setInt(2, accountId);
            statement.setInt(3, folderId);
            return statement.executeUpdate() > 0;
        }
    }

    @Override
    public int deleteCustom(int accountId, int folderId) throws SQLException {
        // folder_id là khoá ngoại ON DELETE CASCADE: phải chuyển thư đi TRƯỚC khi xoá thư mục,
        // nếu không MySQL sẽ xoá luôn các bản thư bên trong.
        // original_folder_id = NULL: thư mục cũ sắp mất, nên khôi phục sẽ về INBOX/SENT mặc định.
        String moveToTrash = "UPDATE mail_recipient mr "
                + "JOIN folder trash ON trash.account_id = mr.account_id AND trash.folder_type = 'TRASH' "
                + "SET mr.folder_id = trash.folder_id, mr.original_folder_id = NULL "
                + "WHERE mr.account_id = ? AND mr.folder_id = ?";
        // Chỉ xoá thư mục CUSTOM của chính tài khoản.
        String delete = "DELETE FROM folder WHERE account_id = ? AND folder_id = ? AND folder_type = 'CUSTOM'";
        try (Connection connection = DatabaseConnection.getConnection()) {
            // Hai câu lệnh trong một transaction.
            connection.setAutoCommit(false);
            try {
                int moved;
                // Bước 1: chuyển thư vào Thùng rác.
                try (PreparedStatement statement = connection.prepareStatement(moveToTrash)) {
                    statement.setInt(1, accountId);
                    statement.setInt(2, folderId);
                    moved = statement.executeUpdate();
                }
                int deleted;
                // Bước 2: xoá thư mục.
                try (PreparedStatement statement = connection.prepareStatement(delete)) {
                    statement.setInt(1, accountId);
                    statement.setInt(2, folderId);
                    deleted = statement.executeUpdate();
                }
                // Không xoá được (không phải CUSTOM của mình): huỷ cả việc chuyển thư, trả -1.
                if (deleted == 0) {
                    connection.rollback();
                    return -1;
                }
                connection.commit();
                // Trả số thư đã chuyển vào Thùng rác.
                return moved;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    // Đọc dòng hiện tại thành Folder.
    private Folder readFolder(ResultSet result) throws SQLException {
        Folder folder = new Folder(result.getInt("folder_id"), result.getString("folder_name"),
                result.getString("folder_type"));
        folder.setUnreadCount(result.getInt("unread_count"));
        return folder;
    }
}
