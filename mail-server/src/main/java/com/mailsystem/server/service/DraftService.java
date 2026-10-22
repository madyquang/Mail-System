package com.mailsystem.server.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mailsystem.common.model.Draft;
import com.mailsystem.common.protocol.EventMessage;
import com.mailsystem.common.protocol.EventName;
import com.mailsystem.common.protocol.ProtocolConstants;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.ResponseMessage;
import com.mailsystem.server.dao.DraftDAO;
import com.mailsystem.server.dao.jdbc.JdbcDraftDAO;
import com.mailsystem.server.session.SessionManager;

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

    static final int MAX_DRAFTS = 200;
    private static final int MAX_RECIPIENT_TEXT = 2_000;

    private final DraftDAO draftDAO;

    public DraftService() {
        this(new JdbcDraftDAO());
    }

    DraftService(DraftDAO draftDAO) {
        this.draftDAO = draftDAO;
    }

    /** Payload {draftId?, to, cc, bcc, subject, body} → {draftId}. */
    public ResponseMessage save(String requestId, Integer accountId, JsonElement payload) {
        if (payload == null || !payload.isJsonObject()) {
            return ResponseMessage.error(requestId, "Thư nháp không hợp lệ.");
        }
        JsonObject values = payload.getAsJsonObject();
        Draft draft = new Draft();
        draft.setTo(AuthService.getString(values, "to"));
        draft.setCc(AuthService.getString(values, "cc"));
        draft.setBcc(AuthService.getString(values, "bcc"));
        draft.setSubject(AuthService.getString(values, "subject"));
        draft.setBody(AuthService.getString(values, "body"));
        if (draft.getTo().length() > MAX_RECIPIENT_TEXT || draft.getCc().length() > MAX_RECIPIENT_TEXT
                || draft.getBcc().length() > MAX_RECIPIENT_TEXT
                || draft.getSubject().length() > ProtocolConstants.MAX_SUBJECT_CHARS
                || draft.getBody().length() > ProtocolConstants.MAX_BODY_CHARS) {
            return ResponseMessage.error(requestId, "Thư nháp vượt quá độ dài cho phép.");
        }
        try {
            int draftId;
            if (values.has("draftId") && !values.get("draftId").isJsonNull()) {
                draftId = values.get("draftId").getAsInt();
                draft.setDraftId(draftId);
                if (draftId <= 0 || !draftDAO.update(accountId, draft)) {
                    return ResponseMessage.error(requestId, "Không tìm thấy thư nháp.");
                }
            } else {
                if (draftDAO.findByAccount(accountId).size() >= MAX_DRAFTS) {
                    return ResponseMessage.error(requestId, "Bạn đã có quá nhiều thư nháp, hãy xoá bớt.");
                }
                draftId = draftDAO.insert(accountId, draft);
            }
            notifyChanged(accountId);
            JsonObject data = new JsonObject();
            data.addProperty("draftId", draftId);
            return ResponseMessage.ok(requestId, data);
        } catch (SQLException | RuntimeException error) {
            return ResponseMessage.error(requestId, "Không thể lưu thư nháp.");
        }
    }

    public ResponseMessage list(String requestId, Integer accountId) {
        try {
            return ResponseMessage.ok(requestId, ProtocolUtil.getGson().toJsonTree(draftDAO.findByAccount(accountId)));
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không thể tải thư nháp.");
        }
    }

    /** Payload {draftIds: [...]} → {deleted}. */
    public ResponseMessage delete(String requestId, Integer accountId, JsonElement payload) {
        List<Integer> ids = new ArrayList<>();
        if (payload != null && payload.isJsonObject() && payload.getAsJsonObject().has("draftIds")
                && payload.getAsJsonObject().get("draftIds").isJsonArray()) {
            JsonArray array = payload.getAsJsonObject().getAsJsonArray("draftIds");
            if (array.size() > ProtocolConstants.MAX_ENTRIES_PER_REQUEST) {
                return ResponseMessage.error(requestId, "Quá nhiều thư nháp trong một yêu cầu.");
            }
            Set<Integer> unique = new LinkedHashSet<>();
            for (JsonElement element : array) {
                try {
                    int id = element.getAsInt();
                    if (id > 0) {
                        unique.add(id);
                    }
                } catch (RuntimeException ignored) {
                }
            }
            ids.addAll(unique);
        }
        if (ids.isEmpty()) {
            return ResponseMessage.error(requestId, "Chọn ít nhất một thư nháp.");
        }
        try {
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

    private void notifyChanged(int accountId) {
        SessionManager.getInstance().pushEvent(accountId, new EventMessage(EventName.DRAFTS_UPDATED, new JsonObject()));
    }
}
