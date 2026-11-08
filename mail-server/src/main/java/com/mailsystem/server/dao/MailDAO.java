// Gói "dao": các interface truy cập CSDL (lớp thực hiện bằng JDBC nằm ở dao.jdbc).
package com.mailsystem.server.dao;

// Các DTO thư: tệp đính kèm, chi tiết thư, dòng tóm tắt trong danh sách.
import com.mailsystem.common.model.Attachment;
import com.mailsystem.common.model.MailDetail;
import com.mailsystem.common.model.MailSummary;

// Connection: kết nối CSDL (để chạy trong transaction của nơi gọi).
import java.sql.Connection;
// SQLException: lỗi CSDL.
import java.sql.SQLException;
// List / Optional.
import java.util.List;
import java.util.Optional;

/**
 * DAO cho bảng `mail`, `mail_recipient`, `attachment`.
 * Lớp thực hiện bằng JDBC: JdbcMailDAO, theo đúng quy trình mô tả trong
 * schema.sql (phần GHI CHÚ NGHIỆP VỤ cuối file).
 */
public interface MailDAO {

    /** Insert 1 dòng vào bảng `mail`, trả về mailId vừa tạo. */
    int insertMail(int senderId, String subject, String body) throws SQLException;

    /** Như trên nhưng chạy trên kết nối (transaction) của nơi gọi. */
    int insertMail(Connection connection, int senderId, String subject, String body) throws SQLException;

    /** threadId = thread của thư gốc khi trả lời (null = thư mở đầu hội thoại mới). */
    int insertMail(Connection connection, int senderId, String subject, String body, Integer threadId,
            Integer inReplyTo) throws SQLException;

    /** Chuyển bản thư sang thư mục khác (không phải Thùng rác) của chính tài khoản. */
    boolean moveToFolder(int entryId, int accountId, int folderId) throws SQLException;

    /** [mailId, entryId] của mọi thư trong hội thoại mà tài khoản xem được, cũ trước mới sau. */
    List<int[]> findThreadEntries(int accountId, int threadId) throws SQLException;

    /** threadId của thư nếu tài khoản có quyền xem thư đó. */
    Optional<Integer> findThreadRoot(int mailId, int accountId) throws SQLException;

    /**
     * Insert 1 dòng vào `mail_recipient`.
     * recipientType: "TO" | "CC" | "BCC" | "SENDER".
     */
    void insertMailRecipient(int mailId, int accountId, int folderId,
            String recipientType, boolean isRead) throws SQLException;

    /** Như trên nhưng chạy trên kết nối (transaction) của nơi gọi. */
    void insertMailRecipient(Connection connection, int mailId, int accountId, int folderId,
            String recipientType, boolean isRead) throws SQLException;

    /** Insert 1 dòng vào `attachment` (thông tin tệp đã lưu trên đĩa). */
    void insertAttachment(int mailId, String filename, String mimeType,
            long sizeBytes, String storagePath) throws SQLException;

    /** Như trên nhưng chạy trên kết nối (transaction) của nơi gọi. */
    void insertAttachment(Connection connection, int mailId, String filename, String mimeType,
            long sizeBytes, String storagePath) throws SQLException;

    /**
     * JOIN mail_recipient + mail, lọc theo account + folder, is_deleted = false.
     */
    List<MailSummary> getMailList(int accountId, int folderId) throws SQLException;

    /** Thư có gắn sao của tài khoản ở mọi thư mục trừ Thùng rác. */
    List<MailSummary> getStarredList(int accountId) throws SQLException;

    /**
     * Lấy chi tiết thư mà account được phép xem; mở thư đồng thời đánh dấu đã đọc
     * đúng bản thư (entry) được mở. entryId == null: ưu tiên bản thư nhận (không
     * phải bản SENDER) để người tự gửi cho chính mình vẫn đánh dấu được đã đọc.
     */
    Optional<MailDetail> getMailDetail(int mailId, int accountId, Integer entryId) throws SQLException;

    /** Thông tin (không kèm nội dung) các tệp đính kèm của một thư. */
    List<Attachment> getAttachmentsByMailId(int mailId) throws SQLException;

    /** Đọc một khối (tối đa maxBytes) của tệp, chỉ khi tài khoản còn giữ bản thư chứa tệp đó. */
    Optional<Attachment> getAttachmentChunk(int attachmentId, int accountId, long offset, int maxBytes)
            throws SQLException;

    /** Đặt trạng thái đã đọc/chưa đọc; false nếu bản thư không thuộc tài khoản. */
    boolean setRead(int entryId, int accountId, boolean read) throws SQLException;

    /** Gắn/bỏ sao; false nếu bản thư không thuộc tài khoản. */
    boolean setStarred(int entryId, int accountId, boolean starred) throws SQLException;

    /** Tệp đính kèm mà tài khoản có quyền xem (dùng khi chuyển tiếp thư kèm tệp). */
    Optional<StoredAttachment> findAccessibleAttachment(int attachmentId, int accountId) throws SQLException;

    /** Thông tin tệp đã lưu trên đĩa Server. */
    record StoredAttachment(String filename, String mimeType, long sizeBytes, java.nio.file.Path file) {
    }

    /** Chuyển bản thư vào Thùng rác của chính tài khoản (nhớ thư mục cũ); false nếu không chuyển được. */
    boolean moveMailToTrash(int entryId, int accountId) throws SQLException;

    /** Xoá vĩnh viễn một bản thư, chỉ khi nó đang nằm trong Thùng rác của tài khoản. */
    boolean deleteTrashMail(int entryId, int accountId) throws SQLException;

    /** Xoá vĩnh viễn mọi bản thư trong Thùng rác của tài khoản; trả về số bản đã xoá. */
    int emptyTrash(int accountId) throws SQLException;

    /**
     * Khôi phục bản thư từ Thùng rác về thư mục cũ (original_folder_id); nếu không còn thư mục cũ
     * thì về thư mục mặc định theo vai trò (người gửi về SENT, người nhận về INBOX).
     */
    boolean restoreTrashMail(int entryId, int accountId) throws SQLException;

    /**
     * Tìm theo tiêu đề/nội dung, có thể lọc theo email hoặc tên người gửi,
     * trong phạm vi thư của tài khoản và (tuỳ chọn) một thư mục.
     */
    List<MailSummary> searchMail(int accountId, Integer folderId, String keyword, String fromFilter)
            throws SQLException;

    /**
     * Trả về danh sách accountId của tất cả người nhận (TO/CC/BCC) của 1 mail -
     * dùng để bắn EVENT.
     */
    List<Integer> getRecipientAccountIds(int mailId) throws SQLException;
}
