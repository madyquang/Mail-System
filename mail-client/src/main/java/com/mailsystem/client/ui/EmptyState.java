package com.mailsystem.client.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** Trạng thái rỗng: icon lớn + tiêu đề + mô tả (VD: "Không có thư nào"). */
public class EmptyState extends VBox {

    private final javafx.scene.layout.StackPane iconHolder = new javafx.scene.layout.StackPane();
    private final Label title = new Label();
    private final Label subtitle = new Label();

    public EmptyState() {
        super(10);
        getStyleClass().add("empty-state");
        setAlignment(Pos.CENTER);
        iconHolder.getStyleClass().add("empty-icon");
        iconHolder.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE);
        title.getStyleClass().add("empty-title");
        subtitle.getStyleClass().add("empty-subtitle");
        subtitle.setWrapText(true);
        subtitle.setMaxWidth(320);
        subtitle.setAlignment(Pos.CENTER);
        subtitle.setStyle("-fx-text-alignment: center;");
        getChildren().addAll(iconHolder, title, subtitle);
    }

    public EmptyState show(String icon, String titleText, String subtitleText) {
        iconHolder.getChildren().setAll(Icons.of(icon, 44));
        title.setText(titleText);
        subtitle.setText(subtitleText == null ? "" : subtitleText);
        subtitle.setVisible(subtitleText != null && !subtitleText.isBlank());
        subtitle.setManaged(subtitle.isVisible());
        return this;
    }
}
