// Gói "model": các lớp dữ liệu (DTO) được chuyển thành JSON để gửi giữa Client và Server.
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
    // Mã tệp (khoá chính attachment_id), Client dùng để tải về.
    private int attachmentId;
    // Tên tệp gốc hiển thị cho người dùng, vd "baocao.pdf".
    private String filename;
    // Loại nội dung, vd "application/pdf".
    private String mimeType;
    // Kích thước tệp (byte); Client dựa vào đây để biết đã tải đủ chưa.
    private long sizeBytes;
    // Một khối nội dung mã hoá Base64 (chỉ có khi trả lời DOWNLOAD_ATTACHMENT).
    private String dataBase64;

    /** Constructor rỗng: Gson cần để tạo đối tượng khi giải mã JSON. */
    public Attachment() {
    }

    // Getter / setter mã tệp.
    public int getAttachmentId() { return attachmentId; }
    public void setAttachmentId(int attachmentId) { this.attachmentId = attachmentId; }

    // Getter / setter tên tệp.
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    // Getter / setter loại nội dung.
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    // Getter / setter kích thước.
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    // Getter / setter khối dữ liệu Base64.
    public String getDataBase64() { return dataBase64; }
    public void setDataBase64(String dataBase64) { this.dataBase64 = dataBase64; }
}
