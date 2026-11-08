// Gói "dao": các interface truy cập CSDL (lớp thực hiện bằng JDBC nằm ở dao.jdbc).
package com.mailsystem.server.dao;

// ScheduledMail: thông tin thư chờ gửi để hiển thị.
import com.mailsystem.common.model.ScheduledMail;

// Connection: kết nối CSDL (dùng để chạy trong transaction của nơi gọi).
import java.sql.Connection;
// SQLException: lỗi khi chạy câu lệnh SQL.
import java.sql.SQLException;
// LocalDateTime: ngày + giờ không múi giờ.
import java.time.LocalDateTime;
// List: danh sách có thứ tự.
import java.util.List;
// Optional: kết quả "có thể không có".
import java.util.Optional;

/** Hộp thư chờ gửi (bảng outbox + outbox_attachment). Lớp thực hiện: JdbcOutboxDAO. */
public interface OutboxDAO {

    /**
     * Một tệp đính kèm đã nằm trong kho data/attachments.
     *
     * @param filename    tên gốc hiển thị
     * @param mimeType    loại nội dung
     * @param sizeBytes   kích thước (byte)
     * @param storagePath tên tệp thật trên ổ đĩa (ngẫu nhiên)
     */
    record StoredFile(String filename, String mimeType, long sizeBytes, String storagePath) {
    }

    /**
     * Một thư chờ gửi đọc ra từ CSDL.
     *
     * @param outboxId  mã thư chờ
     * @param accountId người hẹn gửi
     * @param payload   toàn bộ thư dạng JSON (to/cc/bcc/subject/body/...)
     * @param sendAt    thời điểm phải gửi
     * @param files     các tệp đính kèm
     */
    record Entry(int outboxId, int accountId, String payload, LocalDateTime sendAt, List<StoredFile> files) {
    }

    /**
     * Thêm thư chờ gửi kèm tệp.
     *
     * @return outbox_id vừa tạo
     */
    int insert(int accountId, String payload, LocalDateTime sendAt, List<StoredFile> files) throws SQLException;

    /** Các thư chờ gửi của một tài khoản (để hiện mục "Đã lên lịch"). */
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
