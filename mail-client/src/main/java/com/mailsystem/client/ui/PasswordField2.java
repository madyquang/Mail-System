package com.mailsystem.client.ui;

import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;

/** Ô mật khẩu có nút ẩn/hiện (con mắt). Dùng được trực tiếp trong FXML. */
public class PasswordField2 extends StackPane {

    private final PasswordField hidden = new PasswordField();
    private final TextField visible = new TextField();
    private final Button toggle = new Button();

    public PasswordField2() {
        visible.textProperty().bindBidirectional(hidden.textProperty());
        visible.setVisible(false);
        visible.setManaged(false);
        visible.promptTextProperty().bind(hidden.promptTextProperty());
        hidden.setStyle("-fx-padding: 9px 42px 9px 12px;");
        visible.setStyle("-fx-padding: 9px 42px 9px 12px;");

        toggle.getStyleClass().add("icon-button");
        toggle.setFocusTraversable(false);
        toggle.setGraphic(Icons.of(Icons.EYE, 18));
        toggle.setTooltip(new Tooltip("Hiện mật khẩu"));
        toggle.setOnAction(event -> setRevealed(!visible.isVisible()));
        HBox overlay = new HBox(toggle);
        overlay.setAlignment(Pos.CENTER_RIGHT);
        overlay.setPickOnBounds(false);
        overlay.setStyle("-fx-padding: 0 4px 0 0;");
        HBox.setHgrow(toggle, Priority.NEVER);
        getChildren().addAll(hidden, visible, overlay);
    }

    private void setRevealed(boolean reveal) {
        boolean focused = hidden.isFocused() || visible.isFocused();
        visible.setVisible(reveal);
        visible.setManaged(reveal);
        hidden.setVisible(!reveal);
        hidden.setManaged(!reveal);
        toggle.setGraphic(Icons.of(reveal ? Icons.EYE_OFF : Icons.EYE, 18));
        toggle.getTooltip().setText(reveal ? "Ẩn mật khẩu" : "Hiện mật khẩu");
        if (focused) {
            (reveal ? visible : hidden).requestFocus();
            (reveal ? visible : hidden).end();
        }
    }

    public StringProperty textProperty() {
        return hidden.textProperty();
    }

    public String getText() {
        return hidden.getText() == null ? "" : hidden.getText();
    }

    public void setText(String text) {
        hidden.setText(text);
    }

    public void clear() {
        hidden.clear();
    }

    public String getPromptText() {
        return hidden.getPromptText();
    }

    public void setPromptText(String text) {
        hidden.setPromptText(text);
    }

    public void setOnAction(javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        hidden.setOnAction(handler);
        visible.setOnAction(handler);
    }

    public void requestInputFocus() {
        (visible.isVisible() ? visible : hidden).requestFocus();
    }

    public void setInvalid(boolean invalid) {
        for (TextField field : new TextField[] {hidden, visible}) {
            field.getStyleClass().remove("field-invalid");
            if (invalid) {
                field.getStyleClass().add("field-invalid");
            }
        }
    }
}
