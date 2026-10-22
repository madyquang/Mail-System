package com.mailsystem.common.model;

/**
 * Thư nháp (bảng `draft`). Người nhận lưu nguyên dạng người dùng gõ (chuỗi
 * phân cách bằng dấu phẩy) vì bản nháp có thể chưa hợp lệ; chỉ khi gửi Server
 * mới kiểm tra người nhận.
 */
public class Draft {
    private int draftId;
    private String to;
    private String cc;
    private String bcc;
    private String subject;
    private String body;
    private String updatedAt;

    public Draft() {
    }

    public int getDraftId() { return draftId; }
    public void setDraftId(int draftId) { this.draftId = draftId; }

    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }

    public String getCc() { return cc; }
    public void setCc(String cc) { this.cc = cc; }

    public String getBcc() { return bcc; }
    public void setBcc(String bcc) { this.bcc = bcc; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
