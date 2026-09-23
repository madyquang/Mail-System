package com.mailsystem.common.model;

import java.util.List;

/** DTO đầy đủ dùng cho GET_MAIL_DETAIL: nội dung + danh sách người nhận + attachment. */
public class MailDetail {
    private int mailId;
    private String senderEmail;
    private String senderName;
    private String subject;
    private String body;
    private String sentAt;
    private List<String> to;
    private List<String> cc;
    private List<String> bcc;
    private List<Attachment> attachments;

    public MailDetail() {
    }

    public int getMailId() { return mailId; }
    public void setMailId(int mailId) { this.mailId = mailId; }

    public String getSenderEmail() { return senderEmail; }
    public void setSenderEmail(String senderEmail) { this.senderEmail = senderEmail; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public String getSentAt() { return sentAt; }
    public void setSentAt(String sentAt) { this.sentAt = sentAt; }

    public List<String> getTo() { return to; }
    public void setTo(List<String> to) { this.to = to; }

    public List<String> getCc() { return cc; }
    public void setCc(List<String> cc) { this.cc = cc; }

    public List<String> getBcc() { return bcc; }
    public void setBcc(List<String> bcc) { this.bcc = bcc; }

    public List<Attachment> getAttachments() { return attachments; }
    public void setAttachments(List<Attachment> attachments) { this.attachments = attachments; }
}
