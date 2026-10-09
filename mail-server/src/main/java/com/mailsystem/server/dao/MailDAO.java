package com.mailsystem.server.dao;

import com.mailsystem.common.model.Attachment;
import com.mailsystem.common.model.MailDetail;
import com.mailsystem.common.model.MailSummary;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * DAO cho bang `mail`, `mail_recipient`, `attachment`.
 * TODO (TV2): implement bang JDBC dung theo dung quy trinh da mo ta trong
 * schema.sql
 * (phan GHI CHU NGHIEP VU cuoi file).
 */
public interface MailDAO {

    /** Insert 1 dong vao bang `mail`, tra ve mailId vua tao. */
    int insertMail(int senderId, String subject, String body) throws SQLException;

    int insertMail(Connection connection, int senderId, String subject, String body) throws SQLException;

    /**
     * Insert 1 dong vao `mail_recipient`.
     * recipientType: "TO" | "CC" | "BCC" | "SENDER".
     */
    void insertMailRecipient(int mailId, int accountId, int folderId,
            String recipientType, boolean isRead) throws SQLException;

    void insertMailRecipient(Connection connection, int mailId, int accountId, int folderId,
            String recipientType, boolean isRead) throws SQLException;

    void insertAttachment(int mailId, String filename, String mimeType,
            long sizeBytes, String storagePath) throws SQLException;

    void insertAttachment(Connection connection, int mailId, String filename, String mimeType,
            long sizeBytes, String storagePath) throws SQLException;

    /**
     * JOIN mail_recipient + mail, loc theo account + folder, is_deleted = false.
     */
    List<MailSummary> getMailList(int accountId, int folderId) throws SQLException;

    /**
     * Lay chi tiet thu ma account duoc phep xem; mo thu dong thoi danh dau da doc
     * dung ban thu (entry) duoc mo. entryId == null: uu tien ban thu nhan (khong
     * phai ban SENDER) de nguoi tu gui cho chinh minh van danh dau duoc da doc.
     */
    Optional<MailDetail> getMailDetail(int mailId, int accountId, Integer entryId) throws SQLException;

    List<Attachment> getAttachmentsByMailId(int mailId) throws SQLException;

    /** Loads one bounded attachment chunk only when the account can access its active mail. */
    Optional<Attachment> getAttachmentChunk(int attachmentId, int accountId, long offset, int maxBytes)
            throws SQLException;

    /** Marks an account's active mail entry as read; returns false when it is not found. */
    boolean markRead(int entryId, int accountId) throws SQLException;

    /** Moves an entry to the owning account's TRASH folder; returns false when it cannot be moved. */
    boolean moveMailToTrash(int entryId, int accountId) throws SQLException;

    /** Permanently removes one recipient entry only when it belongs to the account's TRASH. */
    boolean deleteTrashMail(int entryId, int accountId) throws SQLException;

    /** Permanently removes all recipient entries in the account's TRASH and returns the count. */
    int emptyTrash(int accountId) throws SQLException;

    /** Restores an entry from TRASH to its default mailbox for its recipient role. */
    boolean restoreTrashMail(int entryId, int accountId) throws SQLException;

    /**
     * Searches subject/body and optionally filters by sender email or display name,
     * scoped to the account and optional folder.
     */
    List<MailSummary> searchMail(int accountId, Integer folderId, String keyword, String fromFilter)
            throws SQLException;

    /**
     * Tra ve danh sach accountId cua tat ca nguoi nhan (TO/CC/BCC) cua 1 mail -
     * dung de ban EVENT.
     */
    List<Integer> getRecipientAccountIds(int mailId) throws SQLException;
}
