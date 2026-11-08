// Gói "ui": các thành phần giao diện tự viết (JavaFX).
package com.mailsystem.client.ui;

// Các lớp JavaFX: hiệu ứng chờ, luồng giao diện, toạ độ, danh sách, ô chữ, phím, chuột, bố cục, popup.
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

// Danh sách, CompletableFuture, Function.
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

    // Ô chữ được gắn; hàm lấy gợi ý (từ khoá -> danh sách); số ký tự tối thiểu; chế độ nhiều địa chỉ;
    // có tự chọn dòng đầu không; popup; danh sách trong popup; bộ đếm 180ms (debounce);
    // số thứ tự lần hỏi; đang thay chữ do chọn gợi ý (để không hỏi lại).
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

    /** Constructor private: dùng attach / attachTokens. */
    private AutoComplete(TextField field, Function<String, CompletableFuture<List<Suggestion>>> provider, int minChars,
            boolean tokenMode, boolean autoSelectFirst) {
        // Lưu tham số.
        this.field = field;
        this.provider = provider;
        this.minChars = minChars;
        this.tokenMode = tokenMode;
        this.autoSelectFirst = autoSelectFirst;

        // Danh sách: kiểu CSS, ô tự vẽ, không nhận focus (focus luôn ở ô chữ), cao 0 ban đầu.
        list.getStyleClass().add("suggest-list");
        list.setCellFactory(view -> new SuggestionCell());
        list.setFocusTraversable(false);
        list.setPrefHeight(0);
        // Bấm chuột trái vào danh sách: dùng gợi ý đang chọn.
        list.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                pickSelected();
            }
        });
        // Khung popup chứa danh sách, áp CSS theme.
        StackPane container = new StackPane(list);
        container.getStyleClass().add("suggest-popup");
        com.mailsystem.client.app.Navigator.styleParent(container);
        popup.getContent().add(container);
        // Popup tự ẩn khi bấm ra ngoài hoặc nhấn Esc.
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);

        // Hết 180ms không gõ: hỏi gợi ý.
        debounce.setOnFinished(event -> query());
        // Mỗi lần chữ đổi (do người gõ, không phải do chọn gợi ý): đếm lại từ đầu.
        field.textProperty().addListener((observable, old, value) -> {
            if (!picking && field.isFocused()) {
                debounce.playFromStart();
            }
        });
        // Vào ô: đếm để hiện gợi ý; rời ô: dừng đếm và ẩn popup sau 150ms.
        field.focusedProperty().addListener((observable, old, focused) -> {
            if (focused) {
                debounce.playFromStart(); // hiện gợi ý (VD: tìm kiếm gần đây) ngay khi vào ô
            } else {
                // Rời ô: dừng đếm.
                debounce.stop();
                // Chờ một nhịp để cú bấm chuột vào gợi ý kịp xử lý trước khi ẩn.
                PauseTransition hide = new PauseTransition(Duration.millis(150));
                hide.setOnFinished(e -> popup.hide());
                hide.play();
            }
        });
        // Bắt phím trước khi ô chữ xử lý.
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
        // Nội dung ô (null thành rỗng).
        String text = field.getText() == null ? "" : field.getText();
        // tokenMode: chỉ lấy phần sau dấu ',' hoặc ';' cuối cùng.
        if (tokenMode) {
            int comma = Math.max(text.lastIndexOf(','), text.lastIndexOf(';'));
            text = text.substring(comma + 1);
        }
        // Bỏ khoảng trắng hai đầu.
        return text.trim();
    }

    /** Các địa chỉ đã gõ xong (trước phần đang gõ), để không gợi ý trùng. */
    public List<String> completedTokens() {
        // Phần trước dấu phân cách cuối, tách thành danh sách địa chỉ.
        String text = field.getText() == null ? "" : field.getText();
        int comma = Math.max(text.lastIndexOf(','), text.lastIndexOf(';'));
        return comma < 0 ? List.of() : Formats.splitRecipients(text.substring(0, comma));
    }

    /** Thay phần đang gõ bằng value (tokenMode thêm ", " để gõ tiếp địa chỉ sau). */
    public void replaceCurrent(String value) {
        // Bật cờ picking để bộ nghe chữ không hỏi lại.
        picking = true;
        String text = field.getText() == null ? "" : field.getText();
        // tokenMode: giữ các địa chỉ trước, thay phần đang gõ, thêm ", ".
        if (tokenMode) {
            int comma = Math.max(text.lastIndexOf(','), text.lastIndexOf(';'));
            String before = comma < 0 ? "" : text.substring(0, comma + 1) + " ";
            field.setText(before.stripLeading() + value + ", ");
        } else {
            // Thay toàn bộ nội dung.
            field.setText(value);
        }
        // Con trỏ về cuối; tắt cờ; ẩn popup.
        field.positionCaret(field.getText().length());
        picking = false;
        popup.hide();
    }

    /** Dừng đếm và ẩn popup. */
    public void hide() {
        debounce.stop();
        popup.hide();
    }

    // Hỏi gợi ý cho phần đang gõ.
    private void query() {
        String query = currentQuery();
        // Chưa đủ ký tự (minChars = 0 thì luôn hỏi): ẩn popup.
        if (query.length() < minChars && !(minChars == 0)) {
            popup.hide();
            return;
        }
        // Tăng số thứ tự cho lần hỏi này.
        int seq = ++requestSeq;
        // Gọi hàm lấy gợi ý; kết quả xử lý trên luồng giao diện.
        provider.apply(query).whenComplete((items, error) -> Platform.runLater(() -> {
            if (seq != requestSeq || !field.isFocused()) {
                return; // đã có lần hỏi mới hơn, hoặc người dùng đã rời ô
            }
            // Lỗi hoặc không có gợi ý: ẩn popup.
            if (error != null || items == null || items.isEmpty()) {
                popup.hide();
                return;
            }
            // Hiện danh sách gợi ý.
            show(items);
        }));
    }

    private void show(List<Suggestion> items) {
        // Đặt các dòng gợi ý.
        list.getItems().setAll(items);
        // Chiều cao theo nội dung thật: dòng chỉ có tiêu đề thấp hơn dòng có thêm mô tả
        // (tính đều 47 px cho mọi dòng làm "Tìm kiếm gần đây" thừa một khoảng trống ở cuối).
        // Chiều cao: 8px lề + 34px (dòng không mô tả) hoặc 50px (có mô tả) + 24px mỗi tiêu đề nhóm.
        double height = 8;
        String previousSection = null;
        for (Suggestion item : items) {
            height += item.subtitle() == null || item.subtitle().isBlank() ? 34 : 50;
            if (item.section() != null && !item.section().isEmpty() && !item.section().equals(previousSection)) {
                height += 24;
            }
            previousSection = item.section();
        }
        // Cao tối đa 380px; rộng bằng ô chữ, tối thiểu 340px.
        list.setPrefHeight(Math.min(height, 380));
        double width = Math.max(field.getWidth(), 340);
        list.setPrefWidth(width);
        // Chọn sẵn dòng đầu hoặc không chọn dòng nào.
        if (autoSelectFirst) {
            list.getSelectionModel().select(0);
        } else {
            // Không chọn dòng nào.
            list.getSelectionModel().clearSelection();
        }
        // Toạ độ ô chữ trên màn hình; ô chưa hiện thì thôi.
        Bounds bounds = field.localToScreen(field.getBoundsInLocal());
        if (bounds == null) {
            return;
        }
        // Popup đang hiện: dời tới ngay dưới ô (cách 4px).
        if (popup.isShowing()) {
            popup.setX(bounds.getMinX());
            popup.setY(bounds.getMaxY() + 4);
        } else {
            // Chưa hiện: hiện dưới ô, kèm hiệu ứng thả xuống.
            popup.show(field, bounds.getMinX(), bounds.getMaxY() + 4);
            Motion.dropIn(popup.getContent().get(0));
        }
    }

    // Xử lý phím.
    private void onKey(KeyEvent event) {
        // Popup chưa hiện: phím mũi tên xuống thì hỏi ngay (không chờ).
        if (!popup.isShowing()) {
            if (event.getCode() == KeyCode.DOWN) {
                debounce.stop();
                query();
                event.consume();
            }
            return;
        }
        // Dòng đang chọn và số dòng.
        int index = list.getSelectionModel().getSelectedIndex();
        int size = list.getItems().size();
        // Popup đang hiện: xử lý theo phím.
        switch (event.getCode()) {
            // Xuống: chọn dòng dưới (không quá dòng cuối), cuộn tới đó.
            case DOWN -> {
                list.getSelectionModel().select(Math.min(size - 1, index + 1));
                list.scrollTo(list.getSelectionModel().getSelectedIndex());
                event.consume();
            }
            // Lên: ở dòng đầu thì bỏ chọn, không thì chọn dòng trên.
            case UP -> {
                if (index <= 0) {
                    list.getSelectionModel().clearSelection();
                } else {
                    // Chọn dòng trên và cuộn tới đó.
                    list.getSelectionModel().select(index - 1);
                    list.scrollTo(index - 1);
                }
                event.consume();
            }
            // Enter / Tab: có dòng chọn thì dùng gợi ý, không thì chỉ ẩn popup (Tab vẫn chuyển ô).
            case ENTER, TAB -> {
                if (index >= 0) {
                    pickSelected();
                    event.consume();
                } else {
                    // Không có dòng chọn: chỉ ẩn popup.
                    popup.hide();
                }
            }
            // Esc: ẩn popup.
            case ESCAPE -> {
                popup.hide();
                event.consume();
            }
            // Phím khác: không làm gì.
            default -> {
            }
        }
    }

    private void pickSelected() {
        // Dùng gợi ý đang chọn: ẩn popup rồi chạy việc làm onPick của nó.
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
            // Gọi lớp cha; không dùng chữ mặc định.
            super.updateItem(item, empty);
            setText(null);
            // Ô trống: không hiển thị gì.
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            // Tiêu đề: kiểu CSS, chữ dài thành "…", giãn hết chiều ngang.
            Label title = new Label(item.title());
            title.getStyleClass().add("suggest-title");
            title.setTextOverrun(OverrunStyle.ELLIPSIS);
            title.setMaxWidth(Double.MAX_VALUE);
            title.setPrefWidth(1);
            // Cột chữ: tiêu đề, thêm mô tả nếu có.
            VBox text = new VBox(1, title);
            if (item.subtitle() != null && !item.subtitle().isBlank()) {
                Label subtitle = new Label(item.subtitle());
                subtitle.getStyleClass().add("suggest-subtitle");
                subtitle.setTextOverrun(OverrunStyle.ELLIPSIS);
                subtitle.setMaxWidth(Double.MAX_VALUE);
                subtitle.setPrefWidth(1);
                text.getChildren().add(subtitle);
            }
            // Hàng: icon 18px + cột chữ (giãn), căn giữa theo chiều dọc.
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(10, Icons.of(item.icon(), 18), text);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("suggest-row");

            // Dòng đầu của nhóm: chỉ số 0 hoặc nhóm khác dòng trước.
            int index = getIndex();
            boolean firstOfSection = item.section() != null && !item.section().isEmpty()
                    && (index == 0 || !item.section().equals(getListView().getItems().get(index - 1).section()));
            // Có tiêu đề nhóm: hiện chữ in hoa phía trên hàng.
            if (firstOfSection) {
                Label header = new Label(item.section().toUpperCase());
                header.getStyleClass().add("suggest-section");
                header.setMaxWidth(Double.MAX_VALUE);
                // Tiêu đề nhóm chỉ để đọc: bấm vào không chọn / dùng gợi ý nằm dưới nó.
                header.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, javafx.event.Event::consume);
                header.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, javafx.event.Event::consume);
                setGraphic(new VBox(2, header, row));
            } else {
                // Không: chỉ hiện hàng.
                setGraphic(row);
            }
            // Bề rộng ưu tiên 0 để ô không làm danh sách rộng ra.
            setPrefWidth(0);
        }
    }
}
