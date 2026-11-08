// Gói "service": logic nghiệp vụ phía Server (nằm giữa CommandRouter và DAO).
package com.mailsystem.server.service;

// ServerConfig: đọc cấu hình từ config/server.properties.
import com.mailsystem.server.config.ServerConfig;
// LoginThrottle: khoá tạm khi đăng nhập sai nhiều lần.
import com.mailsystem.server.security.LoginThrottle;
// SlidingWindowLimiter: giới hạn số lần một việc trong một khoảng thời gian trượt.
import com.mailsystem.server.security.SlidingWindowLimiter;

// Duration: một khoảng thời gian (phút, giờ...).
import java.time.Duration;

/**
 * Tập các service dùng chung cho MỌI kết nối. Service không giữ trạng thái
 * theo kết nối (trạng thái đó nằm ở ConnectionContext), nên một instance phục
 * vụ được đồng thời nhiều thread.
 * (record: lớp dữ liệu bất biến, Java tự sinh constructor và hàm truy cập auth(), mail()...)
 */
public record ServerServices(AuthService auth, AccountService account, FolderService folder, MailService mail,
        DraftService draft, GroupService group, ContactService contact) {

    /**
     * Tạo bộ service với cấu hình mặc định (đọc từ ServerConfig).
     *
     * @return đối tượng ServerServices dùng cho toàn Server
     */
    public static ServerServices createDefault() {
        // Bộ đếm đăng nhập sai lưu trong bảng login_attempt: khởi động lại Server không xoá khoá.
        // Tham số: số lần sai tối đa, thời gian khoá (phút), nơi lưu bộ đếm (JDBC).
        LoginThrottle loginThrottle = new LoginThrottle(ServerConfig.loginMaxFailures(),
                Duration.ofMinutes(ServerConfig.loginLockMinutes()), new com.mailsystem.server.dao.jdbc.JdbcThrottleStore());
        // Giới hạn số lần đăng ký tài khoản mỗi IP trong 1 giờ (chống tạo tài khoản hàng loạt).
        SlidingWindowLimiter registrations = new SlidingWindowLimiter(ServerConfig.registrationsPerHourPerIp(),
                Duration.ofHours(1));
        // Tạo từng service; AuthService và AccountService dùng chung một LoginThrottle.
        return new ServerServices(new AuthService(loginThrottle, registrations), new AccountService(loginThrottle),
                new FolderService(), new MailService(), new DraftService(), new GroupService(), new ContactService());
    }
}
