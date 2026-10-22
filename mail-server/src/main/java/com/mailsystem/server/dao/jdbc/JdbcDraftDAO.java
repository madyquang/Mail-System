package com.mailsystem.server.dao.jdbc;

import com.mailsystem.common.model.Draft;
import com.mailsystem.server.dao.DraftDAO;
import com.mailsystem.server.db.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class JdbcDraftDAO implements DraftDAO {

    private static final String COLUMNS = "draft_id, to_text, cc_text, bcc_text, subject, body, updated_at";

    @Override
    public int insert(int accountId, Draft draft) throws SQLException {
        String sql = "INSERT INTO draft (account_id, to_text, cc_text, bcc_text, subject, body) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, accountId);
            bindContent(statement, 2, draft);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        }
        throw new SQLException("Database did not return the new draft ID");
    }

    @Override
    public boolean update(int accountId, Draft draft) throws SQLException {
        String sql = "UPDATE draft SET to_text = ?, cc_text = ?, bcc_text = ?, subject = ?, body = ?, "
                + "updated_at = CURRENT_TIMESTAMP WHERE draft_id = ? AND account_id = ?";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            int next = bindContent(statement, 1, draft);
            statement.setInt(next, draft.getDraftId());
            statement.setInt(next + 1, accountId);
            return statement.executeUpdate() > 0;
        }
    }

    @Override
    public List<Draft> findByAccount(int accountId) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM draft WHERE account_id = ? ORDER BY updated_at DESC, draft_id DESC";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                List<Draft> drafts = new ArrayList<>();
                while (result.next()) {
                    drafts.add(read(result));
                }
                return drafts;
            }
        }
    }

    @Override
    public Optional<Draft> findById(int accountId, int draftId) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM draft WHERE account_id = ? AND draft_id = ?";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, accountId);
            statement.setInt(2, draftId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    @Override
    public int delete(int accountId, List<Integer> draftIds) throws SQLException {
        if (draftIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", Collections.nCopies(draftIds.size(), "?"));
        String sql = "DELETE FROM draft WHERE account_id = ? AND draft_id IN (" + placeholders + ")";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, accountId);
            for (int i = 0; i < draftIds.size(); i++) {
                statement.setInt(i + 2, draftIds.get(i));
            }
            return statement.executeUpdate();
        }
    }

    private int bindContent(PreparedStatement statement, int start, Draft draft) throws SQLException {
        statement.setString(start, nullToEmpty(draft.getTo()));
        statement.setString(start + 1, nullToEmpty(draft.getCc()));
        statement.setString(start + 2, nullToEmpty(draft.getBcc()));
        statement.setString(start + 3, nullToEmpty(draft.getSubject()));
        statement.setString(start + 4, nullToEmpty(draft.getBody()));
        return start + 5;
    }

    private Draft read(ResultSet result) throws SQLException {
        Draft draft = new Draft();
        draft.setDraftId(result.getInt("draft_id"));
        draft.setTo(result.getString("to_text"));
        draft.setCc(result.getString("cc_text"));
        draft.setBcc(result.getString("bcc_text"));
        draft.setSubject(result.getString("subject"));
        draft.setBody(result.getString("body"));
        java.time.LocalDateTime updated = result.getObject("updated_at", java.time.LocalDateTime.class);
        draft.setUpdatedAt(updated == null ? null : updated.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return draft;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
