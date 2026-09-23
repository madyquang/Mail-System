package com.mailsystem.common.protocol;

/** 3 loại message duy nhất trong toàn bộ giao thức. */
public enum MessageType {
    REQUEST,   // Client -> Server
    RESPONSE,  // Server -> Client, trả lời 1 REQUEST cụ thể (khớp qua requestId)
    EVENT      // Server -> Client, chủ động đẩy (dùng cho real-time sync)
}
