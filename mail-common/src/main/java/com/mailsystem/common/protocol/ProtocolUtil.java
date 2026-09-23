package com.mailsystem.common.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Lớp tiện ích dùng chung cho cả Server và Client để:
 *  - Convert Object <-> JSON (1 dòng, không pretty-print, vì ta dùng \n để framing)
 *  - Đọc field "type" của 1 dòng JSON nhận được để biết đây là RESPONSE hay EVENT
 *
 * LƯU Ý: mọi nơi gửi dữ liệu qua Socket đều PHẢI dùng gson.toJson(obj) rồi
 * println() (không được dùng println(obj) trực tiếp), và bên nhận dùng
 * readLine() để đọc đúng 1 gói tin.
 */
public final class ProtocolUtil {

    private static final Gson gson = new GsonBuilder().create();

    private ProtocolUtil() {
    }

    public static Gson getGson() {
        return gson;
    }

    public static String toJson(Object obj) {
        return gson.toJson(obj);
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        return gson.fromJson(json, clazz);
    }

    /**
     * Đọc field "type" ở tầng ngoài cùng để router biết parse tiếp thành
     * RequestMessage / ResponseMessage / EventMessage.
     * TODO (người phụ trách ClientHandler / ServerConnection dùng hàm này để dispatch).
     */
    public static MessageType peekType(String json) {
        JsonObject obj = gson.fromJson(json, JsonObject.class);
        return MessageType.valueOf(obj.get("type").getAsString());
    }
}
