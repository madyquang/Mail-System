// Gói "model": các lớp dữ liệu (DTO) được chuyển thành JSON để gửi giữa Client và Server.
package com.mailsystem.common.model;

/**
 * Thư đang nằm trong hộp thư chờ gửi (hoàn tác được hoặc hẹn giờ).
 * Ứng với một dòng bảng `outbox`; Server trả danh sách này cho lệnh GET_SCHEDULED.
 */
public class ScheduledMail {
    // Mã thư chờ (khoá chính outbox_id).
    private int outboxId;
    // Danh sách người nhận dạng chuỗi để hiển thị, vd "binh@mail.local, chi@mail.local".
    private String to;
    // Tiêu đề thư.
    private String subject;
    // Thời điểm sẽ gửi, chuỗi ISO-8601 (giờ của Server).
    private String sendAt;
    // Số tệp đính kèm của thư chờ.
    private int attachmentCount;

    /** Constructor rỗng: Gson cần để tạo đối tượng khi giải mã JSON. */
    public ScheduledMail() {
    }

    /**
     * Tạo thông tin thư chờ với đủ giá trị.
     *
     * @param outboxId        mã thư chờ
     * @param to              người nhận (chuỗi hiển thị)
     * @param subject         tiêu đề
     * @param sendAt          thời điểm gửi (ISO-8601)
     * @param attachmentCount số tệp đính kèm
     */
    public ScheduledMail(int outboxId, String to, String subject, String sendAt, int attachmentCount) {
        // Gán các tham số vào trường tương ứng.
        this.outboxId = outboxId;
        this.to = to;
        this.subject = subject;
        this.sendAt = sendAt;
        this.attachmentCount = attachmentCount;
    }

    // Getter: mã thư chờ.
    public int getOutboxId() { return outboxId; }
    // Getter: người nhận.
    public String getTo() { return to; }
    // Getter: tiêu đề.
    public String getSubject() { return subject; }
    // Getter: thời điểm gửi.
    public String getSendAt() { return sendAt; }
    // Getter: số tệp đính kèm.
    public int getAttachmentCount() { return attachmentCount; }
}
