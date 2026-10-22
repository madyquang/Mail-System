package com.mailsystem.server.service;

import com.mailsystem.common.model.MailSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadGroupingTest {

    private static MailSummary mail(int entryId, int threadId, boolean read) {
        MailSummary mail = new MailSummary();
        mail.setEntryId(entryId);
        mail.setThreadId(threadId);
        mail.setRead(read);
        return mail;
    }

    @Test
    void mailsOfOneConversationCollapseIntoNewestHead() {
        List<MailSummary> grouped = MailService.groupByThread(List.of(
                mail(30, 1, true), mail(29, 2, true), mail(20, 1, false), mail(10, 1, true)));

        assertEquals(2, grouped.size());
        MailSummary head = grouped.get(0);
        assertEquals(30, head.getEntryId(), "thư mới nhất đại diện hội thoại");
        assertEquals(3, head.getThreadCount());
        assertEquals(List.of(30, 20, 10), head.getThreadEntryIds());
        assertFalse(head.isRead(), "còn một thư chưa đọc thì cả hội thoại chưa đọc");
        assertTrue(grouped.get(1).isRead());
        assertEquals(List.of(29), grouped.get(1).getThreadEntryIds());
    }
}
