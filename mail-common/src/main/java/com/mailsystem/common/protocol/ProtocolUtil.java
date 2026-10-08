package com.mailsystem.common.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Writer;

/**
 * Lớp tiện ích dùng chung cho cả Server và Client để:
 *  - Convert Object <-> JSON (1 dòng, không pretty-print, vì ta dùng \n để framing)
 *  - Đọc field "type" của 1 frame nhận được để biết đây là REQUEST/RESPONSE/EVENT
 *  - Ghi 1 frame ra luồng (JSON + '\n' + flush)
 *
 * Bên gửi luôn dùng writeFrame(); bên nhận dùng FrameReader.readFrame() để đọc
 * đúng 1 gói tin.
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

    /** Ghi 1 frame đã serialize sẵn. Caller chịu trách nhiệm đồng bộ hoá Writer. */
    public static void writeFrame(Writer out, String json) throws IOException {
        out.write(json);
        out.write('\n');
        out.flush();
    }

    /**
     * Đọc field "type" ở tầng ngoài cùng để router biết parse tiếp thành
     * RequestMessage / ResponseMessage / EventMessage.
     *
     * @throws JsonParseException frame không phải JSON object hoặc thiếu/sai "type"
     */
    public static MessageType peekType(String json) {
        JsonObject obj;
        try {
            obj = gson.fromJson(json, JsonObject.class);
        } catch (RuntimeException error) {
            throw new JsonParseException("Frame không phải JSON object hợp lệ", error);
        }
        JsonElement type = obj == null ? null : obj.get("type");
        if (type == null || !type.isJsonPrimitive()) {
            throw new JsonParseException("Frame thiếu trường \"type\"");
        }
        try {
            return MessageType.valueOf(type.getAsString());
        } catch (IllegalArgumentException error) {
            throw new JsonParseException("Loại frame không hợp lệ: " + type.getAsString());
        }
    }
}
