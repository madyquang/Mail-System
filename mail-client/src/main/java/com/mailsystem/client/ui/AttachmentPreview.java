package com.mailsystem.client.ui;

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
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

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

    private static final int MAX_PDF_PAGES = 30;
    private static final long MAX_TEXT_BYTES = 2L * 1024 * 1024;

    private AttachmentPreview() {
    }

    public static boolean supports(String filename) {
        String extension = extension(filename);
        return extension.equals("png") || extension.equals("jpg") || extension.equals("pdf") || extension.equals("txt");
    }

    /** Thư mục tạm chứa bản tải về để xem trước (xoá khi thoát ứng dụng). */
    public static Path cacheFile(int attachmentId, String filename) throws IOException {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "mailsystem-preview");
        Files.createDirectories(dir);
        String safe = filename == null ? "tep" : filename.replaceAll("[^A-Za-z0-9._-]", "_");
        Path file = dir.resolve(attachmentId + "-" + safe);
        file.toFile().deleteOnExit();
        return file;
    }

    /** Mở cửa sổ xem trước; onSave: lưu tệp về máy, onOpen: mở bằng ứng dụng mặc định. */
    public static void show(String filename, Path file, Runnable onSave, Runnable onOpen) {
        Stage stage = new Stage();
        stage.initOwner(Navigator.owner());
        stage.setTitle(filename + " – Xem trước");

        Label title = new Label(filename);
        title.getStyleClass().add("title-md");
        Button save = new Button("Lưu về máy", Icons.of(Icons.DOWNLOAD, 16));
        save.getStyleClass().add("secondary-button");
        save.setOnAction(event -> onSave.run());
        Button open = new Button("Mở bằng ứng dụng khác", Icons.of(Icons.FILE, 16));
        open.getStyleClass().add("secondary-button");
        open.setOnAction(event -> onOpen.run());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, Icons.of(Icons.forFile(filename), 22), title, spacer, save, open);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12, 16, 12, 16));
        header.getStyleClass().add("preview-header");

        ProgressIndicator loading = new ProgressIndicator();
        loading.setMaxSize(40, 40);
        StackPane body = new StackPane(loading);
        body.getStyleClass().add("preview-body");
        BorderPane layout = new BorderPane(body);
        layout.setTop(header);

        Scene scene = new Scene(layout, 900, 720);
        Navigator.styleScene(scene);
        stage.setScene(scene);
        stage.show();

        CompletableFuture.supplyAsync(() -> render(filename, file))
                .whenComplete((content, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        body.getChildren().setAll(new EmptyState().show(Icons.ERROR, "Không xem trước được tệp",
                                Fx.message(error)));
                    } else {
                        body.getChildren().setAll(content.get());
                    }
                }));
    }

    /** Đọc/giải mã trên thread nền; trả về hàm dựng Node (chạy trên UI thread). */
    private static java.util.function.Supplier<Node> render(String filename, Path file) {
        try {
            return switch (extension(filename)) {
                case "png", "jpg" -> {
                    Image image = new Image(file.toUri().toString());
                    if (image.isError()) {
                        throw new IOException("Ảnh bị hỏng hoặc không đúng định dạng.");
                    }
                    yield () -> scroll(imageView(image));
                }
                case "pdf" -> {
                    List<Image> pages = pdfPages(file);
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
                default -> {
                    if (Files.size(file) > MAX_TEXT_BYTES) {
                        throw new IOException("Tệp văn bản quá lớn để xem trước (> 2 MB).");
                    }
                    String text = Files.readString(file, StandardCharsets.UTF_8);
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
            throw new java.io.UncheckedIOException(error);
        }
    }

    private static List<Image> pdfPages(Path file) throws IOException {
        List<Image> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFRenderer renderer = new PDFRenderer(document);
            int count = Math.min(document.getNumberOfPages(), MAX_PDF_PAGES);
            for (int i = 0; i < count; i++) {
                pages.add(toFx(renderer.renderImageWithDPI(i, 110)));
            }
        }
        return pages;
    }

    /** BufferedImage (AWT) → Image (JavaFX) không cần module javafx-swing. */
    private static Image toFx(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = source.getRGB(0, 0, width, height, null, 0, width);
        WritableImage image = new WritableImage(width, height);
        image.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        return image;
    }

    private static ImageView imageView(Image image) {
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        view.setFitWidth(Math.min(image.getWidth(), 840));
        return view;
    }

    private static ScrollPane scroll(Node content) {
        StackPane centered = new StackPane(content);
        centered.setPadding(new Insets(12));
        ScrollPane pane = new ScrollPane(centered);
        pane.setFitToWidth(true);
        pane.getStyleClass().add("preview-scroll");
        return pane;
    }

    private static String extension(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }
}
