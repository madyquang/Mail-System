package com.mailsystem.client.controller;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mailsystem.client.network.ServerConnection;
import com.mailsystem.common.protocol.Command;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

/**
 * TODO (TV3): thiet ke form voi cac truong To/CC/BCC/Subject/Body + nut dinh kem file.
 * TODO (TV4): xu ly doc file dinh kem tu FileChooser, encode Base64, kiem tra
 * so luong (<=5) va tong dung luong (<=25MB) O PHIA CLIENT TRUOC (UX tot hon),
 * server van phai kiem tra lai (khong tin tuong tuyet doi phia client).
 */
public class ComposeController {

    @FXML private TextField toField;
    @FXML private TextField ccField;
    @FXML private TextField bccField;
    @FXML private TextField subjectField;
    @FXML private TextArea bodyArea;

    @FXML
    public void handleSend() {
        JsonObject payload = new JsonObject();
        payload.add("to", toEmailArray(toField.getText()));
        payload.add("cc", toEmailArray(ccField.getText()));
        payload.add("bcc", toEmailArray(bccField.getText()));
        payload.addProperty("subject", subjectField.getText());
        payload.addProperty("body", bodyArea.getText());
        payload.add("attachments", new JsonArray()); // TODO: fill danh sach attachment da encode base64

        ServerConnection.getInstance()
                .sendRequest(Command.SEND_MAIL, payload)
                .thenAccept(response -> Platform.runLater(() -> {
                    if (response.isOk()) {
                        // TODO: hien thong bao thanh cong, quay lai Inbox
                    } else {
                        // TODO: hien response.getMessage() (VD: "Nguoi nhan khong ton tai")
                    }
                }));
    }

    /** Tach chuoi email cach nhau boi dau phay thanh JsonArray. */
    private JsonArray toEmailArray(String rawText) {
        JsonArray arr = new JsonArray();
        if (rawText == null || rawText.isBlank()) return arr;
        for (String email : rawText.split(",")) {
            arr.add(email.trim());
        }
        return arr;
    }
}
