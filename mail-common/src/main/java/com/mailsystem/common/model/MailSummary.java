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
    private int threadId;         // = mailId của thư đầu hội thoại
    private int threadCount = 1;  // số thư của hội thoại trong thư mục này (khi xem theo hội thoại)
    private java.util.List<Integer> threadEntryIds; // mọi entryId của hội thoại trong thư mục này

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

    public int getThreadId() { return threadId == 0 ? mailId : threadId; }
    public void setThreadId(int threadId) { this.threadId = threadId; }

    public int getThreadCount() { return threadCount; }
    public void setThreadCount(int threadCount) { this.threadCount = threadCount; }

    /** entryId của mọi thư trong hội thoại (khi gộp); nếu không gộp thì chỉ có entryId của dòng này. */
    public java.util.List<Integer> getThreadEntryIds() {
        return threadEntryIds == null || threadEntryIds.isEmpty() ? java.util.List.of(entryId) : threadEntryIds;
    }
    public void setThreadEntryIds(java.util.List<Integer> threadEntryIds) { this.threadEntryIds = threadEntryIds; }

    public String getFolderType() { return folderType; }
    public void setFolderType(String folderType) { this.folderType = folderType; }
}
