// Gói "ui": các thành phần giao diện tự viết (JavaFX).
package com.mailsystem.client.ui;

// Navigator (cửa sổ chính, áp CSS); các lớp JavaFX: luồng giao diện, bố cục, nút, nhãn, ảnh, cửa sổ.
import com.mailsystem.client.app.Navigator;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
// PDFBox: mở và vẽ trang PDF thành ảnh.
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

// Ảnh AWT, vào/ra tệp, bảng mã, danh sách, Locale, CompletableFuture.
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Xem trước tệp đính kèm ngay trong ứng dụng: ảnh (PNG/JPG), PDF (vẽ từng trang
 * bằng PDFBox) và văn bản (TXT). Tệp đã được tải về thư mục tạm qua giao thức
 * chia khối như khi tải xuống bình thường.
 */
public final class AttachmentPreview {

    // Chỉ vẽ tối đa 30 trang PDF; tệp văn bản tối đa 2 MB.
    private static final int MAX_PDF_PAGES = 30;
    private static final long MAX_TEXT_BYTES = 2L * 1024 * 1024;

    // Constructor private: lớp chỉ có hàm tĩnh.
    private AttachmentPreview() {
    }

    /** Có xem trước được tệp này không (theo đuôi png / jpg / pdf / txt). */
    public static boolean supports(String filename) {
        String extension = extension(filename);
        return extension.equals("png") || extension.equals("jpg") || extension.equals("pdf") || extension.equals("txt");
    }

