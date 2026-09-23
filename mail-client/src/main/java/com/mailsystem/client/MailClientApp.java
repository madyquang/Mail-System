package com.mailsystem.client;

import com.mailsystem.client.network.ServerConnection;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

/**
 * ENTRY POINT cua JavaFX Client App.
 * TODO (TV3): 
 *  - Hoan thien login.fxml, inbox.fxml, compose.fxml (dang la khung toi thieu).
 *  - Dung switchScene() de chuyen man hinh (VD: Login thanh cong -> chuyen sang Inbox).
 */
public class MailClientApp extends Application {

    private static Stage primaryStage;

    // TODO (TV4): doi lai host/port dung voi may chay Server that te
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 5000;

    @Override
    public void start(Stage stage) throws Exception {
        primaryStage = stage;
        primaryStage.setTitle("Mail System");

        // TODO (TV4): bat loi neu Server chua bat (hien Alert thay vi crash app)
        ServerConnection.getInstance().connect(SERVER_HOST, SERVER_PORT);

        switchScene("/fxml/login.fxml");
        primaryStage.show();
    }

    public static void switchScene(String fxmlPath) {
        try {
            Parent root = FXMLLoader.load(MailClientApp.class.getResource(fxmlPath));
            primaryStage.setScene(new Scene(root));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
