package com.mailsystem.server.session;

import com.mailsystem.common.protocol.EventMessage;

/** Một kết nối client đang đăng nhập mà Server có thể chủ động đẩy EVENT tới. */
public interface ClientSession {

    /** Gửi EVENT không chặn (non-blocking): chỉ xếp vào hàng đợi gửi của kết nối. */
    void sendEvent(EventMessage event);
}
