// Gói "dao": các interface truy cập CSDL (lớp thực hiện bằng JDBC nằm ở dao.jdbc).
package com.mailsystem.server.dao;

// Account: thông tin công khai của tài khoản.
import com.mailsystem.common.model.Account;

// Connection: kết nối CSDL (để chạy trong transaction của nơi gọi).
import java.sql.Connection;
// SQLException: lỗi khi chạy câu lệnh SQL.
import java.sql.SQLException;
// Optional: kết quả "có thể không có".
import java.util.Optional;

/**
 * DAO cho bảng `account`. CHỈ chứa câu lệnh JDBC thuần, KHÔNG chứa logic
 * nghiệp vụ (VD: kiểm tra password đúng/sai là việc của AuthService, không
 * phải của DAO).
 *
 * Lớp thực hiện bằng JDBC (PreparedStatement): JdbcAccountDAO.
 */
public interface AccountDAO {

    /**
     * Insert account mới, trả về accountId vừa tạo (dùng
     * Statement.RETURN_GENERATED_KEYS).
     */
    int insertAccount(String email, String passwordHash, String displayName) throws SQLException;

    /** Như trên nhưng chạy trên kết nối (transaction) của nơi gọi. */
    int insertAccount(Connection connection, String email, String passwordHash, String displayName)
            throws SQLException;

    /**
     * Tìm account theo email. Dùng cho LOGIN và kiểm tra người nhận tồn tại khi
     * SEND_MAIL.
     */
    Optional<Account> findByEmail(String email) throws SQLException;

    /**
     * Lấy password_hash theo email, dùng riêng cho AuthService so sánh khi LOGIN.
     */
    Optional<String> findPasswordHashByEmail(String email) throws SQLException;

    /** Tìm account theo mã. */
    Optional<Account> findById(int accountId) throws SQLException;

    /** Email đã được đăng ký chưa. */
    boolean existsByEmail(String email) throws SQLException;

    /** Như trên nhưng chạy trên kết nối (transaction) của nơi gọi. */
    boolean existsByEmail(Connection connection, String email) throws SQLException;

    /** Lấy password_hash theo mã tài khoản (dùng khi đổi mật khẩu, tắt 2FA). */
    Optional<String> findPasswordHashById(int accountId) throws SQLException;

    /** Đổi tên hiển thị. @return false nếu không tìm thấy tài khoản */
    boolean updateDisplayName(int accountId, String displayName) throws SQLException;

    /** Đổi mật khẩu (lưu chuỗi băm mới). @return false nếu không tìm thấy tài khoản */
    boolean updatePasswordHash(int accountId, String passwordHash) throws SQLException;

    /**
     * Cài đặt bảo mật/riêng tư của tài khoản.
     *
     * @param shareReadReceipts có chia sẻ trạng thái đã đọc không
     * @param totpEnabled       đã bật 2FA chưa
     * @param totpSecret        khoá bí mật TOTP (null nếu chưa thiết lập)
     * @param totpLastStep      bước 30 giây đã dùng gần nhất (null nếu chưa dùng)
     */
    record SecuritySettings(boolean shareReadReceipts, boolean totpEnabled, String totpSecret, Long totpLastStep) {
    }

    /** Đọc cài đặt bảo mật của tài khoản. */
    Optional<SecuritySettings> findSecuritySettings(int accountId) throws SQLException;

    /** Bật/tắt chia sẻ trạng thái đã đọc. */
    boolean updateShareReadReceipts(int accountId, boolean share) throws SQLException;

    /** Lưu khoá TOTP (secret null = xoá) và trạng thái bật. */
    boolean updateTotp(int accountId, String secret, boolean enabled) throws SQLException;

    /**
     * Ghi nhận bước TOTP vừa dùng, chỉ thành công nếu lớn hơn bước dùng gần nhất
     * (nguyên tử trong một câu UPDATE): cùng một mã không dùng được hai lần.
     */
    boolean claimTotpStep(int accountId, long step) throws SQLException;
}
