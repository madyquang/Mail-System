package com.mailsystem.server.service;

import com.google.gson.JsonElement;
import com.mailsystem.common.protocol.ResponseMessage;

/**
 * TODO (TV2): moi ham DELETE_GROUP / ADD_MEMBER / REMOVE_MEMBER deu phai:
 *   1. GroupDAO.findOwnerId(groupId)
 *   2. Neu ownerId != accountId dang goi -> tra ResponseMessage.error(requestId,
 *      "Ban khong co quyen thuc hien thao tac nay")
 *   3. Neu dung quyen -> thuc hien thao tac.
 *
 * leaveGroup(): nguoc lai - kiem tra accountId dang goi KHONG PHAI la owner
 * (owner phai dung DELETE_GROUP, khong duoc LEAVE_GROUP).
 */
public class GroupService {

    public ResponseMessage createGroup(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) return ResponseMessage.error(requestId, "Ban chua dang nhap");
        // TODO: implement
        return ResponseMessage.error(requestId, "Chua implement CREATE_GROUP");
    }

    public ResponseMessage deleteGroup(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) return ResponseMessage.error(requestId, "Ban chua dang nhap");
        // TODO: implement + kiem tra quyen owner
        return ResponseMessage.error(requestId, "Chua implement DELETE_GROUP");
    }

    public ResponseMessage addMember(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) return ResponseMessage.error(requestId, "Ban chua dang nhap");
        // TODO: implement + kiem tra quyen owner
        return ResponseMessage.error(requestId, "Chua implement ADD_MEMBER");
    }

    public ResponseMessage removeMember(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) return ResponseMessage.error(requestId, "Ban chua dang nhap");
        // TODO: implement + kiem tra quyen owner
        return ResponseMessage.error(requestId, "Chua implement REMOVE_MEMBER");
    }

    public ResponseMessage leaveGroup(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) return ResponseMessage.error(requestId, "Ban chua dang nhap");
        // TODO: implement + kiem tra KHONG phai owner
        return ResponseMessage.error(requestId, "Chua implement LEAVE_GROUP");
    }

    public ResponseMessage getMyGroups(String requestId, Integer accountId) {
        if (accountId == null) return ResponseMessage.error(requestId, "Ban chua dang nhap");
        // TODO: implement
        return ResponseMessage.error(requestId, "Chua implement GET_MY_GROUPS");
    }
}
