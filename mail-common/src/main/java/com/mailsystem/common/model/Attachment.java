package com.mailsystem.common.model;

/**
 * Dùng ở 2 chiều:
 *  - Client -> Server (SEND_MAIL): dataBase64 PHẢI có giá trị (nội dung file mã hoá base64).
 *  - Server -> Client (GET_MAIL_DETAIL): chỉ trả filename/mimeType/sizeBytes trước;
 *    dataBase64 chỉ nên trả khi Client thực sự bấm "Tải xuống" (tránh tải nặng danh sách).
 *    TODO: có thể tách thành command riêng DOWNLOAD_ATTACHMENT(attachmentId) nếu cần tối ưu.
 */
public class Attachment {
    private int attachmentId;
    private String filename;
    private String mimeType;
    private long sizeBytes;
    private String dataBase64;

    public Attachment() {
    }

    public int getAttachmentId() { return attachmentId; }
    public void setAttachmentId(int attachmentId) { this.attachmentId = attachmentId; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getDataBase64() { return dataBase64; }
    public void setDataBase64(String dataBase64) { this.dataBase64 = dataBase64; }
}
