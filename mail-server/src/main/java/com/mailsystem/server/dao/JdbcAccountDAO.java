package com.mailsystem.server.dao;

import com.mailsystem.common.model.Account;
import com.mailsystem.server.db.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

public class JdbcAccountDAO implements AccountDAO {

    @Override
    public int insertAccount(String email, String passwordHash, String displayName) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection()) {
            return insertAccount(connection, email, passwordHash, displayName);
        }
    }

    @Override
    public int insertAccount(Connection connection, String email, String passwordHash, String displayName)
            throws SQLException {
        String sql = "INSERT INTO account (email, password_hash, display_name) VALUES (?, ?, ?)";
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
        String sql = "SELECT 1 FROM account WHERE email = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private Account readAccount(ResultSet result) throws SQLException {
        return new Account(result.getInt("account_id"), result.getString("email"),
                result.getString("display_name"));
    }
}
