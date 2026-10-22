package com.mailsystem.server.security;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Nơi lưu bộ đếm đăng nhập sai (để khởi động lại Server không xoá khoá). */
public interface ThrottleStore {

    record Record(int failures, Instant lastFailure, Instant lockedUntil) {
    }

    Optional<Record> load(String key);

    void save(String key, Record record);

    void delete(String key);

    /** Chỉ trong bộ nhớ (test, hoặc khi không cần lưu bền). */
    static ThrottleStore inMemory() {
        Map<String, Record> map = new ConcurrentHashMap<>();
        return new ThrottleStore() {
            @Override
            public Optional<Record> load(String key) {
                return Optional.ofNullable(map.get(key));
            }

            @Override
            public void save(String key, Record record) {
                map.put(key, record);
            }

            @Override
            public void delete(String key) {
                map.remove(key);
            }
        };
    }
}
