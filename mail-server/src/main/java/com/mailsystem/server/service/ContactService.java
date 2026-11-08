// Gói "service": logic nghiệp vụ phía Server.
package com.mailsystem.server.service;

// Gson: kiểu JSON tổng quát và đối tượng JSON.
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
// Contact: một gợi ý người nhận.
import com.mailsystem.common.model.Contact;
// ProtocolUtil: Gson dùng chung; ResponseMessage: câu trả lời gửi về Client.
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.ResponseMessage;
// ContactDAO / JdbcContactDAO: truy vấn gợi ý trong CSDL.
import com.mailsystem.server.dao.ContactDAO;
import com.mailsystem.server.dao.jdbc.JdbcContactDAO;

// SQLException: lỗi CSDL.
import java.sql.SQLException;
// ArrayList / List: danh sách.
import java.util.ArrayList;
import java.util.List;

/**
 * Gợi ý người nhận khi gõ (autocomplete). Payload {query, limit?, includeGroups?}.
 * Nhóm (chỉ nhóm mình là thành viên, tức là nhóm mình gửi tới được) xếp trước,
 * rồi tới người: mọi tài khoản có email/tên khớp, người đã trao đổi thư nhiều với mình
 * xếp trước. Giới hạn kết quả nhỏ để frame trả về luôn nhỏ; client gọi lại
 * sau mỗi lần gõ (có debounce) nên request phải rẻ.
 */
public class ContactService {

    // Số gợi ý tối đa mỗi lần.
    static final int MAX_LIMIT = 10;
    // Chuỗi tìm dài quá thì bỏ qua (trả rỗng).
    static final int MAX_QUERY_CHARS = 100;

    // Nơi truy vấn CSDL.
    private final ContactDAO contactDAO;

    /** Constructor dùng khi chạy thật: DAO bằng JDBC. */
    public ContactService() {
        this(new JdbcContactDAO());
    }

    // Constructor nhận DAO từ ngoài (unit test truyền DAO giả).
    ContactService(ContactDAO contactDAO) {
        this.contactDAO = contactDAO;
    }

    /**
     * Xử lý lệnh SUGGEST_CONTACTS.
     *
     * @param requestId mã yêu cầu
     * @param accountId người đang gõ
     * @param payload   {query, limit?, includeGroups?}
     * @return danh sách Contact (nhóm trước, người sau)
     */
    public ResponseMessage suggest(String requestId, Integer accountId, JsonElement payload) {
        // Payload phải là đối tượng JSON.
        if (payload == null || !payload.isJsonObject()) {
            return ResponseMessage.error(requestId, "Yêu cầu gợi ý không hợp lệ.");
        }
        JsonObject values = payload.getAsJsonObject();
        // Chuỗi người dùng đang gõ, bỏ khoảng trắng hai đầu.
        String query = AuthService.getString(values, "query").trim();
        // Rỗng hoặc quá dài: trả danh sách rỗng (không phải lỗi).
        if (query.isEmpty() || query.length() > MAX_QUERY_CHARS) {
            return ResponseMessage.ok(requestId, ProtocolUtil.getGson().toJsonTree(List.of()));
        }
        // Số kết quả mong muốn, mặc định tối đa.
        int limit = MAX_LIMIT;
        if (values.has("limit")) {
            try {
                // Ép vào khoảng [1, MAX_LIMIT].
                limit = Math.max(1, Math.min(MAX_LIMIT, values.get("limit").getAsInt()));
            } catch (RuntimeException ignored) {
                // "limit" không phải số: giữ mặc định.
            }
        }
        // Có gợi ý nhóm không (mặc định có).
        boolean includeGroups = !values.has("includeGroups") || values.get("includeGroups").getAsBoolean();

        // Mẫu LIKE "%chuỗi%" (khớp ở bất kỳ đâu), đã escape ký tự đặc biệt.
        String contains = "%" + escape(query) + "%";
        try {
            List<Contact> result = new ArrayList<>();
            // Tối đa 3 nhóm, xếp trước.
            if (includeGroups) {
                result.addAll(contactDAO.findGroups(accountId, contains, Math.min(3, limit)));
            }
            // Phần còn lại là người; mẫu "chuỗi%" dùng để ưu tiên người có email bắt đầu bằng chuỗi gõ.
            result.addAll(contactDAO.findPeople(accountId, contains, escape(query) + "%", limit - result.size()));
            // Trả danh sách dạng JSON.
            return ResponseMessage.ok(requestId, ProtocolUtil.getGson().toJsonTree(result));
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không tải được gợi ý.");
        }
    }

    /** Escape ký tự đặc biệt của LIKE để "50%" không khớp mọi thứ. */
    static String escape(String value) {
        // Ký tự escape là '!': nhân đôi '!' trước, rồi đặt '!' trước '%' và '_'.
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
