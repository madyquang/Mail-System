package com.mailsystem.server.service;

import com.google.gson.JsonElement;
import com.mailsystem.common.protocol.ResponseMessage;

/**
 * Nghiep vu quan trong nhat cua he thong. TODO (TV2) chi tiet cho tung ham:
 *
 * getMailList(payload = {folderId}):
 *    -> MailDAO.getMailList(accountId, folderId)
 *
 * getMailDetail(payload = {mailId}):
 *    -> MailDAO.getMailDetail(mailId), sau do TU DONG goi markRead luon
 *       (theo yeu cau de bai: mo thu ra la duoc tinh la da doc)
 *
 * sendMail(payload = {to[], cc[], bcc[], subject, body, attachments[]}):
 *    1. Validate: tat ca email trong to/cc/bcc phai ton tai (AccountDAO.findByEmail).
 *       Neu la ten group (khong phai email) -> GroupDAO.getMemberAccountIds de mo rong.
 *       Neu co bat ky nguoi nhan nao khong hop le -> tra ERROR, KHONG luu gi ca.
 *    2. Validate attachment: <=5 file, tong <=25MB, dinh dang cho phep
 *       (PDF/DOCX/PPTX/TXT/JPG/PNG/ZIP).
 *    3. MailDAO.insertMail(...) -> insertMailRecipient cho SENDER (folder=SENT)
 *       va cho tung recipient (folder=INBOX cua ho, type tuong ung).
 *    4. Giai ma base64 tung attachment, ghi file ra dia (thu muc VD: ./attachments/),
 *       insertAttachment luu duong dan.
 *    5. Sau khi luu xong: lay danh sach recipientAccountId, dung SessionManager de
 *       ban EVENT NEW_MAIL toi nhung ai dang online (khong bat buoc phai online moi
 *       gui duoc - chi la neu dang online thi UI cap nhat ngay, khong thi lan sau
 *       ho GET_MAIL_LIST se thay binh thuong).
 *
 * markRead / deleteMail (payload = {entryId hoac mailId}):
 *    -> UPDATE qua MailDAO, sau do neu can dong bo nhieu thiet bi cung 1 tai khoan,
 *       ban EVENT MAIL_READ_UPDATED / MAIL_DELETED toi cac session khac cung accountId
 *       (khong bat buoc lam ngay o Core, co the lam sau).
 *
 * searchMail (payload = {keyword, folderId?, fromFilter?}):
 *    -> MailDAO.searchMail(...)
 */
public class MailService {

    public ResponseMessage getMailList(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) return ResponseMessage.error(requestId, "Ban chua dang nhap");
        // TODO: implement
        return ResponseMessage.error(requestId, "Chua implement GET_MAIL_LIST");
    }

    public ResponseMessage getMailDetail(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) return ResponseMessage.error(requestId, "Ban chua dang nhap");
        // TODO: implement
        return ResponseMessage.error(requestId, "Chua implement GET_MAIL_DETAIL");
    }

    public ResponseMessage sendMail(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) return ResponseMessage.error(requestId, "Ban chua dang nhap");
        // TODO: implement (xem huong dan chi tiet o javadoc tren class nay)
        return ResponseMessage.error(requestId, "Chua implement SEND_MAIL");
    }

    public ResponseMessage markRead(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) return ResponseMessage.error(requestId, "Ban chua dang nhap");
        // TODO: implement
        return ResponseMessage.error(requestId, "Chua implement MARK_READ");
    }

    public ResponseMessage deleteMail(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) return ResponseMessage.error(requestId, "Ban chua dang nhap");
        // TODO: implement
        return ResponseMessage.error(requestId, "Chua implement DELETE_MAIL");
    }

    public ResponseMessage searchMail(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) return ResponseMessage.error(requestId, "Ban chua dang nhap");
        // TODO: implement
        return ResponseMessage.error(requestId, "Chua implement SEARCH_MAIL");
    }
}
