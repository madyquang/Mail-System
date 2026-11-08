// Gói "service": các dịch vụ chạy nền của Client (thông báo, tải tệp).
package com.mailsystem.client.service;

// Navigator (lấy cửa sổ chính); Platform.runLater (về luồng JavaFX).
import com.mailsystem.client.app.Navigator;
import javafx.application.Platform;

// Các lớp AWT: khay hệ thống, biểu tượng khay, vẽ hình.
import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * Thông báo desktop cho thư mới khi cửa sổ KHÔNG được focus: dùng biểu tượng
 * khay hệ thống (System Tray) của hệ điều hành (Windows hiện thông báo góc
 * màn hình). Hệ điều hành không hỗ trợ khay thì bỏ qua; khi cửa sổ đang focus,
 * giao diện tự hiện toast trong ứng dụng.
 */
public final class DesktopNotifier {

    // Biểu tượng trên khay (null khi chưa tạo / đã gỡ).
    private static TrayIcon trayIcon;

    // Constructor private: lớp chỉ có hàm tĩnh.
    private DesktopNotifier() {
    }

    /** Hiện thông báo; trả false nếu không hỗ trợ (để nơi gọi dùng toast thay thế). */
    public static synchronized boolean notifyNewMail(String sender, String subject) {
        // Không có khay hệ thống: báo nơi gọi dùng toast.
        if (!ensureTrayIcon()) {
            return false;
        }
        // Hiện bong bóng thông báo: tiêu đề "Thư mới từ ...", nội dung là tiêu đề thư.
        trayIcon.displayMessage("Thư mới từ " + sender, subject == null || subject.isBlank()
                ? "(Không có tiêu đề)" : subject, TrayIcon.MessageType.INFO);
        return true;
    }

    /** Đổi chữ khi rê chuột lên biểu tượng khay theo số thư chưa đọc. */
    public static synchronized void updateTooltip(int unread) {
        if (trayIcon != null) {
            trayIcon.setToolTip(unread > 0 ? "Mail System - " + unread + " thư chưa đọc" : "Mail System");
        }
    }

    /** Gỡ biểu tượng khỏi khay (khi đăng xuất / thoát). */
    public static synchronized void shutdown() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
        }
    }

    // Tạo biểu tượng khay lần đầu cần dùng; trả false nếu không hỗ trợ.
    private static boolean ensureTrayIcon() {
        if (trayIcon != null) {
            return true;
        }
        try {
            // Máy không có màn hình hoặc hệ điều hành không hỗ trợ khay.
            if (java.awt.GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
                return false;
            }
            // Biểu tượng phong bì tự vẽ, tự co theo kích thước khay.
            trayIcon = new TrayIcon(envelope(), "Mail System");
            trayIcon.setImageAutoSize(true);
            // Bấm vào thông báo / biểu tượng: đưa cửa sổ lên trước.
            trayIcon.addActionListener(event -> Platform.runLater(() -> {
                Navigator.primaryStage().setIconified(false);
                Navigator.primaryStage().toFront();
                Navigator.primaryStage().requestFocus();
            }));
            // Thêm vào khay.
            SystemTray.getSystemTray().add(trayIcon);
            return true;
        // Lỗi bất kỳ: coi như không hỗ trợ.
        } catch (AWTException | UnsupportedOperationException | SecurityException error) {
            trayIcon = null;
            return false;
        }
    }

    // Vẽ biểu tượng phong bì 32x32: nền xanh bo góc, thân trắng, nắp hình chữ V.
    private static BufferedImage envelope() {
        // Ảnh có kênh trong suốt.
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        // Bật khử răng cưa.
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Nền xanh #0b57d0 bo góc.
        g.setColor(new Color(0x0b, 0x57, 0xd0));
        g.fillRoundRect(0, 0, 32, 32, 9, 9);
        // Thân phong bì màu trắng.
        g.setColor(Color.WHITE);
        g.fillRect(6, 9, 20, 14);
        // Nắp chữ V nét xanh dày 2px.
        g.setColor(new Color(0x0b, 0x57, 0xd0));
        g.setStroke(new BasicStroke(2f));
        g.drawPolyline(new int[] {6, 16, 26}, new int[] {9, 17, 9}, 3);
        // Giải phóng bộ vẽ.
        g.dispose();
        return image;
    }
}
