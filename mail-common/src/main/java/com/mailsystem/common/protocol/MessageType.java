// Gói "protocol": các lớp mô tả định dạng tin nhắn giữa Client và Server (dùng chung cho cả hai bên).
package com.mailsystem.common.protocol;

/**
 * 3 loại message duy nhất trong toàn bộ giao thức.
 * Mỗi dòng JSON gửi qua mạng đều có trường "type" mang một trong ba giá trị này,
 * bên nhận đọc trường đó trước (ProtocolUtil.peekType) để biết phải giải mã thành lớp nào.
 */
public enum MessageType {
    REQUEST,   // Client -> Server: yêu cầu làm một việc (lớp RequestMessage)
    RESPONSE,  // Server -> Client, trả lời 1 REQUEST cụ thể (khớp qua requestId) (lớp ResponseMessage)
    EVENT      // Server -> Client, chủ động đẩy (dùng cho real-time sync) (lớp EventMessage)
}
