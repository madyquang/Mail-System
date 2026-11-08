// Gói "model": các lớp dữ liệu (DTO) được chuyển thành JSON để gửi giữa Client và Server.
package com.mailsystem.common.model;

/**
 * Thư nháp (bảng `draft`). Người nhận lưu nguyên dạng người dùng gõ (chuỗi
 * phân cách bằng dấu phẩy) vì bản nháp có thể chưa hợp lệ; chỉ khi gửi Server
 * mới kiểm tra người nhận.
 */
public class Draft {
    // Mã bản nháp (khoá chính draft_id); 0 = bản nháp mới chưa lưu.
    private int draftId;
    // Người nhận To / Cc / Bcc dạng chuỗi nguyên văn.
    private String to;
    private String cc;
    private String bcc;
    // Tiêu đề.
    private String subject;
    // Nội dung.
    private String body;
    // Lần sửa gần nhất (ISO-8601), dùng để sắp xếp và hiển thị.
    private String updatedAt;

    /** Constructor rỗng: Gson cần để tạo đối tượng khi giải mã JSON; Client cũng tạo rồi gán bằng setter. */
    public Draft() {
    }

    // Getter / setter mã bản nháp.
    public int getDraftId() { return draftId; }
    public void setDraftId(int draftId) { this.draftId = draftId; }

    // Getter / setter người nhận To.
    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }

    // Getter / setter người nhận Cc.
    public String getCc() { return cc; }
    public void setCc(String cc) { this.cc = cc; }

    // Getter / setter người nhận Bcc.
    public String getBcc() { return bcc; }
    public void setBcc(String bcc) { this.bcc = bcc; }

    // Getter / setter tiêu đề.
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    // Getter / setter nội dung.
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    // Getter / setter thời điểm sửa gần nhất.
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
