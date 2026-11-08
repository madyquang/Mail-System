// Gói "model": các lớp dữ liệu (DTO) được chuyển thành JSON để gửi giữa Client và Server.
package com.mailsystem.common.model;

/**
 * DTO dùng cho GET_MAIL_LIST / SEARCH_MAIL — chỉ chứa thông tin rút gọn
 * để hiển thị danh sách thư (không kèm toàn bộ body/attachment, tránh tải nặng).
 * Ứng với: JOIN mail_recipient + mail.
 */
public class MailSummary {
    // Mã lá thư (bảng mail).
    private int mailId;
    private int entryId;          // id của dòng mail_recipient (dùng cho mọi thao tác trên "bản thư của tôi")
    // Email và tên người gửi.
    private String senderEmail;
    private String senderName;
    // Tiêu đề thư.
    private String subject;
    private String snippet;       // tối đa ~160 ký tự đầu của nội dung
    private String sentAt;        // ISO-8601 không múi giờ
    // Người xem đã đọc thư này chưa.
    private boolean read;
    // Người xem có gắn sao không.
    private boolean starred;
    // Thư có tệp đính kèm không (để hiện biểu tượng ghim).
    private boolean hasAttachments;
    private String folderType;    // INBOX | SENT | TRASH | CUSTOM — hữu ích ở view "Có gắn sao"
    private int threadId;         // = mailId của thư đầu hội thoại
    private int threadCount = 1;  // số thư của hội thoại trong thư mục này (khi xem theo hội thoại)
    private java.util.List<Integer> threadEntryIds; // mọi entryId của hội thoại trong thư mục này

    /** Constructor rỗng: Gson cần để tạo đối tượng khi giải mã JSON. */
    public MailSummary() {
    }

    // Getter / setter mã thư.
    public int getMailId() { return mailId; }
    public void setMailId(int mailId) { this.mailId = mailId; }

    // Getter / setter mã bản thư của người xem.
    public int getEntryId() { return entryId; }
    public void setEntryId(int entryId) { this.entryId = entryId; }

    // Getter / setter email người gửi.
    public String getSenderEmail() { return senderEmail; }
    public void setSenderEmail(String senderEmail) { this.senderEmail = senderEmail; }

    // Getter / setter tên người gửi.
    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    // Getter / setter tiêu đề.
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    // Getter / setter đoạn trích nội dung.
    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }

    // Getter / setter thời điểm gửi.
    public String getSentAt() { return sentAt; }
    public void setSentAt(String sentAt) { this.sentAt = sentAt; }

    // Getter / setter trạng thái đã đọc.
    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }

    // Getter / setter trạng thái gắn sao.
    public boolean isStarred() { return starred; }
    public void setStarred(boolean starred) { this.starred = starred; }

    // Getter / setter "có tệp đính kèm".
    public boolean isHasAttachments() { return hasAttachments; }
    public void setHasAttachments(boolean hasAttachments) { this.hasAttachments = hasAttachments; }

    // Getter mã hội thoại: threadId = 0 (chưa gán, thư tự mở đầu hội thoại) thì dùng chính mailId.
    public int getThreadId() { return threadId == 0 ? mailId : threadId; }
    public void setThreadId(int threadId) { this.threadId = threadId; }

    // Getter / setter số thư trong hội thoại.
    public int getThreadCount() { return threadCount; }
    public void setThreadCount(int threadCount) { this.threadCount = threadCount; }

    /** entryId của mọi thư trong hội thoại (khi gộp); nếu không gộp thì chỉ có entryId của dòng này. */
    public java.util.List<Integer> getThreadEntryIds() {
        // Danh sách null/rỗng (không gộp hội thoại) thì trả danh sách chỉ gồm entryId của dòng này.
        return threadEntryIds == null || threadEntryIds.isEmpty() ? java.util.List.of(entryId) : threadEntryIds;
    }
    // Setter danh sách entryId của hội thoại.
    public void setThreadEntryIds(java.util.List<Integer> threadEntryIds) { this.threadEntryIds = threadEntryIds; }

    // Getter / setter loại thư mục chứa thư.
    public String getFolderType() { return folderType; }
    public void setFolderType(String folderType) { this.folderType = folderType; }
}
