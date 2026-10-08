package com.mailsystem.common.model;

/**
 * Metadata (và có thể kèm 1 khối nội dung) của tệp đính kèm:
 *  - GET_MAIL_DETAIL: chỉ trả attachmentId/filename/mimeType/sizeBytes.
 *  - DOWNLOAD_ATTACHMENT: trả thêm dataBase64 = 1 khối (tối đa
 *    ProtocolConstants.CHUNK_SIZE_BYTES byte) bắt đầu tại offset Client yêu cầu.
 * Chiều Client -> Server không dùng lớp này: Client tải tệp lên bằng
 * UPLOAD_ATTACHMENT_CHUNK rồi chỉ gửi uploadId trong SEND_MAIL.
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
