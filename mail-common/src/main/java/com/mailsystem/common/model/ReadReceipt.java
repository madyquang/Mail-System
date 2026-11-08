// Gói "model": các lớp dữ liệu (DTO) được chuyển thành JSON để gửi giữa Client và Server.
package com.mailsystem.common.model;

/**
 * Người nhận đã mở thư lúc nào (chỉ khi người đó cho phép chia sẻ).
 * Nằm trong MailDetail.readReceipts, chỉ người gửi mới nhận được danh sách này.
 */
public class ReadReceipt {
    // Email người đã đọc.
    private String email;
    // Tên hiển thị người đã đọc.
    private String displayName;
    // Thời điểm mở thư lần đầu, chuỗi ISO-8601 (vd "2026-11-06T09:20:11").
    private String readAt;

    /** Constructor rỗng: Gson cần để tạo đối tượng khi giải mã JSON. */
    public ReadReceipt() {
    }

    /**
     * Tạo bản ghi "đã đọc" với đủ thông tin.
     *
     * @param email       email người đọc
     * @param displayName tên hiển thị người đọc
     * @param readAt      thời điểm đọc (ISO-8601)
     */
    public ReadReceipt(String email, String displayName, String readAt) {
        // Gán các tham số vào trường tương ứng.
        this.email = email;
        this.displayName = displayName;
        this.readAt = readAt;
    }

    // Getter: email người đọc.
    public String getEmail() { return email; }
    // Getter: tên hiển thị người đọc.
    public String getDisplayName() { return displayName; }
    // Getter: thời điểm đọc.
    public String getReadAt() { return readAt; }
}
