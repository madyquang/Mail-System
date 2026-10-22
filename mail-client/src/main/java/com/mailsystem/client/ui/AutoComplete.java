package com.mailsystem.client.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.util.Duration;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Gợi ý khi gõ (autocomplete) cho một TextField, dùng lại cho ô người nhận, ô
 * tìm thư, ô người gửi, ô thêm thành viên.
 *
 * - Chờ người dùng ngừng gõ 180 ms rồi mới hỏi (debounce): gõ "nguyen" chỉ tốn
 *   1 request thay vì 6.
 * - Mỗi lần hỏi có số thứ tự; kết quả của lần hỏi cũ về trễ bị bỏ qua.
 * - Bàn phím: ↓/↑ chọn, Enter/Tab dùng gợi ý, Esc đóng. Chuột: bấm để chọn.
 * - tokenMode: chỉ gợi ý cho phần đang gõ sau dấu phẩy cuối (ô người nhận nhiều địa chỉ).
 */
public final class AutoComplete {

    /** Một dòng gợi ý; section khác dòng trước thì hiện tiêu đề nhóm phía trên. */
    public record Suggestion(String section, String icon, String title, String subtitle, Runnable onPick) {
    }

    private final TextField field;
    private final Function<String, CompletableFuture<List<Suggestion>>> provider;
    private final int minChars;
    private final boolean tokenMode;
    private final boolean autoSelectFirst;
    private final Popup popup = new Popup();
    private final ListView<Suggestion> list = new ListView<>();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(180));
    private int requestSeq;
    private boolean picking;

    private AutoComplete(TextField field, Function<String, CompletableFuture<List<Suggestion>>> provider, int minChars,
            boolean tokenMode, boolean autoSelectFirst) {
        this.field = field;
        this.provider = provider;
        this.minChars = minChars;
        this.tokenMode = tokenMode;
        this.autoSelectFirst = autoSelectFirst;

        list.getStyleClass().add("suggest-list");
        list.setCellFactory(view -> new SuggestionCell());
        list.setFocusTraversable(false);
        list.setPrefHeight(0);
        list.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                pickSelected();
            }
        });
        StackPane container = new StackPane(list);
        container.getStyleClass().add("suggest-popup");
        container.getStylesheets().add(com.mailsystem.client.app.Navigator.stylesheet());
        popup.getContent().add(container);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);

        debounce.setOnFinished(event -> query());
        field.textProperty().addListener((observable, old, value) -> {
            if (!picking && field.isFocused()) {
                debounce.playFromStart();
            }
        });
        field.focusedProperty().addListener((observable, old, focused) -> {
            if (focused) {
                debounce.playFromStart(); // hiện gợi ý (VD: tìm kiếm gần đây) ngay khi vào ô
            } else {
                debounce.stop();
                // Chờ một nhịp để cú bấm chuột vào gợi ý kịp xử lý trước khi ẩn.
                PauseTransition hide = new PauseTransition(Duration.millis(150));
                hide.setOnFinished(e -> popup.hide());
                hide.play();
            }
        });
        field.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
    }

    /** Gợi ý cho toàn bộ nội dung ô (ô tìm kiếm, ô một địa chỉ). */
    public static AutoComplete attach(TextField field, int minChars, boolean autoSelectFirst,
            Function<String, CompletableFuture<List<Suggestion>>> provider) {
        return new AutoComplete(field, provider, minChars, false, autoSelectFirst);
    }

    /** Gợi ý cho địa chỉ đang gõ sau dấu phẩy cuối (ô Tới/Cc/Bcc). */
    public static AutoComplete attachTokens(TextField field, Function<String, CompletableFuture<List<Suggestion>>> provider) {
        return new AutoComplete(field, provider, 1, true, true);
    }

    /** Phần đang gõ: cả ô, hoặc sau dấu phẩy cuối nếu tokenMode. */
    public String currentQuery() {
        String text = field.getText() == null ? "" : field.getText();
        if (tokenMode) {
            int comma = Math.max(text.lastIndexOf(','), text.lastIndexOf(';'));
            text = text.substring(comma + 1);
        }
        return text.trim();
    }

    /** Các địa chỉ đã gõ xong (trước phần đang gõ), để không gợi ý trùng. */
    public List<String> completedTokens() {
        String text = field.getText() == null ? "" : field.getText();
        int comma = Math.max(text.lastIndexOf(','), text.lastIndexOf(';'));
        return comma < 0 ? List.of() : Formats.splitRecipients(text.substring(0, comma));
    }

    /** Thay phần đang gõ bằng value (tokenMode thêm ", " để gõ tiếp địa chỉ sau). */
    public void replaceCurrent(String value) {
        picking = true;
        String text = field.getText() == null ? "" : field.getText();
        if (tokenMode) {
            int comma = Math.max(text.lastIndexOf(','), text.lastIndexOf(';'));
            String before = comma < 0 ? "" : text.substring(0, comma + 1) + " ";
            field.setText(before.stripLeading() + value + ", ");
        } else {
            field.setText(value);
        }
        field.positionCaret(field.getText().length());
        picking = false;
        popup.hide();
    }

    public void hide() {
        debounce.stop();
        popup.hide();
    }

    private void query() {
        String query = currentQuery();
        if (query.length() < minChars && !(minChars == 0)) {
            popup.hide();
            return;
        }
        int seq = ++requestSeq;
        provider.apply(query).whenComplete((items, error) -> Platform.runLater(() -> {
            if (seq != requestSeq || !field.isFocused()) {
                return; // đã có lần hỏi mới hơn, hoặc người dùng đã rời ô
            }
            if (error != null || items == null || items.isEmpty()) {
                popup.hide();
                return;
            }
            show(items);
        }));
    }

    private void show(List<Suggestion> items) {
        list.getItems().setAll(items);
        long headers = items.stream().map(Suggestion::section).distinct().filter(s -> s != null && !s.isEmpty()).count();
        list.setPrefHeight(Math.min(items.size() * 47 + headers * 22 + 8, 380));
        double width = Math.max(field.getWidth(), 340);
        list.setPrefWidth(width);
        if (autoSelectFirst) {
            list.getSelectionModel().select(0);
        } else {
            list.getSelectionModel().clearSelection();
        }
        Bounds bounds = field.localToScreen(field.getBoundsInLocal());
        if (bounds == null) {
            return;
        }
        if (popup.isShowing()) {
            popup.setX(bounds.getMinX());
            popup.setY(bounds.getMaxY() + 4);
        } else {
            popup.show(field, bounds.getMinX(), bounds.getMaxY() + 4);
        }
    }

    private void onKey(KeyEvent event) {
        if (!popup.isShowing()) {
            if (event.getCode() == KeyCode.DOWN) {
                debounce.stop();
                query();
                event.consume();
            }
            return;
        }
        int index = list.getSelectionModel().getSelectedIndex();
        int size = list.getItems().size();
        switch (event.getCode()) {
            case DOWN -> {
                list.getSelectionModel().select(Math.min(size - 1, index + 1));
                list.scrollTo(list.getSelectionModel().getSelectedIndex());
                event.consume();
            }
            case UP -> {
                if (index <= 0) {
                    list.getSelectionModel().clearSelection();
                } else {
                    list.getSelectionModel().select(index - 1);
                    list.scrollTo(index - 1);
                }
                event.consume();
            }
            case ENTER, TAB -> {
                if (index >= 0) {
                    pickSelected();
                    event.consume();
                } else {
                    popup.hide();
                }
            }
            case ESCAPE -> {
                popup.hide();
                event.consume();
            }
            default -> {
            }
        }
    }

    private void pickSelected() {
        Suggestion selected = list.getSelectionModel().getSelectedItem();
        if (selected != null) {
            popup.hide();
            selected.onPick().run();
        }
    }

    /** Ô gợi ý: tiêu đề nhóm (nếu là dòng đầu của nhóm) + icon + tiêu đề + mô tả. */
    private static final class SuggestionCell extends ListCell<Suggestion> {
        @Override
        protected void updateItem(Suggestion item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            Label title = new Label(item.title());
            title.getStyleClass().add("suggest-title");
            title.setTextOverrun(OverrunStyle.ELLIPSIS);
            title.setMaxWidth(Double.MAX_VALUE);
            title.setPrefWidth(1);
            VBox text = new VBox(1, title);
            if (item.subtitle() != null && !item.subtitle().isBlank()) {
                Label subtitle = new Label(item.subtitle());
                subtitle.getStyleClass().add("suggest-subtitle");
                subtitle.setTextOverrun(OverrunStyle.ELLIPSIS);
                subtitle.setMaxWidth(Double.MAX_VALUE);
                subtitle.setPrefWidth(1);
                text.getChildren().add(subtitle);
            }
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(10, Icons.of(item.icon(), 18), text);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("suggest-row");

            int index = getIndex();
            boolean firstOfSection = item.section() != null && !item.section().isEmpty()
                    && (index == 0 || !item.section().equals(getListView().getItems().get(index - 1).section()));
            if (firstOfSection) {
                Label header = new Label(item.section().toUpperCase());
                header.getStyleClass().add("suggest-section");
                setGraphic(new VBox(2, header, row));
            } else {
                setGraphic(row);
            }
            setPrefWidth(0);
        }
    }
}
