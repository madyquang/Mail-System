package com.mailsystem.common.protocol;

import com.google.gson.JsonElement;

/**
 * Gói tin RESPONSE: Server trả lời cho đúng 1 REQUEST (khớp qua requestId).
 * status = "OK" hoặc "ERROR". Nếu ERROR, message chứa lý do, data có thể null.
 */
public class ResponseMessage {
    private String type = MessageType.RESPONSE.name();
    private String requestId;
    private String status; // "OK" | "ERROR"
    private String message = "";
    private JsonElement data;

    public ResponseMessage() {
    }

    public static ResponseMessage ok(String requestId, JsonElement data) {
        ResponseMessage r = new ResponseMessage();
        r.requestId = requestId;
        r.status = "OK";
        r.data = data;
        return r;
    }

    public static ResponseMessage error(String requestId, String message) {
        ResponseMessage r = new ResponseMessage();
        r.requestId = requestId;
        r.status = "ERROR";
        r.message = message;
        return r;
    }

    public String getType() {
        return type;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isOk() {
        return "OK".equals(status);
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public JsonElement getData() {
        return data;
    }

    public void setData(JsonElement data) {
        this.data = data;
    }
}
