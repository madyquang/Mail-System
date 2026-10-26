package com.mailsystem.server.dao.jdbc;

import com.mailsystem.common.model.Contact;
import com.mailsystem.server.dao.ContactDAO;
import com.mailsystem.server.db.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class JdbcContactDAO implements ContactDAO {

    @Override
    public List<Contact> findPeople(int accountId, String containsPattern, String prefixPattern, int limit)
            throws SQLException {
        // interactions: số thư tôi gửi cho họ + số thư họ gửi cho tôi (đếm theo bản thư mail_recipient).
        // Chỉ gợi ý người ĐÃ trao đổi thư (interactions > 0): không ai dò được danh sách email
        // của cả hệ thống bằng cách gõ từng chữ cái.
        String sql = "SELECT a.email, a.display_name, "
                + "(SELECT COUNT(*) FROM mail_recipient mr JOIN mail m ON m.mail_id = mr.mail_id "
                + "  WHERE mr.recipient_type IN ('TO','CC','BCC') AND ("
                + "    (m.sender_id = ? AND mr.account_id = a.account_id) "
                + "    OR (m.sender_id = a.account_id AND mr.account_id = ?))) AS interactions "
                + "FROM account a "
                + "WHERE a.account_id <> ? "
                + "AND (a.email LIKE ? ESCAPE '!' OR a.display_name LIKE ? ESCAPE '!') "
                + "HAVING interactions > 0 "
                + "ORDER BY interactions DESC, (a.email LIKE ? ESCAPE '!') DESC, a.email "
                + "LIMIT ?";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, accountId);
            statement.setInt(2, accountId);
            statement.setInt(3, accountId);
            statement.setString(4, containsPattern);
            statement.setString(5, containsPattern);
            statement.setString(6, prefixPattern);
            statement.setInt(7, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<Contact> people = new ArrayList<>();
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
        String sql = "SELECT g.group_name, COUNT(everyone.account_id) AS members "
                + "FROM mail_group g "
                + "JOIN group_member me ON me.group_id = g.group_id AND me.account_id = ? "
                + "JOIN group_member everyone ON everyone.group_id = g.group_id "
                + "WHERE g.group_name LIKE ? ESCAPE '!' "
                + "GROUP BY g.group_id, g.group_name ORDER BY g.group_name LIMIT ?";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, accountId);
            statement.setString(2, containsPattern);
            statement.setInt(3, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<Contact> groups = new ArrayList<>();
                while (result.next()) {
                    groups.add(Contact.group(result.getString("group_name"), result.getInt("members")));
                }
                return groups;
            }
        }
    }
}
