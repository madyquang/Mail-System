package com.mailsystem.common.model;

/** Ứng với bảng `account`. Dùng khi trả thông tin user (KHÔNG bao giờ trả passwordHash về Client). */
public class Account {
    private int accountId;
    private String email;
    private String displayName;

    public Account() {
    }

    public Account(int accountId, String email, String displayName) {
        this.accountId = accountId;
        this.email = email;
        this.displayName = displayName;
    }

    public int getAccountId() { return accountId; }
    public void setAccountId(int accountId) { this.accountId = accountId; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
}
