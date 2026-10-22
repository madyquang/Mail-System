package com.mailsystem.server.monitor;

import com.mailsystem.common.protocol.Command;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Số liệu vận hành cho bảng giám sát. Được cập nhật từ rất nhiều thread cùng
 * lúc nên dùng LongAdder/AtomicLong (cộng không khoá) thay vì synchronized.
 *
 * Tốc độ request: vòng 60 ô, mỗi ô là một giây (ô = giây % 60). Ô cũ được xoá
 * khi giây đó quay lại, nên luôn có biểu đồ 60 giây gần nhất mà không cấp phát gì thêm.
 */
public final class ServerMetrics {

    private static final ServerMetrics INSTANCE = new ServerMetrics();
    private static final int WINDOW_SECONDS = 60;

    private final long startedAt = System.currentTimeMillis();
    private final LongAdder requests = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final LongAdder rateLimited = new LongAdder();
    private final LongAdder bytesIn = new LongAdder();
    private final LongAdder bytesOut = new LongAdder();
    private final LongAdder eventsPushed = new LongAdder();
    private final LongAdder connectionsAccepted = new LongAdder();
    private final LongAdder connectionsRejected = new LongAdder();
    private final Map<Command, LongAdder> perCommand = new EnumMap<>(Command.class);
    private final AtomicLongArray perSecond = new AtomicLongArray(WINDOW_SECONDS);
    private final AtomicLongArray secondStamp = new AtomicLongArray(WINDOW_SECONDS);
    private final AtomicLong totalLatencyMicros = new AtomicLong();

    private ServerMetrics() {
        for (Command command : Command.values()) {
            perCommand.put(command, new LongAdder());
        }
    }

    public static ServerMetrics get() {
        return INSTANCE;
    }

    public void recordRequest(Command command, long latencyNanos, boolean ok) {
        requests.increment();
        if (command != null) {
            perCommand.get(command).increment();
        }
        if (!ok) {
            errors.increment();
        }
        totalLatencyMicros.addAndGet(latencyNanos / 1_000);
        long second = System.currentTimeMillis() / 1000;
        int slot = (int) (second % WINDOW_SECONDS);
        if (secondStamp.get(slot) != second) {
            secondStamp.set(slot, second);
            perSecond.set(slot, 0);
        }
        perSecond.incrementAndGet(slot);
    }

    public void recordRateLimited() {
        rateLimited.increment();
    }

    public void addBytesIn(long bytes) {
        bytesIn.add(bytes);
    }

    public void addBytesOut(long bytes) {
        bytesOut.add(bytes);
    }

    public void recordEventPushed() {
        eventsPushed.increment();
    }

    public void recordConnection(boolean accepted) {
        (accepted ? connectionsAccepted : connectionsRejected).increment();
    }

    /** Ảnh chụp số liệu (để chuyển thành JSON). */
    public Map<String, Object> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        long total = requests.sum();
        data.put("uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000);
        data.put("requests", total);
        data.put("errors", errors.sum());
        data.put("rateLimited", rateLimited.sum());
        data.put("bytesIn", bytesIn.sum());
        data.put("bytesOut", bytesOut.sum());
        data.put("eventsPushed", eventsPushed.sum());
        data.put("connectionsAccepted", connectionsAccepted.sum());
        data.put("connectionsRejected", connectionsRejected.sum());
        data.put("avgLatencyMs", total == 0 ? 0 : Math.round(totalLatencyMicros.get() / (double) total) / 1000.0);

        long now = System.currentTimeMillis() / 1000;
        long[] series = new long[WINDOW_SECONDS];
        for (int i = 0; i < WINDOW_SECONDS; i++) {
            long second = now - (WINDOW_SECONDS - 1 - i);
            int slot = (int) (second % WINDOW_SECONDS);
            series[i] = secondStamp.get(slot) == second ? perSecond.get(slot) : 0;
        }
        data.put("requestsPerSecond", series);

        Map<String, Long> commands = new LinkedHashMap<>();
        perCommand.entrySet().stream()
                .filter(entry -> entry.getValue().sum() > 0)
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .forEach(entry -> commands.put(entry.getKey().name(), entry.getValue().sum()));
        data.put("commands", commands);
        return data;
    }
}
