package com.mailsystem.common.model;

/** Cài đặt tài khoản trả về từ GET_SETTINGS. */
public class AccountSettings {
    private boolean shareReadReceipts;
    private boolean totpEnabled;

    public AccountSettings() {
    }

    public AccountSettings(boolean shareReadReceipts, boolean totpEnabled) {
        this.shareReadReceipts = shareReadReceipts;
        this.totpEnabled = totpEnabled;
    }

    public boolean isShareReadReceipts() { return shareReadReceipts; }
    public boolean isTotpEnabled() { return totpEnabled; }
}
