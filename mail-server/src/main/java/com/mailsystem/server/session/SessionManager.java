package com.mailsystem.server.session;

import com.mailsystem.common.protocol.EventMessage;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton quản lý các kết nối đang ONLINE theo accountId. Đây là thành phần
 * cốt lõi của "server push": khi có thư mới / đánh dấu đã đọc / xoá thư, Service
 * gọi pushEvent(accountId, event) và mọi phiên của tài khoản đó nhận EVENT.
 *
 * Một tài khoản có thể đăng nhập trên NHIỀU thiết bị cùng lúc, nên mỗi
 * accountId ánh xạ tới một tập phiên. Map được truy cập đồng thời từ nhiều
 * thread xử lý client, nên dùng ConcurrentHashMap và cập nhật nguyên tử bằng
 * compute()/computeIfPresent().
 */
public class SessionManager {

    private static final SessionManager INSTANCE = new SessionManager();

    private final Map<Integer, Set<ClientSession>> onlineSessions = new ConcurrentHashMap<>();

    SessionManager() {
    }

    public static SessionManager getInstance() {
        return INSTANCE;
    }

    public void register(int accountId, ClientSession session) {
        onlineSessions.compute(accountId, (id, sessions) -> {
            Set<ClientSession> result = sessions == null ? ConcurrentHashMap.newKeySet() : sessions;
            result.add(session);
            return result;
        });
    }

    /** Chỉ gỡ đúng phiên này; các phiên khác của cùng tài khoản vẫn online. */
    public void unregister(Integer accountId, ClientSession session) {
        if (accountId == null) {
            return;
        }
        onlineSessions.computeIfPresent(accountId, (id, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;
        });
    }

    /**
     * Đẩy EVENT tới mọi phiên đang online của tài khoản. Tài khoản offline thì
     * bỏ qua: lần đăng nhập sau Client sẽ tự tải dữ liệu mới nhất.
     */
    public void pushEvent(int accountId, EventMessage event) {
        Set<ClientSession> sessions = onlineSessions.get(accountId);
        if (sessions == null) {
            return;
        }
        for (ClientSession session : sessions) {
            session.sendEvent(event);
        }
    }

    public int sessionCount(int accountId) {
        Set<ClientSession> sessions = onlineSessions.get(accountId);
        return sessions == null ? 0 : sessions.size();
    }
}
