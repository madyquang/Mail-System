// Gói "protocol": các lớp mô tả định dạng tin nhắn giữa Client và Server.
package com.mailsystem.common.protocol;

// JsonElement (Gson): một giá trị JSON bất kỳ.
import com.google.gson.JsonElement;

/**
 * Gói tin RESPONSE: Server trả lời cho đúng 1 REQUEST (khớp qua requestId).
 * status = "OK" hoặc "ERROR". Nếu ERROR, message chứa lý do, data có thể null.
 */
public class ResponseMessage {
    // Loại message, luôn là "RESPONSE".
    private String type = MessageType.RESPONSE.name();
    // Mã của yêu cầu được trả lời (null với thông báo cấp kết nối, vd Server quá tải).
    private String requestId;
    private String status; // "OK" | "ERROR"
    // Câu thông báo cho người dùng (mặc định chuỗi rỗng).
    private String message = "";
    private String code;       // null hoặc mã lỗi (ErrorCodes)
    // Kết quả khi thành công.
    private JsonElement data;

    /** Constructor rỗng: Gson cần để tạo đối tượng khi giải mã JSON (phía Client). */
    public ResponseMessage() {
    }

    /**
     * Tạo câu trả lời thành công.
     *
     * @param requestId mã yêu cầu được trả lời
     * @param data      kết quả (có thể null)
     * @return ResponseMessage với status "OK"
     */
    public static ResponseMessage ok(String requestId, JsonElement data) {
        // Tạo đối tượng rỗng rồi gán từng trường.
        ResponseMessage r = new ResponseMessage();
        r.requestId = requestId;
        r.status = "OK";
        r.data = data;
        return r;
    }

    /**
     * Tạo câu trả lời lỗi (không có mã lỗi).
     *
     * @param requestId mã yêu cầu được trả lời
     * @param message   lý do lỗi, hiện cho người dùng
     * @return ResponseMessage với status "ERROR"
     */
    public static ResponseMessage error(String requestId, String message) {
        // Tạo đối tượng rỗng rồi gán từng trường (data giữ null).
        ResponseMessage r = new ResponseMessage();
        r.requestId = requestId;
        r.status = "ERROR";
        r.message = message;
        return r;
    }

    /**
     * Lỗi có mã máy đọc được, để Client phản ứng khác nhau mà không phải so
     * khớp câu chữ (VD: TOTP_REQUIRED → hiện ô nhập mã 2 lớp).
     */
    public static ResponseMessage error(String requestId, String code, String message) {
        // Dùng lại hàm error ở trên rồi gán thêm mã lỗi.
        ResponseMessage r = error(requestId, message);
        r.code = code;
        return r;
    }

    /** @return mã lỗi (ErrorCodes) hoặc null */
    public String getCode() {
        return code;
    }

    /** @return loại message ("RESPONSE") */
    public String getType() {
        return type;
    }

    /** @return mã yêu cầu được trả lời */
    public String getRequestId() {
        return requestId;
    }

    /** @param requestId mã yêu cầu mới */
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    /** @return "OK" hoặc "ERROR" */
    public String getStatus() {
        return status;
    }

    /** @param status trạng thái mới */
    public void setStatus(String status) {
        this.status = status;
    }

    /** @return true nếu thành công (so hằng trước để không lỗi khi status null) */
    public boolean isOk() {
        return "OK".equals(status);
    }

    /** @return câu thông báo */
    public String getMessage() {
        return message;
    }

    /** @param message câu thông báo mới */
    public void setMessage(String message) {
        this.message = message;
    }

    /** @return kết quả kèm theo */
    public JsonElement getData() {
        return data;
    }

    /** @param data kết quả mới */
    public void setData(JsonElement data) {
        this.data = data;
    }
}