    /** Thư mục tạm chứa bản tải về để xem trước (xoá khi thoát ứng dụng). */
    // Thư mục <tmp>/mailsystem-preview (tạo nếu chưa có).
    public static Path cacheFile(int attachmentId, String filename) throws IOException {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "mailsystem-preview");
        Files.createDirectories(dir);
        // Tên tệp an toàn: ký tự lạ thành '_' (null thì "tep").
        String safe = filename == null ? "tep" : filename.replaceAll("[^A-Za-z0-9._-]", "_");
        // Tên tệp tạm: <attachmentId>-<tên an toàn>.
        Path file = dir.resolve(attachmentId + "-" + safe);
        // Đánh dấu xoá tệp khi JVM thoát.
        file.toFile().deleteOnExit();
        return file;
    }

    /** Mở cửa sổ xem trước; onSave: lưu tệp về máy, onOpen: mở bằng ứng dụng mặc định. */
    public static void show(String filename, Path file, Runnable onSave, Runnable onOpen) {
        // Cửa sổ mới, thuộc cửa sổ chính, tiêu đề "<tên> – Xem trước".
        Stage stage = new Stage();
        stage.initOwner(Navigator.owner());
        stage.setTitle(filename + " – Xem trước");

        // Thanh trên: tên tệp, nút Lưu về máy, nút Mở bằng ứng dụng khác.
        Label title = new Label(filename);
        title.getStyleClass().add("title-md");
        Button save = new Button("Lưu về máy", Icons.of(Icons.DOWNLOAD, 16));
        save.getStyleClass().add("secondary-button");
        save.setOnAction(event -> onSave.run());
        Button open = new Button("Mở bằng ứng dụng khác", Icons.of(Icons.FILE, 16));
        open.getStyleClass().add("secondary-button");
        open.setOnAction(event -> onOpen.run());
        // Khoảng trống co giãn đẩy hai nút sang phải.
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        // Hàng: icon theo loại tệp, tên, khoảng trống, hai nút.
        HBox header = new HBox(10, Icons.of(Icons.forFile(filename), 22), title, spacer, save, open);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12, 16, 12, 16));
        header.getStyleClass().add("preview-header");

        // Vòng xoay "đang tải" 40x40 đặt giữa vùng nội dung.
        ProgressIndicator loading = new ProgressIndicator();
        loading.setMaxSize(40, 40);
        StackPane body = new StackPane(loading);
        body.getStyleClass().add("preview-body");
        // Bố cục: thanh trên + vùng nội dung.
        BorderPane layout = new BorderPane(body);
        layout.setTop(header);

        // Cảnh 900x720, áp CSS theme hiện tại, hiện cửa sổ.
        Scene scene = new Scene(layout, 900, 720);
        Navigator.styleScene(scene);
        stage.setScene(scene);
        stage.show();

        // Đọc / giải mã tệp trên thread nền (không làm đơ giao diện).
        CompletableFuture.supplyAsync(() -> render(filename, file))
                // Xong thì quay về luồng giao diện.
                .whenComplete((content, error) -> Platform.runLater(() -> {
                    // Lỗi: hiện khung trống với icon lỗi và lý do.
                    if (error != null) {
                        body.getChildren().setAll(new EmptyState().show(Icons.ERROR, "Không xem trước được tệp",
                                Fx.message(error)));
                    } else {
                        // Thành công: dựng Node trên luồng giao diện và hiển thị.
                        body.getChildren().setAll(content.get());
                    }
                }));
    }

    /** Đọc/giải mã trên thread nền; trả về hàm dựng Node (chạy trên UI thread). */
    private static java.util.function.Supplier<Node> render(String filename, Path file) {
        try {
            // Chọn cách đọc theo đuôi tệp.
            return switch (extension(filename)) {
                // Ảnh: nạp từ đường dẫn tệp; hỏng thì báo lỗi; trả hàm dựng khung cuộn chứa ảnh.
                case "png", "jpg" -> {
                    Image image = new Image(file.toUri().toString());
                    if (image.isError()) {
                        throw new IOException("Ảnh bị hỏng hoặc không đúng định dạng.");
                    }
                    yield () -> scroll(imageView(image));
                }
                // PDF: vẽ các trang thành ảnh ngay trên thread nền.
                case "pdf" -> {
                    List<Image> pages = pdfPages(file);
                    // Hàm dựng: cột dọc các trang (cách 16px, căn giữa), đặt trong khung cuộn.
                    yield () -> {
                        VBox column = new VBox(16);
                        column.setAlignment(Pos.TOP_CENTER);
                        column.setPadding(new Insets(16));
                        for (Image page : pages) {
                            ImageView view = imageView(page);
                            view.getStyleClass().add("preview-page");
                            column.getChildren().add(view);
                        }
                        return scroll(column);
                    };
                }
                // Còn lại (txt): kiểm tra kích thước, đọc UTF-8.
                default -> {
                    if (Files.size(file) > MAX_TEXT_BYTES) {
                        throw new IOException("Tệp văn bản quá lớn để xem trước (> 2 MB).");
                    }
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    // Hàm dựng: ô chữ chỉ đọc, tự xuống dòng.
                    yield () -> {
                        TextArea area = new TextArea(text);
                        area.setEditable(false);
                        area.setWrapText(true);
                        area.getStyleClass().add("reader-body");
                        return area;
                    };
                }
            };
        } catch (IOException error) {
            // Bọc IOException thành lỗi không kiểm tra (để đi qua CompletableFuture).
            throw new java.io.UncheckedIOException(error);
        }
    }

    private static List<Image> pdfPages(Path file) throws IOException {
        // Danh sách ảnh trang.
        List<Image> pages = new ArrayList<>();
        // Mở PDF (try-with-resources: tự đóng).
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            // Bộ vẽ trang; số trang vẽ tối đa MAX_PDF_PAGES.
            PDFRenderer renderer = new PDFRenderer(document);
            int count = Math.min(document.getNumberOfPages(), MAX_PDF_PAGES);
            // Vẽ từng trang ở 110 DPI rồi đổi sang ảnh JavaFX.
            for (int i = 0; i < count; i++) {
                pages.add(toFx(renderer.renderImageWithDPI(i, 110)));
            }
        }
        return pages;
    }

    /** BufferedImage (AWT) → Image (JavaFX) không cần module javafx-swing. */
    private static Image toFx(BufferedImage source) {
        // Kích thước và mảng điểm ảnh ARGB của ảnh nguồn.
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = source.getRGB(0, 0, width, height, null, 0, width);
        // Ảnh JavaFX cùng cỡ, ghi toàn bộ điểm ảnh vào.
        WritableImage image = new WritableImage(width, height);
        image.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        return image;
    }

    // Khung hiện ảnh: giữ tỉ lệ, rộng tối đa 840px.
    private static ImageView imageView(Image image) {
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        view.setFitWidth(Math.min(image.getWidth(), 840));
        return view;
    }

    // Khung cuộn: nội dung căn giữa, lề 12px, giãn theo bề ngang.
    private static ScrollPane scroll(Node content) {
        StackPane centered = new StackPane(content);
        centered.setPadding(new Insets(12));
        ScrollPane pane = new ScrollPane(centered);
        pane.setFitToWidth(true);
        pane.getStyleClass().add("preview-scroll");
        return pane;
    }

    // Đuôi tệp chữ thường (không có dấu chấm thì chuỗi rỗng).
    private static String extension(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }
}
