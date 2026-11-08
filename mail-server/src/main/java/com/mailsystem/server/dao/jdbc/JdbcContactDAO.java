// Gói "dao.jdbc": các lớp DAO viết bằng JDBC thuần (PreparedStatement).
package com.mailsystem.server.dao.jdbc;

// Contact: một gợi ý người nhận.
import com.mailsystem.common.model.Contact;
// ContactDAO: interface mà lớp này thực hiện.
import com.mailsystem.server.dao.ContactDAO;
// DatabaseConnection: lấy kết nối từ pool HikariCP.
import com.mailsystem.server.db.DatabaseConnection;

// Các lớp JDBC: kết nối, câu lệnh có tham số, kết quả truy vấn, lỗi SQL.
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
// ArrayList / List: danh sách.
import java.util.ArrayList;
import java.util.List;

/** Truy vấn gợi ý người nhận / nhóm bằng JDBC. */
public class JdbcContactDAO implements ContactDAO {

    @Override
    public List<Contact> findPeople(int accountId, String containsPattern, String prefixPattern, int limit)
            throws SQLException {
        // interactions: số thư tôi gửi cho họ + số thư họ gửi cho tôi (đếm theo bản thư mail_recipient).
        // Gợi ý mọi tài khoản khớp (danh bạ chung trong mạng nội bộ), người đã trao đổi nhiều xếp trước.
        String sql = "SELECT a.email, a.display_name, "
                + "(SELECT COUNT(*) FROM mail_recipient mr JOIN mail m ON m.mail_id = mr.mail_id "
                + "  WHERE mr.recipient_type IN ('TO','CC','BCC') AND ("
                + "    (m.sender_id = ? AND mr.account_id = a.account_id) "
                + "    OR (m.sender_id = a.account_id AND mr.account_id = ?))) AS interactions "
                + "FROM account a "
                + "WHERE a.account_id <> ? "
                + "AND (a.email LIKE ? ESCAPE '!' OR a.display_name LIKE ? ESCAPE '!') "
                + "ORDER BY interactions DESC, (a.email LIKE ? ESCAPE '!') DESC, a.email "
                + "LIMIT ?";
        // try-with-resources: tự đóng kết nối và câu lệnh khi xong (trả kết nối về pool).
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            // ?1, ?2: tôi (để đếm thư trao đổi hai chiều).
            statement.setInt(1, accountId);
            statement.setInt(2, accountId);
            // ?3: loại chính tôi khỏi kết quả.
            statement.setInt(3, accountId);
            // ?4, ?5: email hoặc tên hiển thị chứa chuỗi gõ.
            statement.setString(4, containsPattern);
            statement.setString(5, containsPattern);
            // ?6: ưu tiên email bắt đầu bằng chuỗi gõ.
            statement.setString(6, prefixPattern);
            // ?7: số kết quả tối đa.
            statement.setInt(7, limit);
            // Chạy truy vấn và duyệt kết quả.
            try (ResultSet result = statement.executeQuery()) {
                List<Contact> people = new ArrayList<>();
                // Mỗi dòng là một người.
                while (result.next()) {
                    people.add(Contact.person(result.getString("email"), result.getString("display_name"),
                            result.getInt("interactions")));
                }
                return people;
            }
        }
    }

    @Override
    public List<Contact> findGroups(int accountId, String containsPattern, int limit) throws SQLException {
        // Nối nhóm với group_member hai lần: "me" để chỉ lấy nhóm tôi là thành viên,
        // "everyone" để đếm tổng số thành viên của nhóm.
        String sql = "SELECT g.group_name, g.group_address, COUNT(everyone.account_id) AS members "
                + "FROM mail_group g "
                + "JOIN group_member me ON me.group_id = g.group_id AND me.account_id = ? "
                + "JOIN group_member everyone ON everyone.group_id = g.group_id "
                + "WHERE (g.group_name LIKE ? ESCAPE '!' OR g.group_address LIKE ? ESCAPE '!') "
                + "GROUP BY g.group_id, g.group_name, g.group_address ORDER BY g.group_name LIMIT ?";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            // ?1: tôi.
            statement.setInt(1, accountId);
            // ?2, ?3: tên hoặc địa chỉ nhóm chứa chuỗi gõ.
            statement.setString(2, containsPattern);
            statement.setString(3, containsPattern);
            // ?4: số kết quả tối đa.
            statement.setInt(4, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<Contact> groups = new ArrayList<>();
                // Mỗi dòng là một nhóm.
                while (result.next()) {
                    groups.add(Contact.group(result.getString("group_name"), result.getString("group_address"),
                            result.getInt("members")));
                }
                return groups;
            }
        }
    }
}
