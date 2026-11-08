// Gói "dao": các interface truy cập CSDL (lớp thực hiện bằng JDBC nằm ở dao.jdbc).
package com.mailsystem.server.dao;

// Contact: một gợi ý người nhận (người hoặc nhóm).
import com.mailsystem.common.model.Contact;

// SQLException: lỗi khi chạy câu lệnh SQL.
import java.sql.SQLException;
// List: danh sách có thứ tự.
import java.util.List;

/** Tra cứu gợi ý người nhận. Chỉ đọc. Lớp thực hiện: JdbcContactDAO. */
public interface ContactDAO {

    /**
     * Người có email hoặc tên hiển thị chứa pattern (đã escape cho LIKE, ký tự
     * escape '!'), trừ chính người dùng. Người đã trao đổi thư nhiều xếp trước,
     * rồi tới người khớp từ đầu email.
     *
     * @param accountId       người đang gõ (bị loại khỏi kết quả)
     * @param containsPattern mẫu LIKE "%chuỗi%" (khớp ở bất kỳ đâu)
     * @param prefixPattern   mẫu LIKE "chuỗi%" (khớp từ đầu, để xếp hạng)
     * @param limit           số kết quả tối đa
     */
    List<Contact> findPeople(int accountId, String containsPattern, String prefixPattern, int limit)
            throws SQLException;

    /** Nhóm mà người dùng là thành viên và tên nhóm chứa pattern. */
    List<Contact> findGroups(int accountId, String containsPattern, int limit) throws SQLException;
}
