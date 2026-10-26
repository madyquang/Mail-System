package com.mailsystem.server.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mailsystem.common.model.Contact;
import com.mailsystem.common.protocol.ResponseMessage;
import com.mailsystem.server.dao.ContactDAO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContactServiceTest {

    /** DAO giả ghi lại tham số để kiểm tra pattern và limit. */
    private static final class FakeContacts implements ContactDAO {
        final List<String> patterns = new ArrayList<>();
        final List<Integer> limits = new ArrayList<>();

        @Override
        public List<Contact> findPeople(int accountId, String contains, String prefix, int limit) {
            patterns.add(contains);
            patterns.add(prefix);
            limits.add(limit);
            List<Contact> people = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                people.add(Contact.person("p" + i + "@mail.local", "Người " + i, 5 - i));
            }
            return people;
        }

        @Override
        public List<Contact> findGroups(int accountId, String contains, int limit) {
            return List.of(Contact.group("Lop-LTM", "lop-ltm@group.local", 30));
        }
    }

    private static JsonObject query(String text) {
        JsonObject payload = new JsonObject();
        payload.addProperty("query", text);
        return payload;
    }

    @Test
    void groupsComeFirstThenPeopleWithinTheLimit() {
        FakeContacts dao = new FakeContacts();
        JsonObject payload = query("lo");
        payload.addProperty("limit", 4);
        ResponseMessage response = new ContactService(dao).suggest("1", 7, payload);

        JsonArray items = response.getData().getAsJsonArray();
        assertEquals(4, items.size());
        assertEquals("GROUP", items.get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals("Lop-LTM", items.get(0).getAsJsonObject().get("groupName").getAsString());
        assertEquals("PERSON", items.get(1).getAsJsonObject().get("kind").getAsString());
        assertEquals(List.of(3), dao.limits, "phần còn lại của limit dành cho người");
    }

    @Test
    void likeWildcardsInQueryAreEscaped() {
        FakeContacts dao = new FakeContacts();
        new ContactService(dao).suggest("1", 7, query("50%_a!"));
        assertEquals("%50!%!_a!!%", dao.patterns.get(0));
        assertEquals("50!%!_a!!%", dao.patterns.get(1), "pattern khớp từ đầu");
    }

    @Test
    void emptyOrHugeQueryReturnsNothingWithoutTouchingDatabase() {
        FakeContacts dao = new FakeContacts();
        ContactService service = new ContactService(dao);
        assertEquals(0, service.suggest("1", 7, query("   ")).getData().getAsJsonArray().size());
        assertEquals(0, service.suggest("2", 7, query("x".repeat(500))).getData().getAsJsonArray().size());
        assertTrue(dao.patterns.isEmpty());

        JsonObject peopleOnly = query("ng");
        peopleOnly.addProperty("includeGroups", false);
        peopleOnly.addProperty("limit", 999);
        JsonArray items = service.suggest("3", 7, peopleOnly).getData().getAsJsonArray();
        assertEquals(ContactService.MAX_LIMIT, items.size(), "limit bị chặn trên");
        assertEquals("PERSON", items.get(0).getAsJsonObject().get("kind").getAsString());
    }
}
