package com.mailsystem.server.dao;

import com.mailsystem.common.model.Folder;
import com.mailsystem.server.db.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JdbcFolderDAO implements FolderDAO {

    @Override
    public void createDefaultFolders(int accountId) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection()) {
            createDefaultFolders(connection, accountId);
        }
    }

    @Override
    public void createDefaultFolders(Connection connection, int accountId) throws SQLException {
        String sql = "INSERT INTO folder (account_id, folder_name, folder_type) VALUES (?, ?, ?)";
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
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    @Override
    public List<Folder> findFoldersByAccount(int accountId) throws SQLException {
        String sql = "SELECT folder_id, folder_name, folder_type FROM folder WHERE account_id = ? ORDER BY folder_id";
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
        try (Connection connection = DatabaseConnection.getConnection()) {
            return findFolderIdByType(connection, accountId, folderType);
        }
    }

    @Override
    public Optional<Integer> findFolderIdByType(Connection connection, int accountId, String folderType)
            throws SQLException {
        String sql = "SELECT folder_id FROM folder WHERE account_id = ? AND folder_type = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, accountId);
            statement.setString(2, folderType);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getInt("folder_id")) : Optional.empty();
            }
        }
    }

    private Folder readFolder(ResultSet result) throws SQLException {
        return new Folder(result.getInt("folder_id"), result.getString("folder_name"),
                result.getString("folder_type"));
    }
}
