package com.mailsystem.common.model;

/**
 * DTO dùng cho GET_MAIL_LIST / SEARCH_MAIL — chỉ chứa thông tin rút gọn
 * để hiển thị danh sách thư (không kèm body/attachment, tránh tải nặng).
 * Ứng với: JOIN mail_recipient + mail.
 */
public class MailSummary {
    private int mailId;
    private int entryId;          // id của dòng mail_recipient (dùng để MARK_READ / DELETE_MAIL)
    private String senderEmail;
    private String senderName;
    private String subject;
    private String sentAt;        // ISO string, format ở tầng Service trước khi trả về
    private boolean read;

    public MailSummary() {
    }

    public int getMailId() { return mailId; }
    public void setMailId(int mailId) { this.mailId = mailId; }

    public int getEntryId() { return entryId; }
    public void setEntryId(int entryId) { this.entryId = entryId; }

    public String getSenderEmail() { return senderEmail; }
    public void setSenderEmail(String senderEmail) { this.senderEmail = senderEmail; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getSentAt() { return sentAt; }
    public void setSentAt(String sentAt) { this.sentAt = sentAt; }

    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }
}
