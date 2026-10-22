package com.mailsystem.client.network;

/**
 * Chứng chỉ máy chủ cần người dùng quyết định có tin hay không.
 *
 * @param previousFingerprint null nếu lần đầu kết nối; khác null nghĩa là
 *                            chứng chỉ đã THAY ĐỔI so với lần trước (cảnh báo MITM)
 */
public record ServerIdentity(String host, int port, String subject, String fingerprint, String previousFingerprint) {

    public boolean changed() {
        return previousFingerprint != null;
    }
}
