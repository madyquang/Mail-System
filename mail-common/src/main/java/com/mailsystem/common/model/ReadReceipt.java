package com.mailsystem.common.model;

/** Người nhận đã mở thư lúc nào (chỉ khi người đó cho phép chia sẻ). */
public class ReadReceipt {
    private String email;
    private String displayName;
    private String readAt;

    public ReadReceipt() {
    }

    public ReadReceipt(String email, String displayName, String readAt) {
        this.email = email;
        this.displayName = displayName;
        this.readAt = readAt;
    }

    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getReadAt() { return readAt; }
}
