// Gói "dao.jdbc": các lớp DAO viết bằng JDBC thuần.
package com.mailsystem.server.dao.jdbc;

// Interface mà lớp này thực hiện.
import com.mailsystem.server.dao.GroupDAO;

// DTO nhóm; kết nối CSDL.
import com.mailsystem.common.model.MailGroupDTO;
import com.mailsystem.server.db.DatabaseConnection;

// Các lớp JDBC; danh sách, Map giữ thứ tự, Optional.
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Truy cập bảng mail_group và group_member bằng JDBC. */
public class JdbcGroupDAO implements GroupDAO {

    @Override
    public int insertGroup(String groupName, int ownerId) throws SQLException {
        String sql = "INSERT INTO mail_group (group_name, owner_id) VALUES (?, ?)";
        // RETURN_GENERATED_KEYS: lấy group_id vừa tạo.
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, groupName);
            statement.setInt(2, ownerId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        }
        throw new SQLException("Database did not return the new group ID");
    }

    @Override
    public int createGroupWithOwner(String groupName, String groupAddress, int ownerId) throws SQLException {
        String insertGroup = "INSERT INTO mail_group (group_name, group_address, owner_id) VALUES (?, ?, ?)";
        String insertOwner = "INSERT INTO group_member (group_id, account_id) VALUES (?, ?)";
        try (Connection connection = DatabaseConnection.getConnection()) {
            // Tạo nhóm + thêm chủ nhóm làm thành viên trong một transaction.
            connection.setAutoCommit(false);
            try {
                int groupId;
                // Bước 1: thêm nhóm, lấy mã.
                try (PreparedStatement statement = connection.prepareStatement(
                        insertGroup, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, groupName);
                    statement.setString(2, groupAddress);
                    statement.setInt(3, ownerId);
                    // Trùng tên hoặc trùng địa chỉ sẽ ném SQLException (ràng buộc UNIQUE).
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("Database did not return the new group ID");
                        }
                        groupId = keys.getInt(1);
                    }
                }
                // Bước 2: chủ nhóm cũng là thành viên.
                try (PreparedStatement statement = connection.prepareStatement(insertOwner)) {
                    statement.setInt(1, groupId);
                    statement.setInt(2, ownerId);
                    statement.executeUpdate();
                }
                connection.commit();
                return groupId;
            } catch (SQLException error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    // Rollback cũng lỗi: đính kèm vào lỗi gốc để không mất thông tin.
                    error.addSuppressed(rollbackError);
                }
                throw error;
            }
        }
    }

    @Override
    public void insertMember(int groupId, int accountId) throws SQLException {
        // Đã là thành viên sẽ ném lỗi trùng khoá chính (group_id, account_id).
        executeUpdate("INSERT INTO group_member (group_id, account_id) VALUES (?, ?)", groupId, accountId);
    }

    @Override
    public void removeMember(int groupId, int accountId) throws SQLException {
        executeUpdate("DELETE FROM group_member WHERE group_id = ? AND account_id = ?", groupId, accountId);
    }

    @Override
    public void deleteGroup(int groupId) throws SQLException {
        // group_member bị xoá theo nhờ ON DELETE CASCADE.
        String sql = "DELETE FROM mail_group WHERE group_id = ?";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, groupId);
            statement.executeUpdate();
        }
    }

    @Override
    public Optional<Integer> findOwnerId(int groupId) throws SQLException {
        String sql = "SELECT owner_id FROM mail_group WHERE group_id = ?";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, groupId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getInt("owner_id")) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<String> findGroupName(int groupId) throws SQLException {
        String sql = "SELECT group_name FROM mail_group WHERE group_id = ?";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, groupId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString("group_name")) : Optional.empty();
            }
        }
    }

    @Override
    public List<MailGroupDTO> findGroupsByMember(int accountId) throws SQLException {
        // visible_member: chỉ lấy nhóm mà tài khoản là thành viên.
        // all_members + member: liệt kê email mọi thành viên của nhóm đó (mỗi thành viên một dòng).
        // owner: email chủ nhóm.
        String sql = "SELECT g.group_id, g.group_name, g.group_address, g.owner_id, owner.email AS owner_email, "
                + "member.email AS member_email "
                + "FROM mail_group g "
                + "JOIN group_member visible_member ON visible_member.group_id = g.group_id "
                + "JOIN account owner ON owner.account_id = g.owner_id "
                + "LEFT JOIN group_member all_members ON all_members.group_id = g.group_id "
                + "LEFT JOIN account member ON member.account_id = all_members.account_id "
                + "WHERE visible_member.account_id = ? "
                + "ORDER BY g.group_id, member.email";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                // Gom các dòng cùng group_id thành một MailGroupDTO (giữ thứ tự nhóm).
                Map<Integer, MailGroupDTO> groupsById = new LinkedHashMap<>();
                while (result.next()) {
                    int groupId = result.getInt("group_id");
                    MailGroupDTO group = groupsById.get(groupId);
                    // Dòng đầu tiên của nhóm: tạo DTO.
                    if (group == null) {
                        group = new MailGroupDTO();
                        group.setGroupId(groupId);
                        group.setGroupName(result.getString("group_name"));
                        group.setGroupAddress(result.getString("group_address"));
                        group.setOwnerEmail(result.getString("owner_email"));
                        // Người xem có phải chủ nhóm không.
                        group.setOwner(result.getInt("owner_id") == accountId);
                        group.setMemberEmails(new ArrayList<>());
                        groupsById.put(groupId, group);
                    }
                    // Thêm email thành viên của dòng này.
                    String memberEmail = result.getString("member_email");
                    if (memberEmail != null) {
                        group.getMemberEmails().add(memberEmail);
                    }
                }
                return new ArrayList<>(groupsById.values());
            }
        }
    }

    @Override
    public List<Integer> getMemberAccountIds(int groupId) throws SQLException {
        String sql = "SELECT account_id FROM group_member WHERE group_id = ?";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, groupId);
            try (ResultSet result = statement.executeQuery()) {
                List<Integer> members = new ArrayList<>();
                while (result.next()) {
                    members.add(result.getInt("account_id"));
                }
                return members;
            }
        }
    }

    @Override
    public Optional<Integer> findGroupIdByName(String groupName) throws SQLException {
        // So sánh không phân biệt hoa thường.
        String sql = "SELECT group_id FROM mail_group WHERE LOWER(group_name) = LOWER(?) LIMIT 1";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, groupName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getInt("group_id")) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<Integer> findGroupIdByAddress(String groupAddress) throws SQLException {
        // So sánh không phân biệt hoa thường.
        String sql = "SELECT group_id FROM mail_group WHERE LOWER(group_address) = LOWER(?) LIMIT 1";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, groupAddress);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getInt("group_id")) : Optional.empty();
            }
        }
    }

    @Override
    public java.util.Map<Integer, String> findGroupsWithoutAddress() throws SQLException {
        String sql = "SELECT group_id, group_name FROM mail_group WHERE group_address IS NULL ORDER BY group_id";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            // groupId -> tên nhóm, theo thứ tự mã nhóm.
            java.util.Map<Integer, String> groups = new LinkedHashMap<>();
            while (result.next()) {
                groups.put(result.getInt("group_id"), result.getString("group_name"));
            }
            return groups;
        }
    }

    @Override
    public Optional<String> findGroupAddress(int groupId) throws SQLException {
        String sql = "SELECT group_address FROM mail_group WHERE group_id = ?";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, groupId);
            try (ResultSet result = statement.executeQuery()) {
                // ofNullable: nhóm có nhưng chưa có địa chỉ (NULL) thì cũng trả rỗng.
                return result.next() ? Optional.ofNullable(result.getString("group_address")) : Optional.empty();
            }
        }
    }

    @Override
    public void updateGroupAddress(int groupId, String groupAddress) throws SQLException {
        String sql = "UPDATE mail_group SET group_address = ? WHERE group_id = ?";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, groupAddress);
            statement.setInt(2, groupId);
            statement.executeUpdate();
        }
    }

    @Override
    public boolean isMember(int groupId, int accountId) throws SQLException {
        String sql = "SELECT 1 FROM group_member WHERE group_id = ? AND account_id = ? LIMIT 1";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, groupId);
            statement.setInt(2, accountId);
            try (ResultSet result = statement.executeQuery()) {
                // Có dòng = là thành viên.
                return result.next();
            }
        }
    }

    // Chạy câu lệnh ghi có 2 tham số (groupId, accountId).
    private void executeUpdate(String sql, int groupId, int accountId) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, groupId);
            statement.setInt(2, accountId);
            statement.executeUpdate();
        }
    }
}
