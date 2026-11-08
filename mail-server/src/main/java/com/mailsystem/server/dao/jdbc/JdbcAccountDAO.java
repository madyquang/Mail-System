// Gói "dao.jdbc": các lớp DAO viết bằng JDBC thuần.
package com.mailsystem.server.dao.jdbc;

// Interface mà lớp này thực hiện.
import com.mailsystem.server.dao.AccountDAO;

// DTO tài khoản; kết nối CSDL.
import com.mailsystem.common.model.Account;
import com.mailsystem.server.db.DatabaseConnection;

// Các lớp JDBC; Optional.
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/** Truy cập bảng account bằng JDBC. */
public class JdbcAccountDAO implements AccountDAO {

    @Override
    public int insertAccount(String email, String passwordHash, String displayName) throws SQLException {
        // Mượn kết nối riêng rồi gọi bản dùng kết nối.
        try (Connection connection = DatabaseConnection.getConnection()) {
            return insertAccount(connection, email, passwordHash, displayName);
        }
    }

    @Override
    public int insertAccount(Connection connection, String email, String passwordHash, String displayName)
            throws SQLException {
        String sql = "INSERT INTO account (email, password_hash, display_name) VALUES (?, ?, ?)";
        // RETURN_GENERATED_KEYS: lấy account_id vừa tạo.
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, email);
            statement.setString(2, passwordHash);
            statement.setString(3, displayName);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        }
        throw new SQLException("Database did not return the new account ID");
    }

    @Override
    public Optional<Account> findByEmail(String email) throws SQLException {
        // Chỉ lấy thông tin công khai (không lấy password_hash).
        String sql = "SELECT account_id, email, display_name FROM account WHERE email = ?";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readAccount(result)) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<String> findPasswordHashByEmail(String email) throws SQLException {
        String sql = "SELECT password_hash FROM account WHERE email = ?";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString("password_hash")) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<Account> findById(int accountId) throws SQLException {
        String sql = "SELECT account_id, email, display_name FROM account WHERE account_id = ?";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readAccount(result)) : Optional.empty();
            }
        }
    }

    @Override
    public boolean existsByEmail(String email) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection()) {
            return existsByEmail(connection, email);
        }
    }

    @Override
    public boolean existsByEmail(Connection connection, String email) throws SQLException {
        // Chỉ cần biết có dòng hay không: SELECT 1 ... LIMIT 1.
        String sql = "SELECT 1 FROM account WHERE email = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    @Override
    public Optional<String> findPasswordHashById(int accountId) throws SQLException {
        String sql = "SELECT password_hash FROM account WHERE account_id = ?";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString("password_hash")) : Optional.empty();
            }
        }
    }

    @Override
    public boolean updateDisplayName(int accountId, String displayName) throws SQLException {
        return update("UPDATE account SET display_name = ? WHERE account_id = ?", displayName, accountId);
    }

    @Override
    public boolean updatePasswordHash(int accountId, String passwordHash) throws SQLException {
        return update("UPDATE account SET password_hash = ? WHERE account_id = ?", passwordHash, accountId);
    }

    @Override
    public Optional<SecuritySettings> findSecuritySettings(int accountId) throws SQLException {
        String sql = "SELECT share_read_receipts, totp_enabled, totp_secret, totp_last_step FROM account WHERE account_id = ?";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                // getLong trả 0 khi cột NULL; wasNull() cho biết cột vừa đọc có NULL không.
                long step = result.getLong("totp_last_step");
                boolean noStep = result.wasNull();
                return Optional.of(new SecuritySettings(result.getBoolean("share_read_receipts"),
                        result.getBoolean("totp_enabled"), result.getString("totp_secret"), noStep ? null : step));
            }
        }
    }

    @Override
    public boolean updateShareReadReceipts(int accountId, boolean share) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE account SET share_read_receipts = ? WHERE account_id = ?")) {
            statement.setBoolean(1, share);
            statement.setInt(2, accountId);
            return statement.executeUpdate() > 0;
        }
    }

    @Override
    public boolean updateTotp(int accountId, String secret, boolean enabled) throws SQLException {
        // Đổi khoá TOTP thì xoá bước đã dùng (khoá mới bắt đầu lại).
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE account SET totp_secret = ?, totp_enabled = ?, totp_last_step = NULL WHERE account_id = ?")) {
            statement.setString(1, secret);
            statement.setBoolean(2, enabled);
            statement.setInt(3, accountId);
            return statement.executeUpdate() > 0;
        }
    }

    @Override
    public boolean claimTotpStep(int accountId, long step) throws SQLException {
        // Chỉ cập nhật khi bước mới lớn hơn bước đã dùng: một câu UPDATE nguyên tử,
        // hai lần đăng nhập cùng mã chạy song song thì chỉ một lần thành công.
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE account SET totp_last_step = ? WHERE account_id = ? "
                                + "AND (totp_last_step IS NULL OR totp_last_step < ?)")) {
            statement.setLong(1, step);
            statement.setInt(2, accountId);
            statement.setLong(3, step);
            // Có dòng bị sửa = mã hợp lệ và chưa dùng.
            return statement.executeUpdate() > 0;
        }
    }

    // Chạy câu UPDATE có 2 tham số (giá trị chuỗi, accountId). @return true nếu có dòng bị sửa.
    private boolean update(String sql, String value, int accountId) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            statement.setInt(2, accountId);
            return statement.executeUpdate() > 0;
        }
    }

    // Đọc dòng hiện tại thành Account.
    private Account readAccount(ResultSet result) throws SQLException {
        return new Account(result.getInt("account_id"), result.getString("email"),
                result.getString("display_name"));
    }
}
