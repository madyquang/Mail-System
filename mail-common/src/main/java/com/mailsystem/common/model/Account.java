// Gói "model": các lớp dữ liệu (DTO) được chuyển thành JSON để gửi giữa Client và Server.
package com.mailsystem.common.model;

/** Ứng với bảng `account`. Dùng khi trả thông tin user (KHÔNG bao giờ trả passwordHash về Client). */
public class Account {
    // Mã tài khoản (khoá chính account_id trong CSDL).
    private int accountId;
    // Địa chỉ email dùng để đăng nhập, vd "an@mail.local".
    private String email;
    // Tên hiển thị, vd "An".
    private String displayName;

    /** Constructor rỗng: Gson cần để tạo đối tượng khi giải mã JSON. */
    public Account() {
    }

    /**
     * Tạo tài khoản với đủ thông tin công khai.
     *
     * @param accountId   mã tài khoản
     * @param email       email đăng nhập
     * @param displayName tên hiển thị
     */
    public Account(int accountId, String email, String displayName) {
        // Gán các tham số vào trường tương ứng.
        this.accountId = accountId;
        this.email = email;
        this.displayName = displayName;
    }

    // Getter / setter mã tài khoản.
    public int getAccountId() { return accountId; }
    public void setAccountId(int accountId) { this.accountId = accountId; }

    // Getter / setter email.
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    // Getter / setter tên hiển thị.
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
}
