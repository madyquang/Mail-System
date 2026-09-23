package com.mailsystem.server.session;

import com.mailsystem.common.protocol.EventMessage;
import com.mailsystem.server.ClientHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton quan ly danh sach cac ClientHandler dang ONLINE, map theo accountId.
 * Day la thanh phan cot loi cho tinh nang "dong bo tuc thi" (real-time sync):
 * khi MailService co su kien can bao (thu moi, danh dau da doc, xoa thu tu
 * thiet bi khac), no se goi SessionManager.pushEvent(accountId, event) de
 * ClientHandler tuong ung gui EVENT xuong dung Client dang mo Inbox.
 *
 * TODO (TV2 + TV4): 
 *  - Goi register()/unregister() dung cho trong ClientHandler (khi LOGIN thanh
 *    cong va khi ngat ket noi/LOGOUT).
 *  - Neu 1 tai khoan dang nhap tren nhieu thiet bi cung luc (nhieu Client cho
 *    cung 1 accountId), can doi Map value thanh List<ClientHandler> thay vi 1
 *    ClientHandler duy nhat.
 */
public class SessionManager {

    private static final SessionManager INSTANCE = new SessionManager();

    private final Map<Integer, ClientHandler> onlineClients = new ConcurrentHashMap<>();

    private SessionManager() {
    }

    public static SessionManager getInstance() {
        return INSTANCE;
    }

    public void register(int accountId, ClientHandler handler) {
        onlineClients.put(accountId, handler);
    }

    public void unregister(Integer accountId) {
        if (accountId != null) {
            onlineClients.remove(accountId);
        }
    }

    /** Goi tu Service khi can chu dong bao 1 client dang online ve thay doi moi. */
    public void pushEvent(int accountId, EventMessage event) {
        ClientHandler handler = onlineClients.get(accountId);
        if (handler != null) {
            handler.sendEvent(event);
        }
        // Neu handler == null nghia la account do dang offline -> khong can lam gi,
        // lan sau ho mo app len se GET_MAIL_LIST thay du lieu moi nhat binh thuong.
    }
}
