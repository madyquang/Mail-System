package com.mailsystem.client.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceIdentityTest {

    @Test
    void deviceCertificateIsCreatedOnceAndReused(@TempDir Path dir) throws Exception {
        Path keystore = dir.resolve("device.p12");
        DeviceIdentity first = DeviceIdentity.loadOrCreate(keystore);
        assertNotNull(first.keyManagers());
        assertTrue(first.fingerprint().matches("([0-9A-F]{2}:){31}[0-9A-F]{2}"), first.fingerprint());

        DeviceIdentity again = DeviceIdentity.loadOrCreate(keystore);
        assertEquals(first.fingerprint(), again.fingerprint(), "mở lại ứng dụng vẫn là cùng một thiết bị");
    }
}
