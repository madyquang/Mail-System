package com.mailsystem.server.service;

import com.google.gson.JsonElement;
import com.mailsystem.common.protocol.ResponseMessage;

/**
 * Xu ly nghiep vu REGISTER / LOGIN / LOGOUT.
 * TODO (TV2):
 *  1. register(): doc email/password/displayName tu payload -> kiem tra email da
 *     ton tai chua (AccountDAO.existsByEmail) -> hash password (VD: dung
 *     org.mindrot:jbcrypt hoac MessageDigest SHA-256+salt) -> insertAccount ->
 *     FolderDAO.createDefaultFolders(accountId) -> tra ResponseMessage.ok(...)
 *  2. login(): tim account theo email -> so sanh password_hash -> neu dung,
 *     tra ve accountId + displayName + danh sach folder trong data.
 *  3. Nho: KHONG BAO GIO tra password_hash ve Client du bat ky truong hop nao.
 */
public class AuthService {

    public ResponseMessage register(String requestId, JsonElement payload) {
        // TODO: implement
        return ResponseMessage.error(requestId, "Chua implement REGISTER");
    }

    public ResponseMessage login(String requestId, JsonElement payload) {
        // TODO: implement
        return ResponseMessage.error(requestId, "Chua implement LOGIN");
    }
}
