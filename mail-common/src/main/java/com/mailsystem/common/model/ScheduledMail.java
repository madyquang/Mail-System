package com.mailsystem.common.model;

/** Thư đang nằm trong hộp thư chờ gửi (hoàn tác được hoặc hẹn giờ). */
public class ScheduledMail {
    private int outboxId;
    private String to;
    private String subject;
    private String sendAt;
    private int attachmentCount;

    public ScheduledMail() {
    }

    public ScheduledMail(int outboxId, String to, String subject, String sendAt, int attachmentCount) {
        this.outboxId = outboxId;
        this.to = to;
        this.subject = subject;
        this.sendAt = sendAt;
        this.attachmentCount = attachmentCount;
    }

    public int getOutboxId() { return outboxId; }
    public String getTo() { return to; }
    public String getSubject() { return subject; }
    public String getSendAt() { return sendAt; }
    public int getAttachmentCount() { return attachmentCount; }
}
