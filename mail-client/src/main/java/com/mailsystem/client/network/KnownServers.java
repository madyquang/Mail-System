package com.mailsystem.client.network;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Danh sách máy chủ đã tin (giống ~/.ssh/known_hosts): "host:port" → dấu vân
 * tay SHA-256 của chứng chỉ. Lưu tại ~/.mailsystem/known_servers.properties.
 */
public class KnownServers {

    private final Path file;
    private final Properties entries = new Properties();

    public KnownServers(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                entries.load(reader);
            } catch (IOException error) {
                System.err.println("[KnownServers] Khong doc duoc " + file + ": " + error.getMessage());
            }
        }
    }

    public static KnownServers inUserHome() {
        return new KnownServers(Path.of(System.getProperty("user.home"), ".mailsystem", "known_servers.properties"));
    }

    public synchronized String fingerprint(String host, int port) {
        return entries.getProperty(key(host, port));
    }

    public synchronized void trust(String host, int port, String fingerprint) {
        entries.setProperty(key(host, port), fingerprint);
        save();
    }

    public synchronized void forget(String host, int port) {
        entries.remove(key(host, port));
        save();
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                entries.store(writer, "Mail System - may chu da tin (host:port = SHA-256 fingerprint)");
            }
        } catch (IOException error) {
            System.err.println("[KnownServers] Khong luu duoc " + file + ": " + error.getMessage());
        }
    }

    private static String key(String host, int port) {
        return host.toLowerCase(Locale.ROOT) + ":" + port;
    }
}
