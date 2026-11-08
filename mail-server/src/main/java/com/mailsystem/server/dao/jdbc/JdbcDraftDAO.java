// Gói "dao.jdbc": các lớp DAO viết bằng JDBC thuần.
package com.mailsystem.server.dao.jdbc;

// Draft: dữ liệu bản nháp.
import com.mailsystem.common.model.Draft;
// DraftDAO: interface mà lớp này thực hiện.
import com.mailsystem.server.dao.DraftDAO;
// DatabaseConnection: lấy kết nối từ pool.
import com.mailsystem.server.db.DatabaseConnection;

// Các lớp JDBC; Statement.RETURN_GENERATED_KEYS để lấy khoá tự tăng vừa tạo.
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
// DateTimeFormatter: định dạng ngày giờ ISO-8601.
import java.time.format.DateTimeFormatter;
// Danh sách; Collections.nCopies để tạo chuỗi "?,?,?".
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Truy cập bảng draft bằng JDBC. Mọi câu lệnh đều có điều kiện account_id. */
public class JdbcDraftDAO implements DraftDAO {

    // Các cột đọc ra khi SELECT bản nháp.
    private static final String COLUMNS = "draft_id, to_text, cc_text, bcc_text, subject, body, updated_at";

    @Override
    public int insert(int accountId, Draft draft) throws SQLException {
        String sql = "INSERT INTO draft (account_id, to_text, cc_text, bcc_text, subject, body) VALUES (?, ?, ?, ?, ?, ?)";
        // RETURN_GENERATED_KEYS: yêu cầu MySQL trả draft_id tự tăng vừa tạo.
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            // ?1: chủ bản nháp.
            statement.setInt(1, accountId);
            // ?2..?6: nội dung bản nháp.
            bindContent(statement, 2, draft);
            statement.executeUpdate();
            // Đọc khoá vừa tạo.
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        }
        // Không nhận được khoá (bất thường).
        throw new SQLException("Database did not return the new draft ID");
    }

    @Override
    public boolean update(int accountId, Draft draft) throws SQLException {
        // Ghi đè nội dung và cập nhật thời điểm sửa; chỉ khi bản nháp thuộc tài khoản.
        String sql = "UPDATE draft SET to_text = ?, cc_text = ?, bcc_text = ?, subject = ?, body = ?, "
                + "updated_at = CURRENT_TIMESTAMP WHERE draft_id = ? AND account_id = ?";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            // ?1..?5: nội dung; bindContent trả vị trí tham số kế tiếp (6).
            int next = bindContent(statement, 1, draft);
            // ?6: mã bản nháp; ?7: chủ bản nháp.
            statement.setInt(next, draft.getDraftId());
            statement.setInt(next + 1, accountId);
            // Có dòng bị sửa nghĩa là thành công.
            return statement.executeUpdate() > 0;
        }
    }

    @Override
    public List<Draft> findByAccount(int accountId) throws SQLException {
        // Mới sửa xếp trước; cùng thời điểm thì mã lớn (mới tạo) trước.
        String sql = "SELECT " + COLUMNS + " FROM draft WHERE account_id = ? ORDER BY updated_at DESC, draft_id DESC";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                List<Draft> drafts = new ArrayList<>();
                // Mỗi dòng thành một Draft.
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
                // Có dòng thì đọc, không thì rỗng.
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    @Override
    public int delete(int accountId, List<Integer> draftIds) throws SQLException {
        // Không có gì để xoá.
        if (draftIds.isEmpty()) {
            return 0;
        }
        // Tạo chuỗi "?,?,?" đúng số phần tử để dùng trong IN (...).
        String placeholders = String.join(",", Collections.nCopies(draftIds.size(), "?"));
        String sql = "DELETE FROM draft WHERE account_id = ? AND draft_id IN (" + placeholders + ")";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            // ?1: chủ bản nháp (chỉ xoá được nháp của mình).
            statement.setInt(1, accountId);
            // ?2..: các mã bản nháp.
            for (int i = 0; i < draftIds.size(); i++) {
                statement.setInt(i + 2, draftIds.get(i));
            }
            // Trả số dòng đã xoá.
            return statement.executeUpdate();
        }
    }

    // Gán 5 tham số nội dung bắt đầu từ vị trí start; trả vị trí tham số kế tiếp.
    private int bindContent(PreparedStatement statement, int start, Draft draft) throws SQLException {
        // null thành chuỗi rỗng (các cột NOT NULL DEFAULT '').
        statement.setString(start, nullToEmpty(draft.getTo()));
        statement.setString(start + 1, nullToEmpty(draft.getCc()));
        statement.setString(start + 2, nullToEmpty(draft.getBcc()));
        statement.setString(start + 3, nullToEmpty(draft.getSubject()));
        statement.setString(start + 4, nullToEmpty(draft.getBody()));
        return start + 5;
    }

    // Đọc dòng hiện tại của ResultSet thành Draft.
    private Draft read(ResultSet result) throws SQLException {
        Draft draft = new Draft();
        draft.setDraftId(result.getInt("draft_id"));
        draft.setTo(result.getString("to_text"));
        draft.setCc(result.getString("cc_text"));
        draft.setBcc(result.getString("bcc_text"));
        draft.setSubject(result.getString("subject"));
        draft.setBody(result.getString("body"));
        // Thời điểm sửa: đổi DATETIME thành chuỗi ISO-8601 (null giữ null).
        java.time.LocalDateTime updated = result.getObject("updated_at", java.time.LocalDateTime.class);
        draft.setUpdatedAt(updated == null ? null : updated.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return draft;
    }

    // null thành chuỗi rỗng.
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
