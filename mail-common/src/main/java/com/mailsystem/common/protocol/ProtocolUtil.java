// Gói "protocol": các lớp mô tả định dạng tin nhắn giữa Client và Server.
package com.mailsystem.common.protocol;

// Gson: thư viện chuyển đối tượng Java <-> chuỗi JSON.
import com.google.gson.Gson;
// GsonBuilder: tạo đối tượng Gson với cấu hình tuỳ chọn.
import com.google.gson.GsonBuilder;
// JsonElement: một giá trị JSON bất kỳ.
import com.google.gson.JsonElement;
// JsonObject: một đối tượng JSON {...}.
import com.google.gson.JsonObject;
// JsonParseException: lỗi khi chuỗi không phải JSON hợp lệ.
import com.google.gson.JsonParseException;

// IOException: lỗi vào/ra khi ghi ra luồng.
import java.io.IOException;
// Writer: luồng ghi ký tự (ở đây là luồng ra của socket).
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

    // Một đối tượng Gson dùng chung (Gson an toàn khi nhiều thread dùng cùng lúc).
    // Cấu hình mặc định: JSON nằm trên 1 dòng, không xuống dòng thụt lề.
    private static final Gson gson = new GsonBuilder().create();

    // Constructor private: lớp tiện ích chỉ có hàm tĩnh.
    private ProtocolUtil() {
    }

    /** @return đối tượng Gson dùng chung (để nơi khác tự chuyển JSON khi cần kiểu đặc biệt) */
    public static Gson getGson() {
        return gson;
    }

    /**
     * Chuyển một đối tượng Java thành chuỗi JSON một dòng.
     *
     * @param obj đối tượng cần chuyển
     * @return chuỗi JSON
     */
    public static String toJson(Object obj) {
        return gson.toJson(obj);
    }

    /**
     * Chuyển chuỗi JSON thành đối tượng của lớp cho trước.
     *
     * @param json  chuỗi JSON
     * @param clazz lớp đích (vd ResponseMessage.class)
     * @param <T>   kiểu đích
     * @return đối tượng đã giải mã
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        return gson.fromJson(json, clazz);
    }

    /** Ghi 1 frame đã serialize sẵn. Caller chịu trách nhiệm đồng bộ hoá Writer. */
    public static void writeFrame(Writer out, String json) throws IOException {
        // Ghi nội dung JSON.
        out.write(json);
        // Ghi ký tự xuống dòng: dấu kết thúc frame.
        out.write('\n');
        // Đẩy dữ liệu trong bộ đệm ra mạng ngay.
        out.flush();
    }

    /**
     * Đọc field "type" ở tầng ngoài cùng để router biết parse tiếp thành
     * RequestMessage / ResponseMessage / EventMessage.
     *
     * @throws JsonParseException frame không phải JSON object hoặc thiếu/sai "type"
     */
    public static MessageType peekType(String json) {
        // Biến chứa đối tượng JSON sau khi giải mã.
        JsonObject obj;
        try {
            // Giải mã chuỗi thành JsonObject.
            obj = gson.fromJson(json, JsonObject.class);
        } catch (RuntimeException error) {
            // Không phải JSON hoặc không phải object: báo lỗi kèm nguyên nhân gốc.
            throw new JsonParseException("Frame không phải JSON object hợp lệ", error);
        }
        // Lấy trường "type" (chuỗi rỗng sẽ cho obj == null).
        JsonElement type = obj == null ? null : obj.get("type");
        // Thiếu "type" hoặc "type" không phải giá trị đơn (chuỗi/số) thì lỗi.
        if (type == null || !type.isJsonPrimitive()) {
            throw new JsonParseException("Frame thiếu trường \"type\"");
        }
        try {
            // Đổi chuỗi "REQUEST"/"RESPONSE"/"EVENT" thành enum MessageType.
            return MessageType.valueOf(type.getAsString());
        } catch (IllegalArgumentException error) {
            // Giá trị lạ không có trong enum.
            throw new JsonParseException("Loại frame không hợp lệ: " + type.getAsString());
        }
    }
}
