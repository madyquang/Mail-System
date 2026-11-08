// Gói "session": quản lý các phiên (kết nối đã đăng nhập) phía Server.
package com.mailsystem.server.session;

// EventMessage: gói tin EVENT Server chủ động đẩy về Client.
import com.mailsystem.common.protocol.EventMessage;

/**
 * Một kết nối client đang đăng nhập mà Server có thể chủ động đẩy EVENT tới.
 * Lớp thực hiện là ClientHandler; SessionManager chỉ biết tới interface này.
 */
public interface ClientSession {

    /** Gửi EVENT không chặn (non-blocking): chỉ xếp vào hàng đợi gửi của kết nối. */
    void sendEvent(EventMessage event);

    /**
     * Thu hồi phiên: gửi EVENT SESSION_REVOKED kèm lý do, đăng xuất phiên, rồi
     * đóng kết nối sau khi đã gửi hết các frame đang chờ.
     */
    void revoke(String reason);
}
