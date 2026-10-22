package com.mailsystem.server.service;

import com.mailsystem.server.config.ServerConfig;
import com.mailsystem.server.security.LoginThrottle;
import com.mailsystem.server.security.SlidingWindowLimiter;

import java.time.Duration;

/**
 * Tập các service dùng chung cho MỌI kết nối. Service không giữ trạng thái
 * theo kết nối (trạng thái đó nằm ở ConnectionContext), nên một instance phục
 * vụ được đồng thời nhiều thread.
 */
public record ServerServices(AuthService auth, AccountService account, FolderService folder, MailService mail,
        DraftService draft, GroupService group) {

    public static ServerServices createDefault() {
        LoginThrottle loginThrottle = new LoginThrottle(ServerConfig.loginMaxFailures(),
                Duration.ofMinutes(ServerConfig.loginLockMinutes()));
        SlidingWindowLimiter registrations = new SlidingWindowLimiter(ServerConfig.registrationsPerHourPerIp(),
                Duration.ofHours(1));
        return new ServerServices(new AuthService(loginThrottle, registrations), new AccountService(loginThrottle),
                new FolderService(), new MailService(), new DraftService(), new GroupService());
    }
}
