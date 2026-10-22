package com.mailsystem.common.model;

/**
 * DTO dùng cho GET_MAIL_LIST / SEARCH_MAIL — chỉ chứa thông tin rút gọn
 * để hiển thị danh sách thư (không kèm toàn bộ body/attachment, tránh tải nặng).
 * Ứng với: JOIN mail_recipient + mail.
 */
public class MailSummary {
    private int mailId;
    private int entryId;          // id của dòng mail_recipient (dùng cho mọi thao tác trên "bản thư của tôi")
    private String senderEmail;
    private String senderName;
    private String subject;
    private String snippet;       // tối đa ~160 ký tự đầu của nội dung
    private String sentAt;        // ISO-8601 không múi giờ
    private boolean read;
    private boolean starred;
    private boolean hasAttachments;
    private String folderType;    // INBOX | SENT | TRASH | CUSTOM — hữu ích ở view "Có gắn sao"

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

    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }

    public String getSentAt() { return sentAt; }
    public void setSentAt(String sentAt) { this.sentAt = sentAt; }

    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }

    public boolean isStarred() { return starred; }
    public void setStarred(boolean starred) { this.starred = starred; }

    public boolean isHasAttachments() { return hasAttachments; }
    public void setHasAttachments(boolean hasAttachments) { this.hasAttachments = hasAttachments; }

    public String getFolderType() { return folderType; }
    public void setFolderType(String folderType) { this.folderType = folderType; }
}
