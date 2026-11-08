// Gói "model": các lớp dữ liệu (DTO) được chuyển thành JSON để gửi giữa Client và Server.
package com.mailsystem.common.model;

/** Cài đặt tài khoản trả về từ GET_SETTINGS. */
public class AccountSettings {
    // true: cho người gửi biết mình đã đọc thư (sự kiện READ_RECEIPT).
    private boolean shareReadReceipts;
    // true: tài khoản đã bật xác minh 2 bước (TOTP).
    private boolean totpEnabled;

    /** Constructor rỗng: thư viện Gson cần để tạo đối tượng khi giải mã JSON. */
    public AccountSettings() {
    }

    /**
     * Tạo cài đặt với đủ giá trị (Server dùng khi trả lời GET_SETTINGS).
     *
     * @param shareReadReceipts có chia sẻ trạng thái đã đọc không
     * @param totpEnabled       đã bật xác minh 2 bước chưa
     */
    public AccountSettings(boolean shareReadReceipts, boolean totpEnabled) {
        // Gán tham số vào trường của đối tượng.
        this.shareReadReceipts = shareReadReceipts;
        this.totpEnabled = totpEnabled;
    }

    // Getter: có chia sẻ trạng thái đã đọc không.
    public boolean isShareReadReceipts() { return shareReadReceipts; }
    // Getter: đã bật xác minh 2 bước chưa.
    public boolean isTotpEnabled() { return totpEnabled; }
}
