// Gói "security": các cơ chế bảo mật phía Server.
package com.mailsystem.server.security;

// DatabaseConnection: lấy kết nối CSDL.
import com.mailsystem.server.db.DatabaseConnection;

// Các lớp JDBC.
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
// LocalDateTime: đọc cột DATETIME.
import java.time.LocalDateTime;
// Danh sách và bảng giữ thứ tự chèn (để JSON ra đúng thứ tự trường).
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thiết bị (chứng chỉ client trong mutual TLS) đã từng kết nối và trạng thái
 * phê duyệt. Mỗi Client tự sinh một cặp khoá riêng cho máy đó; Server ghi nhận
 * dấu vân tay chứng chỉ. Ở chế độ tls.clientAuth=need chỉ thiết bị được quản
 * trị viên phê duyệt (trên bảng giám sát) mới kết nối được: lộ mật khẩu mà
 * không có máy đã duyệt cũng không đăng nhập được.
 */
public final class DeviceRegistry {

    // Constructor private: lớp chỉ có hàm tĩnh.
    private DeviceRegistry() {
    }

    /** Ghi nhận thiết bị vừa kết nối. @return true nếu thiết bị đã được phê duyệt */
    public static boolean recordSeen(String fingerprint, String subject, String ip) {
        // Thêm thiết bị mới; nếu đã có (trùng dấu vân tay) thì cập nhật lần thấy cuối, IP, tên.
        String upsert = "INSERT INTO trusted_device (fingerprint, subject, last_ip) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE last_seen = CURRENT_TIMESTAMP, last_ip = VALUES(last_ip), subject = VALUES(subject)";
        try (Connection connection = DatabaseConnection.getConnection()) {
            // Bước 1: ghi nhận.
            try (PreparedStatement statement = connection.prepareStatement(upsert)) {
                statement.setString(1, fingerprint);
                // Cắt tên chứng chỉ cho vừa cột VARCHAR(255).
                statement.setString(2, truncate(subject, 255));
                statement.setString(3, ip);
                statement.executeUpdate();
            }
            // Bước 2: đọc trạng thái phê duyệt.
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT approved FROM trusted_device WHERE fingerprint = ?")) {
                statement.setString(1, fingerprint);
                try (ResultSet result = statement.executeQuery()) {
                    // Có dòng và cột approved = TRUE.
                    return result.next() && result.getBoolean("approved");
                }
            }
        } catch (SQLException error) {
            // Lỗi CSDL: coi như chưa duyệt (an toàn hơn cho phép).
            System.err.println("[DeviceRegistry] Khong ghi nhan duoc thiet bi: " + error.getMessage());
            return false;
        }
    }

    /** Ghi tài khoản vừa đăng nhập trên thiết bị (để quản trị viên biết máy của ai). */
    public static void recordAccount(String fingerprint, int accountId) {
        update("UPDATE trusted_device SET last_account_id = ? WHERE fingerprint = ?", accountId, fingerprint);
    }

    /** Duyệt / bỏ duyệt thiết bị. @return true nếu tìm thấy thiết bị */
    public static boolean setApproved(String fingerprint, boolean approved) {
        return update("UPDATE trusted_device SET approved = ? WHERE fingerprint = ?", approved, fingerprint) > 0;
    }

    /** Danh sách thiết bị cho trang giám sát (chưa duyệt lên trước, tối đa 200). */
    public static List<Map<String, Object>> list() {
        // LEFT JOIN account để lấy email tài khoản đăng nhập gần nhất (có thể không có).
        String sql = "SELECT d.fingerprint, d.subject, d.approved, d.first_seen, d.last_seen, d.last_ip, a.email "
                + "FROM trusted_device d LEFT JOIN account a ON a.account_id = d.last_account_id "
                + "ORDER BY d.approved, d.last_seen DESC LIMIT 200";
        List<Map<String, Object>> devices = new ArrayList<>();
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            // Mỗi dòng thành một Map (sau đó đổi ra JSON).
            while (result.next()) {
                Map<String, Object> device = new LinkedHashMap<>();
                device.put("fingerprint", result.getString("fingerprint"));
                device.put("subject", result.getString("subject"));
                device.put("approved", result.getBoolean("approved"));
                device.put("firstSeen", String.valueOf(result.getObject("first_seen", LocalDateTime.class)));
                device.put("lastSeen", String.valueOf(result.getObject("last_seen", LocalDateTime.class)));
                device.put("lastIp", result.getString("last_ip"));
                device.put("lastAccount", result.getString("email"));
                devices.add(device);
            }
        } catch (SQLException error) {
            System.err.println("[DeviceRegistry] Khong doc duoc danh sach thiet bi: " + error.getMessage());
        }
        return devices;
    }

    // Chạy câu UPDATE có 2 tham số (giá trị, dấu vân tay). @return số dòng bị ảnh hưởng (0 nếu lỗi).
    private static int update(String sql, Object first, String fingerprint) {
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, first);
            statement.setString(2, fingerprint);
            return statement.executeUpdate();
        } catch (SQLException error) {
            System.err.println("[DeviceRegistry] Loi cap nhat thiet bi: " + error.getMessage());
            return 0;
        }
    }

    // Cắt chuỗi cho không quá max ký tự; null thành chuỗi rỗng.
    private static String truncate(String value, int max) {
        return value == null ? "" : value.length() > max ? value.substring(0, max) : value;
    }
}
