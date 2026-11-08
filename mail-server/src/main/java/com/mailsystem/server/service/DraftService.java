// Gói "service": logic nghiệp vụ phía Server.
package com.mailsystem.server.service;

// Kiểu JSON của Gson.
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
// Draft: dữ liệu bản nháp.
import com.mailsystem.common.model.Draft;
// EVENT, tên sự kiện, giới hạn, Gson dùng chung, câu trả lời.
import com.mailsystem.common.protocol.EventMessage;
import com.mailsystem.common.protocol.EventName;
import com.mailsystem.common.protocol.ProtocolConstants;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.ResponseMessage;
// DAO bản nháp.
import com.mailsystem.server.dao.DraftDAO;
import com.mailsystem.server.dao.jdbc.JdbcDraftDAO;
// Đẩy EVENT.
import com.mailsystem.server.session.SessionManager;

// Lỗi CSDL; danh sách và tập giữ thứ tự (bỏ trùng).
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Thư nháp. Người nhận được lưu nguyên văn (chưa kiểm tra) vì bản nháp có thể
 * dở dang; chỉ SEND_MAIL mới kiểm tra người nhận. Mỗi tài khoản giữ tối đa
 * MAX_DRAFTS bản nháp để không ai lấp đầy DB.
 */
public class DraftService {

    // Số bản nháp tối đa mỗi tài khoản.
    static final int MAX_DRAFTS = 200;
    // Độ dài tối đa mỗi ô người nhận (khớp cột VARCHAR(2000)).
    private static final int MAX_RECIPIENT_TEXT = 2_000;

    // Nơi truy vấn bảng draft.
    private final DraftDAO draftDAO;

    /** Constructor dùng khi chạy thật: DAO bằng JDBC. */
    public DraftService() {
        this(new JdbcDraftDAO());
    }

    // Constructor nhận DAO từ ngoài (unit test).
    DraftService(DraftDAO draftDAO) {
        this.draftDAO = draftDAO;
    }

    /** Payload {draftId?, to, cc, bcc, subject, body} → {draftId}. */
    public ResponseMessage save(String requestId, Integer accountId, JsonElement payload) {
        // Payload phải là đối tượng JSON.
        if (payload == null || !payload.isJsonObject()) {
            return ResponseMessage.error(requestId, "Thư nháp không hợp lệ.");
        }
        JsonObject values = payload.getAsJsonObject();
        // Đọc các trường (thiếu thì là chuỗi rỗng).
        Draft draft = new Draft();
        draft.setTo(AuthService.getString(values, "to"));
        draft.setCc(AuthService.getString(values, "cc"));
        draft.setBcc(AuthService.getString(values, "bcc"));
        draft.setSubject(AuthService.getString(values, "subject"));
        draft.setBody(AuthService.getString(values, "body"));
        // Kiểm tra độ dài từng trường.
        if (draft.getTo().length() > MAX_RECIPIENT_TEXT || draft.getCc().length() > MAX_RECIPIENT_TEXT
                || draft.getBcc().length() > MAX_RECIPIENT_TEXT
                || draft.getSubject().length() > ProtocolConstants.MAX_SUBJECT_CHARS
                || draft.getBody().length() > ProtocolConstants.MAX_BODY_CHARS) {
            return ResponseMessage.error(requestId, "Thư nháp vượt quá độ dài cho phép.");
        }
        try {
            int draftId;
            // Có draftId: ghi đè bản nháp cũ.
            if (values.has("draftId") && !values.get("draftId").isJsonNull()) {
                draftId = values.get("draftId").getAsInt();
                draft.setDraftId(draftId);
                // Mã không hợp lệ hoặc không phải nháp của mình.
                if (draftId <= 0 || !draftDAO.update(accountId, draft)) {
                    return ResponseMessage.error(requestId, "Không tìm thấy thư nháp.");
                }
            } else {
                // Không có draftId: tạo mới, nếu chưa vượt số bản nháp tối đa.
                if (draftDAO.findByAccount(accountId).size() >= MAX_DRAFTS) {
                    return ResponseMessage.error(requestId, "Bạn đã có quá nhiều thư nháp, hãy xoá bớt.");
                }
                draftId = draftDAO.insert(accountId, draft);
            }
            // Báo các máy khác tải lại mục Nháp.
            notifyChanged(accountId);
            // Trả mã bản nháp (Client dùng cho lần lưu sau).
            JsonObject data = new JsonObject();
            data.addProperty("draftId", draftId);
            return ResponseMessage.ok(requestId, data);
        } catch (SQLException | RuntimeException error) {
            // Lỗi CSDL, hoặc draftId không phải số.
            return ResponseMessage.error(requestId, "Không thể lưu thư nháp.");
        }
    }

    /** GET_DRAFTS → danh sách Draft. */
    public ResponseMessage list(String requestId, Integer accountId) {
        try {
            return ResponseMessage.ok(requestId, ProtocolUtil.getGson().toJsonTree(draftDAO.findByAccount(accountId)));
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không thể tải thư nháp.");
        }
    }

    /** Payload {draftIds: [...]} → {deleted}. */
    public ResponseMessage delete(String requestId, Integer accountId, JsonElement payload) {
        // Danh sách mã hợp lệ sẽ xoá.
        List<Integer> ids = new ArrayList<>();
        // Có mảng "draftIds" trong payload.
        if (payload != null && payload.isJsonObject() && payload.getAsJsonObject().has("draftIds")
                && payload.getAsJsonObject().get("draftIds").isJsonArray()) {
            JsonArray array = payload.getAsJsonObject().getAsJsonArray("draftIds");
            // Chặn mảng quá lớn.
            if (array.size() > ProtocolConstants.MAX_ENTRIES_PER_REQUEST) {
                return ResponseMessage.error(requestId, "Quá nhiều thư nháp trong một yêu cầu.");
            }
            // Tập giữ thứ tự để bỏ mã trùng.
            Set<Integer> unique = new LinkedHashSet<>();
            for (JsonElement element : array) {
                try {
                    int id = element.getAsInt();
                    // Chỉ nhận mã dương.
                    if (id > 0) {
                        unique.add(id);
                    }
                } catch (RuntimeException ignored) {
                    // Phần tử không phải số: bỏ qua.
                }
            }
            ids.addAll(unique);
        }
        // Không có mã nào hợp lệ.
        if (ids.isEmpty()) {
            return ResponseMessage.error(requestId, "Chọn ít nhất một thư nháp.");
        }
        try {
            // Xoá (DAO chỉ xoá nháp thuộc tài khoản).
            int deleted = draftDAO.delete(accountId, ids);
            if (deleted == 0) {
                return ResponseMessage.error(requestId, "Không tìm thấy thư nháp.");
            }
            notifyChanged(accountId);
            JsonObject data = new JsonObject();
            data.addProperty("deleted", deleted);
            return ResponseMessage.ok(requestId, data);
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không thể xoá thư nháp.");
        }
    }

    // Đẩy EVENT DRAFTS_UPDATED tới mọi phiên của tài khoản.
    private void notifyChanged(int accountId) {
        SessionManager.getInstance().pushEvent(accountId, new EventMessage(EventName.DRAFTS_UPDATED, new JsonObject()));
    }
}
