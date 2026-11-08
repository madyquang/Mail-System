// Gói "protocol": các lớp mô tả định dạng tin nhắn giữa Client và Server.
package com.mailsystem.common.protocol;

// JsonElement (Gson): một giá trị JSON bất kỳ.
import com.google.gson.JsonElement;

/**
 * Gói tin REQUEST: Client gửi lên Server.
 * payload là JsonElement (thường là JsonObject) chứa dữ liệu tuỳ theo command,
 * ví dụ command=LOGIN thì payload = { "email": "...", "password": "..." }.
 *
 * Cách dùng phía Client:
 *   RequestMessage req = new RequestMessage(UUID.randomUUID().toString(), Command.LOGIN, payloadJson);
 *   String line = ProtocolUtil.toJson(req);   // gửi qua socket, nhớ println (Auto flush + thêm \n)
 */
public class RequestMessage {
    // Loại message, luôn là "REQUEST".
    private String type = MessageType.REQUEST.name();
    // Mã riêng của yêu cầu (UUID); Server trả lời kèm đúng mã này.
    private String requestId;
    // Tên lệnh dạng chuỗi (một giá trị của enum Command), vd "SEND_MAIL".
    private String command;
    // Dữ liệu kèm theo lệnh (có thể null nếu lệnh không cần dữ liệu).
    private JsonElement payload;

    /** Constructor rỗng: Gson cần để tạo đối tượng khi giải mã JSON (phía Server). */
    public RequestMessage() {
    }

    /**
     * Tạo yêu cầu để Client gửi đi.
     *
     * @param requestId mã yêu cầu (UUID)
     * @param command   lệnh
     * @param payload   dữ liệu kèm theo
     */
    public RequestMessage(String requestId, Command command, JsonElement payload) {
        // Gán mã yêu cầu.
        this.requestId = requestId;
        // Lưu tên lệnh dạng chuỗi.
        this.command = command.name();
        // Gán dữ liệu kèm theo.
        this.payload = payload;
    }

    /** @return loại message ("REQUEST") */
    public String getType() {
        return type;
    }

    /** @return mã yêu cầu */
    public String getRequestId() {
        return requestId;
    }

    /** @param requestId mã yêu cầu mới */
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    /** @return tên lệnh dạng chuỗi (chưa kiểm tra hợp lệ) */
    public String getCommand() {
        return command;
    }

    /**
     * Đổi tên lệnh thành enum.
     *
     * @return giá trị Command tương ứng
     * @throws IllegalArgumentException nếu tên lệnh không có trong enum (lệnh lạ)
     */
    public Command getCommandEnum() {
        return Command.valueOf(command);
    }

    /** @param command lệnh mới (lưu dạng chuỗi) */
    public void setCommand(Command command) {
        this.command = command.name();
    }

    /** @return dữ liệu kèm theo */
    public JsonElement getPayload() {
        return payload;
    }

    /** @param payload dữ liệu kèm theo mới */
    public void setPayload(JsonElement payload) {
        this.payload = payload;
    }
}
