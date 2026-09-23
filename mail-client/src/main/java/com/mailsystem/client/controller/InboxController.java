package com.mailsystem.client.controller;

import com.mailsystem.client.network.ServerConnection;
import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.EventName;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.ListView;

import com.google.gson.JsonObject;

/**
 * TODO (TV3): thiet ke bang/list hien thi thu (nguoi gui, tieu de, thoi gian,
 * in dam neu chua doc) - hien dang dung ListView<String> tam thoi de minh hoa
 * cach nhan du lieu, TV3 nen thay bang TableView voi custom cell.
 *
 * TODO (TV4): day la noi QUAN TRONG demo tinh nang dong bo real-time - phai
 * goi setEventListener() moi khi man hinh Inbox duoc mo, va HUY dang ky (hoac
 * kiem tra man hinh con dang mo khong) khi chuyen sang man hinh khac.
 */
public class InboxController {

    @FXML private ListView<String> mailListView;

    private int currentFolderId = 1; // TODO: lay dung folderId cua INBOX tu GET_FOLDERS luc dang nhap

    @FXML
    public void initialize() {
        loadMailList();
        registerEventListener();
    }

    private void loadMailList() {
        JsonObject payload = new JsonObject();
        payload.addProperty("folderId", currentFolderId);

        ServerConnection.getInstance()
                .sendRequest(Command.GET_MAIL_LIST, payload)
                .thenAccept(response -> Platform.runLater(() -> {
                    if (response.isOk()) {
                        // TODO: parse response.getData() (mang MailSummary) va
                        // render vao mailListView / TableView. Thu chua doc -> in dam.
                    }
                }));
    }

    private void registerEventListener() {
        ServerConnection.getInstance().setEventListener(event -> Platform.runLater(() -> {
            if (EventName.NEW_MAIL.name().equals(event.getEventName())) {
                // TODO: them 1 dong moi vao dau danh sach, khong can goi lai toan bo GET_MAIL_LIST
                loadMailList(); // cach don gian nhat de bat dau: goi lai toan bo danh sach
            } else if (EventName.MAIL_READ_UPDATED.name().equals(event.getEventName())
                    || EventName.MAIL_DELETED.name().equals(event.getEventName())) {
                loadMailList();
            }
        }));
    }

    @FXML
    public void handleCompose() {
        // TODO (TV3): chuyen sang compose.fxml (MailClientApp.switchScene)
    }

    @FXML
    public void handleOpenMail() {
        // TODO (TV4): lay mailId dang chon, goi Command.GET_MAIL_DETAIL,
        // mo man hinh chi tiet thu (mail_detail.fxml - TV3 tao them)
    }
}
