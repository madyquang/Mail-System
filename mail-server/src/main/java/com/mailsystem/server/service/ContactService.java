package com.mailsystem.server.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mailsystem.common.model.Contact;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.ResponseMessage;
import com.mailsystem.server.dao.ContactDAO;
import com.mailsystem.server.dao.jdbc.JdbcContactDAO;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Gợi ý người nhận khi gõ (autocomplete). Payload {query, limit?, includeGroups?}.
 * Nhóm (chỉ nhóm mình là thành viên, tức là nhóm mình gửi tới được) xếp trước,
 * rồi tới người. Giới hạn kết quả nhỏ để frame trả về luôn nhỏ; client gọi lại
 * sau mỗi lần gõ (có debounce) nên request phải rẻ.
 */
public class ContactService {

    static final int MAX_LIMIT = 10;
    static final int MAX_QUERY_CHARS = 100;

    private final ContactDAO contactDAO;

    public ContactService() {
        this(new JdbcContactDAO());
    }

    ContactService(ContactDAO contactDAO) {
        this.contactDAO = contactDAO;
    }

    public ResponseMessage suggest(String requestId, Integer accountId, JsonElement payload) {
        if (payload == null || !payload.isJsonObject()) {
            return ResponseMessage.error(requestId, "Yêu cầu gợi ý không hợp lệ.");
        }
        JsonObject values = payload.getAsJsonObject();
        String query = AuthService.getString(values, "query").trim();
        if (query.isEmpty() || query.length() > MAX_QUERY_CHARS) {
            return ResponseMessage.ok(requestId, ProtocolUtil.getGson().toJsonTree(List.of()));
        }
        int limit = MAX_LIMIT;
        if (values.has("limit")) {
            try {
                limit = Math.max(1, Math.min(MAX_LIMIT, values.get("limit").getAsInt()));
            } catch (RuntimeException ignored) {
            }
        }
        boolean includeGroups = !values.has("includeGroups") || values.get("includeGroups").getAsBoolean();

        String contains = "%" + escape(query) + "%";
        try {
            List<Contact> result = new ArrayList<>();
            if (includeGroups) {
                result.addAll(contactDAO.findGroups(accountId, contains, Math.min(3, limit)));
            }
            result.addAll(contactDAO.findPeople(accountId, contains, escape(query) + "%", limit - result.size()));
            return ResponseMessage.ok(requestId, ProtocolUtil.getGson().toJsonTree(result));
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không tải được gợi ý.");
        }
    }

    /** Escape ký tự đặc biệt của LIKE để "50%" không khớp mọi thứ. */
    static String escape(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
