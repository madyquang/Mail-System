package com.mailsystem.client.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.util.Duration;

/**
 * Hiệu ứng chuyển cảnh dùng chung. Chỉ đổi độ mờ (opacity) và dịch chuyển vẽ
 * (translate), KHÔNG đổi kích thước hay vị trí bố cục, nên không gây giật.
 * Màu hover / focus đổi dần bằng CSS transition (xem cuối app.css).
 */
public final class Motion {

    public static final Duration FAST = Duration.millis(140);
    public static final Duration NORMAL = Duration.millis(200);
    private static final String RUNNING = "mail.motion";

    private Motion() {
    }

    /** Hiện dần một node (huỷ hiệu ứng cũ còn đang chạy trên node đó). */
    public static void fadeIn(Node node, Duration duration) {
        stop(node);
        node.setOpacity(0);
        FadeTransition fade = new FadeTransition(duration, node);
        fade.setToValue(1);
        fade.setInterpolator(Interpolator.EASE_OUT);
        node.getProperties().put(RUNNING, fade);
        fade.setOnFinished(event -> node.getProperties().remove(RUNNING));
        fade.play();
    }

    public static void fadeIn(Node node) {
        fadeIn(node, FAST);
    }

    /** Hiện dần và trượt nhẹ từ trên xuống (popup, thông báo). */
    public static void dropIn(Node node) {
        stop(node);
        node.setOpacity(0);
        node.setTranslateY(-6);
        FadeTransition fade = new FadeTransition(FAST, node);
        fade.setToValue(1);
        TranslateTransition slide = new TranslateTransition(FAST, node);
        slide.setToY(0);
        ParallelTransition both = new ParallelTransition(fade, slide);
        both.setInterpolator(Interpolator.EASE_OUT);
        node.getProperties().put(RUNNING, both);
        both.setOnFinished(event -> node.getProperties().remove(RUNNING));
        both.play();
    }

    /** Thay nội dung của pane rồi cho nội dung mới hiện dần. */
    public static void swap(Pane parent, Node content) {
        parent.getChildren().setAll(content);
        fadeIn(content);
    }

    private static void stop(Node node) {
        Object running = node.getProperties().remove(RUNNING);
        if (running instanceof javafx.animation.Animation animation) {
            animation.stop();
        }
        node.setTranslateY(0);
    }
}
