package com.mailsystem.server.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mailsystem.common.model.Account;
import com.mailsystem.common.protocol.ResponseMessage;
import com.mailsystem.server.dao.AccountDAO;
import com.mailsystem.server.dao.FolderDAO;
import com.mailsystem.server.dao.GroupDAO;
import com.mailsystem.server.dao.MailDAO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kiểm thử nghiệp vụ MailService với DAO giả (không cần MySQL). */
class MailServiceTest {

    private static final int SENDER = 1;

    @TempDir
    Path attachments;

    /** Tạo DAO giả: chỉ các method có trong handlers được phép gọi. */
    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, Map<String, BiFunction<Object[], List<Object[]>, Object>> handlers,
            List<Object[]> calls) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            BiFunction<Object[], List<Object[]>, Object> handler = handlers.get(method.getName());
            if (handler == null) {
                throw new UnsupportedOperationException(method.getName());
            }
            calls.add(new Object[] {method.getName(), args});
            return handler.apply(args, calls);
        });
    }

    /**
     * Sau bước phân giải người nhận, Service mở kết nối MySQL thật để lưu thư.
     * Test chỉ quan tâm bước phân giải: không có DB thì nhận lỗi kết nối, có DB
     * thì DAO giả ném UnsupportedOperationException ở insertMail - cả hai đều bỏ qua.
     */
    private static void sendIgnoringStorage(MailService service, JsonObject payload) {
        try {
            service.sendMail("send", SENDER, payload, null);
        } catch (UnsupportedOperationException ignored) {
        }
    }

    @Test
    void groupMailIsNotDeliveredBackToTheSender() {
        List<Object[]> calls = new ArrayList<>();
        GroupDAO groups = fake(GroupDAO.class, Map.of(
                "findGroupIdByName", (args, c) -> Optional.of(7),
                "isMember", (args, c) -> true,
                "getMemberAccountIds", (args, c) -> List.of(SENDER, 2, 3)), calls);
        List<Integer> inboxLookups = new ArrayList<>();
        FolderDAO folders = fake(FolderDAO.class, Map.of(
                "findFolderIdByType", (args, c) -> {
                    if ("INBOX".equals(args[1])) {
                        inboxLookups.add((Integer) args[0]);
                    }
                    return Optional.of(100 + (Integer) args[0]);
                }), calls);
        MailService service = new MailService(fake(MailDAO.class, Map.of(), calls),
                fake(AccountDAO.class, Map.of(), calls), folders, groups, attachments);

        JsonObject payload = new JsonObject();
        JsonArray to = new JsonArray();
        to.add("Lop-LTM");
        payload.add("to", to);
        sendIgnoringStorage(service, payload);

        assertEquals(List.of(2, 3), inboxLookups, "chỉ các thành viên khác người gửi nhận thư");
    }

    @Test
    void groupContainingOnlyTheSenderIsRejected() {
        List<Object[]> calls = new ArrayList<>();
        GroupDAO groups = fake(GroupDAO.class, Map.of(
                "findGroupIdByName", (args, c) -> Optional.of(7),
                "isMember", (args, c) -> true,
                "getMemberAccountIds", (args, c) -> List.of(SENDER)), calls);
        MailService service = new MailService(fake(MailDAO.class, Map.of(), calls),
                fake(AccountDAO.class, Map.of(), calls), fake(FolderDAO.class, Map.of(), calls), groups,
                attachments);

        JsonObject payload = new JsonObject();
        JsonArray to = new JsonArray();
        to.add("Nhom-mot-minh");
        payload.add("to", to);
        ResponseMessage response = service.sendMail("r2", SENDER, payload, null);

        assertFalse(response.isOk());
        assertTrue(response.getMessage().contains("chưa có thành viên nào khác"), response.getMessage());
    }

    @Test
    void selfAddressedMailStillReachesSender() {
        List<Object[]> calls = new ArrayList<>();
        Account self = new Account();
        self.setAccountId(SENDER);
        self.setEmail("me@mail.local");
        List<Integer> inboxLookups = new ArrayList<>();
        FolderDAO folders = fake(FolderDAO.class, Map.of(
                "findFolderIdByType", (args, c) -> {
                    if ("INBOX".equals(args[1])) {
                        inboxLookups.add((Integer) args[0]);
                    }
                    return Optional.of(1);
                }), calls);
        MailService service = new MailService(fake(MailDAO.class, Map.of(), calls),
                fake(AccountDAO.class, Map.of("findByEmail", (args, c) -> Optional.of(self)), calls),
                folders, fake(GroupDAO.class, Map.of(), calls), attachments);

        JsonObject payload = new JsonObject();
        JsonArray to = new JsonArray();
        to.add("me@mail.local");
        payload.add("to", to);
        sendIgnoringStorage(service, payload);

        assertEquals(List.of(SENDER), inboxLookups, "gửi trực tiếp cho chính mình vẫn được phép");
    }

    @Test
    void batchOperationProcessesEveryOwnedEntryInOneRequest() {
        List<Object[]> calls = new ArrayList<>();
        Set<Integer> owned = Set.of(10, 11, 12);
        MailDAO mails = fake(MailDAO.class, Map.of(
                "moveMailToTrash", (args, c) -> owned.contains((Integer) args[0])), calls);
        MailService service = new MailService(mails, fake(AccountDAO.class, Map.of(), calls),
                fake(FolderDAO.class, Map.of(), calls), fake(GroupDAO.class, Map.of(), calls), attachments);

        JsonObject payload = new JsonObject();
        JsonArray ids = new JsonArray();
        for (int id : new int[] {10, 11, 11, 99, 12}) {
            ids.add(id);
        }
        payload.add("entryIds", ids);
        ResponseMessage response = service.deleteMail("b1", SENDER, payload);

        assertTrue(response.isOk());
        JsonObject data = response.getData().getAsJsonObject();
        assertEquals(3, data.get("processed").getAsInt());
        assertEquals(4, data.get("requested").getAsInt(), "id trùng lặp chỉ tính một lần");
    }

    @Test
    void batchRejectsInvalidPayloadsAndReportsNothingFound() {
        List<Object[]> calls = new ArrayList<>();
        MailDAO mails = fake(MailDAO.class, Map.of("markRead", (args, c) -> false), calls);
        MailService service = new MailService(mails, fake(AccountDAO.class, Map.of(), calls),
                fake(FolderDAO.class, Map.of(), calls), fake(GroupDAO.class, Map.of(), calls), attachments);

        JsonObject empty = new JsonObject();
        empty.add("entryIds", new JsonArray());
        assertFalse(service.markRead("e1", SENDER, empty).isOk());

        JsonObject negative = new JsonObject();
        JsonArray bad = new JsonArray();
        bad.add(-1);
        negative.add("entryIds", bad);
        assertFalse(service.markRead("e2", SENDER, negative).isOk());

        JsonObject tooMany = new JsonObject();
        JsonArray many = new JsonArray();
        for (int i = 1; i <= MailService.MAX_ENTRIES_PER_REQUEST + 1; i++) {
            many.add(i);
        }
        tooMany.add("entryIds", many);
        assertFalse(service.markRead("e3", SENDER, tooMany).isOk());
        assertTrue(calls.isEmpty(), "payload không hợp lệ bị chặn trước khi chạm DAO");

        JsonObject single = new JsonObject();
        single.addProperty("entryId", 5);
        ResponseMessage notOwned = service.markRead("e4", SENDER, single);
        assertFalse(notOwned.isOk());
        assertEquals("Không tìm thấy thư trong hộp thư của bạn.", notOwned.getMessage());
    }
}
