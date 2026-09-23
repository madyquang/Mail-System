package com.mailsystem.common.protocol;

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
    private String type = MessageType.REQUEST.name();
    private String requestId;
    private String command;
    private JsonElement payload;

    public RequestMessage() {
    }

    public RequestMessage(String requestId, Command command, JsonElement payload) {
        this.requestId = requestId;
        this.command = command.name();
        this.payload = payload;
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

    public String getCommand() {
        return command;
    }

    public Command getCommandEnum() {
        return Command.valueOf(command);
    }

    public void setCommand(Command command) {
        this.command = command.name();
    }

    public JsonElement getPayload() {
        return payload;
    }

    public void setPayload(JsonElement payload) {
        this.payload = payload;
    }
}
