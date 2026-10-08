package com.mailsystem.server.session;

import com.google.gson.JsonObject;
import com.mailsystem.common.protocol.EventMessage;
import com.mailsystem.common.protocol.EventName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionManagerTest {

    private static final class RecordingSession implements ClientSession {
        final List<EventMessage> events = new ArrayList<>();

        @Override
        public void sendEvent(EventMessage event) {
            events.add(event);
        }
    }

    private static EventMessage newMail() {
        return new EventMessage(EventName.NEW_MAIL, new JsonObject());
    }

    @Test
    void pushesEventToEverySessionOfTheAccount() {
        SessionManager manager = new SessionManager();
        RecordingSession laptop = new RecordingSession();
        RecordingSession phone = new RecordingSession();
        RecordingSession otherUser = new RecordingSession();
        manager.register(1, laptop);
        manager.register(1, phone);
        manager.register(2, otherUser);

        manager.pushEvent(1, newMail());

        assertEquals(1, laptop.events.size());
        assertEquals(1, phone.events.size());
        assertEquals(0, otherUser.events.size());
    }

    @Test
    void disconnectingOneDeviceKeepsTheOtherOnline() {
        SessionManager manager = new SessionManager();
        RecordingSession laptop = new RecordingSession();
        RecordingSession phone = new RecordingSession();
        manager.register(1, laptop);
        manager.register(1, phone);

        manager.unregister(1, laptop);
        manager.pushEvent(1, newMail());

        assertEquals(0, laptop.events.size());
        assertEquals(1, phone.events.size());
        assertEquals(1, manager.sessionCount(1));

        manager.unregister(1, phone);
        assertEquals(0, manager.sessionCount(1));
        manager.unregister(null, phone); // không lỗi khi chưa đăng nhập
    }
}
