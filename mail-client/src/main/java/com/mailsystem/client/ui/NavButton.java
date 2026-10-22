package com.mailsystem.client.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/** Mục điều hướng ở sidebar: icon + nhãn + số đếm (VD: số thư chưa đọc). */
public class NavButton extends ToggleButton {

    private final Label title = new Label();
    private final Label badge = new Label();

    public NavButton(String icon, String text) {
        getStyleClass().setAll("nav-item");
        setMaxWidth(Double.MAX_VALUE);
        title.setText(text);
        title.setStyle("-fx-text-fill: inherit;");
        badge.getStyleClass().add("nav-badge");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox content = new HBox(16, Icons.of(icon, 20), title, spacer, badge);
        content.setAlignment(Pos.CENTER_LEFT);
        setGraphic(content);
        setText(null);
        content.prefWidthProperty().bind(widthProperty().subtract(32));
    }

    public void setCount(int count) {
        badge.setText(count > 0 ? (count > 999 ? "999+" : Integer.toString(count)) : "");
    }
}
