// Gói "model": các lớp dữ liệu (DTO) được chuyển thành JSON để gửi giữa Client và Server.
package com.mailsystem.common.model;

// List: danh sách có thứ tự.
import java.util.List;

/** DTO đầy đủ dùng cho GET_MAIL_DETAIL: nội dung + danh sách người nhận + attachment. */
public class MailDetail {
    // Mã lá thư (bảng mail).
    private int mailId;
    private int entryId;          // bản thư của người xem đã được mở
    // Mã thư đầu hội thoại (0 = thư này tự mở đầu hội thoại).
    private int threadId;
    private List<ReadReceipt> readReceipts; // chỉ có khi người xem là người gửi
    /** Chỉ dùng phía Server (transient: Gson không gửi đi): lần mở này là lần đọc đầu tiên. */
    private transient boolean newlyRead;
    // Chỉ dùng phía Server (transient): mã tài khoản người gửi, để gửi sự kiện READ_RECEIPT.
    private transient int senderAccountId;
    // Người xem có gắn sao thư này không.
    private boolean starred;
    // Loại thư mục đang chứa bản thư của người xem.
    private String folderType;
    // Email và tên người gửi.
    private String senderEmail;
    private String senderName;
    // Tiêu đề và nội dung đầy đủ.
    private String subject;
    private String body;
    // Thời điểm gửi (ISO-8601).
    private String sentAt;
    // Danh sách người nhận To / Cc / Bcc (Bcc chỉ đầy đủ khi người xem là người gửi).
    private List<String> to;
    private List<String> cc;
    private List<String> bcc;
    // Thông tin tệp đính kèm (chưa kèm nội dung).
    private List<Attachment> attachments;

    /** Constructor rỗng: Gson cần để tạo đối tượng khi giải mã JSON. */
    public MailDetail() {
    }

    // Getter mã hội thoại: threadId = 0 thì dùng chính mailId.
    public int getThreadId() { return threadId == 0 ? mailId : threadId; }
    public void setThreadId(int threadId) { this.threadId = threadId; }

    // Getter / setter "lần mở này là lần đọc đầu tiên" (chỉ phía Server).
    public boolean isNewlyRead() { return newlyRead; }
    public void setNewlyRead(boolean newlyRead) { this.newlyRead = newlyRead; }

    // Getter / setter mã tài khoản người gửi (chỉ phía Server).
    public int getSenderAccountId() { return senderAccountId; }
    public void setSenderAccountId(int senderAccountId) { this.senderAccountId = senderAccountId; }

    // Getter / setter danh sách "đã đọc".
    public List<ReadReceipt> getReadReceipts() { return readReceipts; }
    public void setReadReceipts(List<ReadReceipt> readReceipts) { this.readReceipts = readReceipts; }

    // Getter / setter mã bản thư của người xem.
    public int getEntryId() { return entryId; }
    public void setEntryId(int entryId) { this.entryId = entryId; }

    // Getter / setter trạng thái gắn sao.
    public boolean isStarred() { return starred; }
    public void setStarred(boolean starred) { this.starred = starred; }

    // Getter / setter loại thư mục.
    public String getFolderType() { return folderType; }
    public void setFolderType(String folderType) { this.folderType = folderType; }

    // Getter / setter mã thư.
    public int getMailId() { return mailId; }
    public void setMailId(int mailId) { this.mailId = mailId; }

    // Getter / setter email người gửi.
    public String getSenderEmail() { return senderEmail; }
    public void setSenderEmail(String senderEmail) { this.senderEmail = senderEmail; }

    // Getter / setter tên người gửi.
    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    // Getter / setter tiêu đề.
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    // Getter / setter nội dung.
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    // Getter / setter thời điểm gửi.
    public String getSentAt() { return sentAt; }
    public void setSentAt(String sentAt) { this.sentAt = sentAt; }

    // Getter / setter người nhận To.
    public List<String> getTo() { return to; }
    public void setTo(List<String> to) { this.to = to; }

    // Getter / setter người nhận Cc.
    public List<String> getCc() { return cc; }
    public void setCc(List<String> cc) { this.cc = cc; }

    // Getter / setter người nhận Bcc.
    public List<String> getBcc() { return bcc; }
    public void setBcc(List<String> bcc) { this.bcc = bcc; }

    // Getter / setter tệp đính kèm.
    public List<Attachment> getAttachments() { return attachments; }
    public void setAttachments(List<Attachment> attachments) { this.attachments = attachments; }
}
