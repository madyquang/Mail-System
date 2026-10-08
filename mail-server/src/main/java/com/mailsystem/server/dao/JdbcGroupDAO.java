package com.mailsystem.server.dao;

import com.mailsystem.common.model.MailGroupDTO;
import com.mailsystem.server.db.DatabaseConnection;

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

public class JdbcGroupDAO implements GroupDAO {

    @Override
    public int insertGroup(String groupName, int ownerId) throws SQLException {
        String sql = "INSERT INTO mail_group (group_name, owner_id) VALUES (?, ?)";
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
    public int createGroupWithOwner(String groupName, int ownerId) throws SQLException {
        String insertGroup = "INSERT INTO mail_group (group_name, owner_id) VALUES (?, ?)";
        String insertOwner = "INSERT INTO group_member (group_id, account_id) VALUES (?, ?)";
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int groupId;
                try (PreparedStatement statement = connection.prepareStatement(
                        insertGroup, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, groupName);
                    statement.setInt(2, ownerId);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("Database did not return the new group ID");
                        }
                        groupId = keys.getInt(1);
                    }
                }
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
                    error.addSuppressed(rollbackError);
                }
                throw error;
            }
        }
    }

    @Override
    public void insertMember(int groupId, int accountId) throws SQLException {
        executeUpdate("INSERT INTO group_member (group_id, account_id) VALUES (?, ?)", groupId, accountId);
    }

    @Override
    public void removeMember(int groupId, int accountId) throws SQLException {
        executeUpdate("DELETE FROM group_member WHERE group_id = ? AND account_id = ?", groupId, accountId);
    }

    @Override
    public void deleteGroup(int groupId) throws SQLException {
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
    public List<MailGroupDTO> findGroupsByMember(int accountId) throws SQLException {
        String sql = "SELECT g.group_id, g.group_name, g.owner_id, owner.email AS owner_email, "
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
                Map<Integer, MailGroupDTO> groupsById = new LinkedHashMap<>();
                while (result.next()) {
                    int groupId = result.getInt("group_id");
                    MailGroupDTO group = groupsById.get(groupId);
                    if (group == null) {
                        group = new MailGroupDTO();
                        group.setGroupId(groupId);
                        group.setGroupName(result.getString("group_name"));
                        group.setOwnerEmail(result.getString("owner_email"));
                        group.setOwner(result.getInt("owner_id") == accountId);
                        group.setMemberEmails(new ArrayList<>());
                        groupsById.put(groupId, group);
                    }
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
    public boolean isMember(int groupId, int accountId) throws SQLException {
        String sql = "SELECT 1 FROM group_member WHERE group_id = ? AND account_id = ? LIMIT 1";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, groupId);
            statement.setInt(2, accountId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void executeUpdate(String sql, int groupId, int accountId) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, groupId);
            statement.setInt(2, accountId);
            statement.executeUpdate();
        }
    }
}
