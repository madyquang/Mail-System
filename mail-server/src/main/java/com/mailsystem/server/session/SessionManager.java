// Gói "session": quản lý các phiên (kết nối đã đăng nhập) phía Server.
package com.mailsystem.server.session;

// EventMessage: gói tin EVENT đẩy về Client.
import com.mailsystem.common.protocol.EventMessage;

// Map / Set: bảng accountId -> tập phiên.
import java.util.Map;
import java.util.Set;
// ConcurrentHashMap: an toàn khi nhiều thread dùng cùng lúc.
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

    // Đối tượng duy nhất (singleton).
    private static final SessionManager INSTANCE = new SessionManager();

    // accountId -> tập phiên đang online của tài khoản.
    private final Map<Integer, Set<ClientSession>> onlineSessions = new ConcurrentHashMap<>();

    // Constructor không public: dùng getInstance() (test trong cùng gói có thể tạo riêng).
    SessionManager() {
    }

    /** @return đối tượng SessionManager duy nhất */
    public static SessionManager getInstance() {
        return INSTANCE;
    }

    /**
     * Ghi nhận một phiên vừa đăng nhập.
     *
     * @param accountId tài khoản
     * @param session   phiên (kết nối)
     */
    public void register(int accountId, ClientSession session) {
        // compute chạy nguyên tử trên khoá accountId.
        onlineSessions.compute(accountId, (id, sessions) -> {
            // Chưa có tập phiên thì tạo tập an toàn đa luồng.
            Set<ClientSession> result = sessions == null ? ConcurrentHashMap.newKeySet() : sessions;
            // Thêm phiên mới.
            result.add(session);
            return result;
        });
    }

    /** Chỉ gỡ đúng phiên này; các phiên khác của cùng tài khoản vẫn online. */
    public void unregister(Integer accountId, ClientSession session) {
        // Kết nối chưa đăng nhập: không có gì để gỡ.
        if (accountId == null) {
            return;
        }
        // Chỉ chạy nếu tài khoản đang có trong bảng.
        onlineSessions.computeIfPresent(accountId, (id, sessions) -> {
            // Gỡ phiên.
            sessions.remove(session);
            // Không còn phiên nào thì trả null để xoá khoá khỏi bảng.
            return sessions.isEmpty() ? null : sessions;
        });
    }

    /**
     * Đẩy EVENT tới mọi phiên đang online của tài khoản. Tài khoản offline thì
     * bỏ qua: lần đăng nhập sau Client sẽ tự tải dữ liệu mới nhất.
     */
    public void pushEvent(int accountId, EventMessage event) {
        // Tập phiên của tài khoản.
        Set<ClientSession> sessions = onlineSessions.get(accountId);
        // Không online: bỏ qua.
        if (sessions == null) {
            return;
        }
        // Gửi tới từng phiên (chỉ xếp vào hàng đợi gửi, không chờ mạng).
        for (ClientSession session : sessions) {
            session.sendEvent(event);
        }
    }

    /**
     * Thu hồi mọi phiên của tài khoản trừ phiên except (có thể null). Dùng khi
     * đổi mật khẩu hoặc "đăng xuất khỏi mọi thiết bị": kẻ đã đánh cắp phiên
     * không thể tiếp tục dùng sau khi chủ tài khoản đổi mật khẩu.
     *
     * @return số phiên đã thu hồi
     */
    public int revokeAll(int accountId, ClientSession except, String reason) {
        Set<ClientSession> sessions = onlineSessions.get(accountId);
        // Không có phiên nào.
        if (sessions == null) {
            return 0;
        }
        int revoked = 0;
        // Duyệt trên bản sao vì revoke() sẽ gỡ phiên khỏi tập gốc trong lúc duyệt.
        for (ClientSession session : Set.copyOf(sessions)) {
            // Bỏ qua phiên đang thực hiện thao tác (except).
            if (session != except) {
                session.revoke(reason);
                revoked++;
            }
        }
        return revoked;
    }

    /** @return số phiên đang online của tài khoản */
    public int sessionCount(int accountId) {
        Set<ClientSession> sessions = onlineSessions.get(accountId);
        return sessions == null ? 0 : sessions.size();
    }
}
