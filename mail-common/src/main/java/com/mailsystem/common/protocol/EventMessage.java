package com.mailsystem.common.protocol;

import com.google.gson.JsonElement;

/**
 * Gói tin EVENT: Server chủ động đẩy về Client, KHÔNG cần Client hỏi trước.
 * Dùng cho tính năng đồng bộ real-time (mục "sync tức thì" trong đề bài).
 */
public class EventMessage {
    private String type = MessageType.EVENT.name();
    private String eventName;
    private JsonElement data;

    public EventMessage() {
    }

    public EventMessage(EventName eventName, JsonElement data) {
        this.eventName = eventName.name();
        this.data = data;
    }

    public String getType() {
        return type;
    }

    public String getEventName() {
        return eventName;
    }

    public JsonElement getData() {
        return data;
    }
}
