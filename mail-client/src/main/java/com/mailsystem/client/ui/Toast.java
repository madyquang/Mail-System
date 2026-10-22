package com.mailsystem.client.ui;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Thông báo ngắn (toast) ở cạnh dưới cửa sổ cho kết quả thao tác: thành công,
 * lỗi, thông tin. Tự biến mất; nhiều toast xếp chồng theo thứ tự.
 */
public final class Toast {

    public enum Kind { SUCCESS, ERROR, INFO }

    private static final String HOST_ID = "toast-host";

    private Toast() {
    }

    public static void success(StackPane root, String message) {
        show(root, message, Kind.SUCCESS);
    }

    public static void error(StackPane root, String message) {
        show(root, message, Kind.ERROR);
    }

    public static void info(StackPane root, String message) {
        show(root, message, Kind.INFO);
    }

    public static void show(StackPane root, String message, Kind kind) {
        if (root == null || message == null || message.isBlank()) {
            return;
        }
        VBox host = host(root);
        String icon = switch (kind) {
            case SUCCESS -> Icons.SUCCESS;
            case ERROR -> Icons.ERROR;
            case INFO -> Icons.INFO;
        };
        Label text = new Label(message);
        text.setWrapText(true);
        text.getStyleClass().add("toast-text");
        HBox toast = new HBox(10, Icons.of(icon, 18), text);
        toast.setAlignment(Pos.CENTER_LEFT);
        toast.getStyleClass().addAll("toast", "toast-" + kind.name().toLowerCase());
        toast.setMaxWidth(460);
        toast.setOpacity(0);
        host.getChildren().add(toast);
        if (host.getChildren().size() > 3) {
            host.getChildren().remove(0);
        }

        FadeTransition in = new FadeTransition(Duration.millis(160), toast);
        in.setToValue(1);
        PauseTransition stay = new PauseTransition(Duration.seconds(kind == Kind.ERROR ? 6 : 3.5));
        FadeTransition out = new FadeTransition(Duration.millis(260), toast);
        out.setToValue(0);
        SequentialTransition sequence = new SequentialTransition(in, stay, out);
        sequence.setOnFinished(event -> host.getChildren().remove(toast));
        toast.setOnMouseClicked(event -> host.getChildren().remove(toast));
        sequence.play();
    }

    /**
     * Toast có nút hành động (VD "Hoàn tác") đếm ngược seconds giây. Bấm nút thì
     * gọi action; hết giờ hoặc đóng thì không làm gì (thư cứ thế được gửi).
     */
    public static void withAction(StackPane root, String message, String actionText, int seconds, Runnable action) {
        if (root == null) {
            return;
        }
        VBox host = host(root);
        Label text = new Label(message);
        text.setWrapText(true);
        text.getStyleClass().add("toast-text");
        javafx.scene.control.Button button = new javafx.scene.control.Button(actionText + " (" + seconds + ")");
        button.getStyleClass().add("toast-action");
        HBox toast = new HBox(10, Icons.of(Icons.SENT, 18), text, button);
        toast.setAlignment(Pos.CENTER_LEFT);
        toast.getStyleClass().addAll("toast", "toast-info");
        toast.setMaxWidth(520);
        host.getChildren().add(toast);

        int[] left = {seconds};
        javafx.animation.Timeline countdown = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(Duration.seconds(1), event -> {
                    left[0]--;
                    button.setText(actionText + " (" + Math.max(0, left[0]) + ")");
                }));
        countdown.setCycleCount(seconds);
        countdown.setOnFinished(event -> host.getChildren().remove(toast));
        button.setOnAction(event -> {
            countdown.stop();
            host.getChildren().remove(toast);
            action.run();
        });
        countdown.play();
    }

    private static VBox host(StackPane root) {
        for (Node child : root.getChildren()) {
            if (HOST_ID.equals(child.getId()) && child instanceof VBox box) {
                box.toFront();
                return box;
            }
        }
        VBox host = new VBox(8);
        host.setId(HOST_ID);
        host.setAlignment(Pos.BOTTOM_CENTER);
        host.setPickOnBounds(false);
        host.setMouseTransparent(false);
        host.getStyleClass().add("toast-host");
        StackPane.setAlignment(host, Pos.BOTTOM_CENTER);
        host.setMaxSize(StackPane.USE_PREF_SIZE, StackPane.USE_PREF_SIZE);
        root.getChildren().add(host);
        return host;
    }
}
