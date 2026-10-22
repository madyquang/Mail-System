package com.mailsystem.client;

/**
 * Điểm vào cho bản đóng gói (jpackage / fat jar). JavaFX yêu cầu lớp main KHÔNG
 * kế thừa Application khi chạy từ classpath, nên lớp này chỉ chuyển tiếp sang
 * MailClientApp.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        MailClientApp.main(args);
    }
}
