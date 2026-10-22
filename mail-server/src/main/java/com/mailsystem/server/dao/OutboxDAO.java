package com.mailsystem.server.dao;

import com.mailsystem.common.model.ScheduledMail;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Hộp thư chờ gửi (bảng outbox + outbox_attachment). */
public interface OutboxDAO {

    record StoredFile(String filename, String mimeType, long sizeBytes, String storagePath) {
    }

    record Entry(int outboxId, int accountId, String payload, LocalDateTime sendAt, List<StoredFile> files) {
    }

    int insert(int accountId, String payload, LocalDateTime sendAt, List<StoredFile> files) throws SQLException;

    List<ScheduledMail> listByAccount(int accountId) throws SQLException;

    /** outboxId các thư đã tới giờ gửi. */
    List<Integer> findDueIds(int limit) throws SQLException;

    /**
     * Khoá (SELECT ... FOR UPDATE) và xoá một thư khỏi outbox trong transaction
     * của caller. Rỗng nếu thư không còn (đã bị huỷ hoặc đã gửi) hoặc không thuộc
     * tài khoản (accountId null = không kiểm tra chủ sở hữu, dùng cho bộ gửi tự động).
     */
    Optional<Entry> claim(Connection connection, int outboxId, Integer accountId) throws SQLException;
}
