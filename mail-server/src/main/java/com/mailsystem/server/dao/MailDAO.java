package com.mailsystem.server.dao;

import com.mailsystem.common.model.Attachment;
import com.mailsystem.common.model.MailDetail;
import com.mailsystem.common.model.MailSummary;

import java.sql.SQLException;
import java.util.List;

/**
 * DAO cho bang `mail`, `mail_recipient`, `attachment`.
 * TODO (TV2): implement bang JDBC dung theo dung quy trinh da mo ta trong schema.sql
 * (phan GHI CHU NGHIEP VU cuoi file).
 */
public interface MailDAO {

    /** Insert 1 dong vao bang `mail`, tra ve mailId vua tao. */
    int insertMail(int senderId, String subject, String body) throws SQLException;

    /**
     * Insert 1 dong vao `mail_recipient`.
     * recipientType: "TO" | "CC" | "BCC" | "SENDER".
     */
    void insertMailRecipient(int mailId, int accountId, int folderId,
                              String recipientType, boolean isRead) throws SQLException;

    void insertAttachment(int mailId, String filename, String mimeType,
                           long sizeBytes, String storagePath) throws SQLException;

    /** JOIN mail_recipient + mail, loc theo account + folder, is_deleted = false. */
    List<MailSummary> getMailList(int accountId, int folderId) throws SQLException;

    /** Lay noi dung day du 1 thu (subject/body/to/cc/bcc/attachments). */
    MailDetail getMailDetail(int mailId) throws SQLException;

    List<Attachment> getAttachmentsByMailId(int mailId) throws SQLException;

    /** UPDATE mail_recipient SET is_read = true WHERE entry_id = ? AND account_id = ?. */
    void markRead(int entryId, int accountId) throws SQLException;

    /** UPDATE mail_recipient SET is_deleted = true WHERE entry_id = ? AND account_id = ?. */
    void softDeleteMail(int entryId, int accountId) throws SQLException;

    /** LIKE '%keyword%' tren subject/body, join mail_recipient theo accountId (+ folderId neu co). */
    List<MailSummary> searchMail(int accountId, Integer folderId, String keyword) throws SQLException;

    /** Tra ve danh sach accountId cua tat ca nguoi nhan (TO/CC/BCC) cua 1 mail - dung de ban EVENT. */
    List<Integer> getRecipientAccountIds(int mailId) throws SQLException;
}
