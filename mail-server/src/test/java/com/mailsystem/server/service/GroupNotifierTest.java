package com.mailsystem.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Nội dung thư thông báo nhóm (phần không cần MySQL). */
class GroupNotifierTest {

    @Test
    void addedNoticeTellsWhoAddedAndHowToUseTheGroup() {
        assertEquals("Bạn đã được thêm vào nhóm \"Lop-LTM\"", GroupNotifier.subject(GroupNotifier.Action.ADDED, "Lop-LTM"));
        String body = GroupNotifier.body(GroupNotifier.Action.ADDED, "Lop-LTM", "lop-ltm@group.local", "Nguyễn Quang",
                "quang@mail.local", "Bình", 5);
        assertTrue(body.startsWith("Xin chào Bình,"), body);
        assertTrue(body.contains("Nguyễn Quang (quang@mail.local) đã thêm bạn vào nhóm thư \"Lop-LTM\""), body);
        assertTrue(body.contains("5 thành viên"), body);
        assertTrue(body.contains("Rời nhóm"), "chỉ cách rời nhóm nếu không muốn tham gia");
        assertTrue(body.contains("nhập lop-ltm@group.local vào ô Tới"), "thư nhắc địa chỉ nhóm để gửi");
    }

    @Test
    void removedAndDeletedNoticesExplainThatOldMailIsKept() {
        assertTrue(GroupNotifier.body(GroupNotifier.Action.REMOVED, "G", null, "A", "a@x.local", "B", 0)
                .contains("thư cũ của nhóm vẫn còn"));
        assertEquals("Nhóm \"G\" đã bị xoá", GroupNotifier.subject(GroupNotifier.Action.DELETED, "G"));
    }
}
