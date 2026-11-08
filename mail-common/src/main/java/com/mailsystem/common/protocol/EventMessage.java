// Gói "protocol": các lớp mô tả định dạng tin nhắn giữa Client và Server.
package com.mailsystem.common.protocol;

// JsonElement (thư viện Gson): một giá trị JSON bất kỳ (object, mảng, chuỗi, số...).
import com.google.gson.JsonElement;

/**
 * Gói tin EVENT: Server chủ động đẩy về Client, KHÔNG cần Client hỏi trước.
 * Dùng cho tính năng đồng bộ real-time (mục "sync tức thì" trong đề bài).
 * Dạng JSON: {"type":"EVENT","eventName":"NEW_MAIL","data":{...}}.
 */
public class EventMessage {
    // Loại message, luôn là "EVENT" (gán sẵn để Gson ghi ra JSON).
    private String type = MessageType.EVENT.name();
    // Tên sự kiện (một giá trị của enum EventName, lưu dạng chuỗi).
    private String eventName;
    // Dữ liệu kèm theo sự kiện (tuỳ loại sự kiện, xem EventName).
    private JsonElement data;

    /** Constructor rỗng: Gson cần để tạo đối tượng khi giải mã JSON. */
    public EventMessage() {
    }

    /**
     * Tạo sự kiện để Server gửi đi.
     *
     * @param eventName loại sự kiện
     * @param data      dữ liệu kèm theo
     */
    public EventMessage(EventName eventName, JsonElement data) {
        // Lưu tên enum dạng chuỗi (vd "NEW_MAIL").
        this.eventName = eventName.name();
        // Lưu dữ liệu kèm theo.
        this.data = data;
    }

    /** @return loại message ("EVENT") */
    public String getType() {
        return type;
    }

    /** @return tên sự kiện, vd "NEW_MAIL" */
    public String getEventName() {
        return eventName;
    }

    /** @return dữ liệu kèm theo sự kiện */
    public JsonElement getData() {
        return data;
    }
}
