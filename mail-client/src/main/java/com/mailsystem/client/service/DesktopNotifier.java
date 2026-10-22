package com.mailsystem.client.service;

import com.mailsystem.client.app.Navigator;
import javafx.application.Platform;

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

    private static TrayIcon trayIcon;

    private DesktopNotifier() {
    }

    /** Hiện thông báo; trả false nếu không hỗ trợ (để nơi gọi dùng toast thay thế). */
    public static synchronized boolean notifyNewMail(String sender, String subject) {
        if (!ensureTrayIcon()) {
            return false;
        }
        trayIcon.displayMessage("Thư mới từ " + sender, subject == null || subject.isBlank()
                ? "(Không có tiêu đề)" : subject, TrayIcon.MessageType.INFO);
        return true;
    }

    public static synchronized void updateTooltip(int unread) {
        if (trayIcon != null) {
            trayIcon.setToolTip(unread > 0 ? "Mail System - " + unread + " thư chưa đọc" : "Mail System");
        }
    }

    public static synchronized void shutdown() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
        }
    }

    private static boolean ensureTrayIcon() {
        if (trayIcon != null) {
            return true;
        }
        try {
            if (java.awt.GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
                return false;
            }
            trayIcon = new TrayIcon(envelope(), "Mail System");
            trayIcon.setImageAutoSize(true);
            // Bấm vào thông báo / biểu tượng: đưa cửa sổ lên trước.
            trayIcon.addActionListener(event -> Platform.runLater(() -> {
                Navigator.primaryStage().setIconified(false);
                Navigator.primaryStage().toFront();
                Navigator.primaryStage().requestFocus();
            }));
            SystemTray.getSystemTray().add(trayIcon);
            return true;
        } catch (AWTException | UnsupportedOperationException | SecurityException error) {
            trayIcon = null;
            return false;
        }
    }

    private static BufferedImage envelope() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x0b, 0x57, 0xd0));
        g.fillRoundRect(0, 0, 32, 32, 9, 9);
        g.setColor(Color.WHITE);
        g.fillRect(6, 9, 20, 14);
        g.setColor(new Color(0x0b, 0x57, 0xd0));
        g.setStroke(new BasicStroke(2f));
        g.drawPolyline(new int[] {6, 16, 26}, new int[] {9, 17, 9}, 3);
        g.dispose();
        return image;
    }
}
