package com.mailsystem.server.transfer;

import com.mailsystem.common.protocol.ProtocolConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadManagerTest {

    @TempDir
    Path temp;

    @Test
    void assemblesFileFromSequentialChunks() throws Exception {
        UploadManager uploads = new UploadManager(temp);
        byte[] first = {1, 2, 3};
        byte[] second = {4, 5};

        UploadManager.ChunkResult start = uploads.acceptChunk(null, "report.pdf", 5, 0, first);
        assertFalse(start.complete());
        UploadManager.ChunkResult end = uploads.acceptChunk(start.uploadId(), "report.pdf", 5, 3, second);
        assertTrue(end.complete());

        UploadManager.PendingUpload upload = uploads.take(start.uploadId());
        assertEquals("report.pdf", upload.filename());
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, Files.readAllBytes(upload.file()));
        assertEquals(0, uploads.pendingCount());
    }

    @Test
    void rejectsOutOfOrderOrDuplicateChunk() throws Exception {
        UploadManager uploads = new UploadManager(temp);
        String id = uploads.acceptChunk(null, "a.txt", 4, 0, new byte[] {1, 2}).uploadId();

        UploadManager.UploadException duplicate = assertThrows(UploadManager.UploadException.class,
                () -> uploads.acceptChunk(id, "a.txt", 4, 0, new byte[] {1, 2}));
        assertTrue(duplicate.getMessage().contains("offset 2"));
        assertThrows(UploadManager.UploadException.class,
                () -> uploads.acceptChunk(id, "a.txt", 4, 3, new byte[] {9}));
    }

    @Test
    void validatesExtensionSizeAndCompleteness() throws Exception {
        UploadManager uploads = new UploadManager(temp);
        assertThrows(UploadManager.UploadException.class,
                () -> uploads.acceptChunk(null, "virus.exe", 1, 0, new byte[] {1}));
        assertThrows(UploadManager.UploadException.class, () -> uploads.acceptChunk(null, "big.zip",
                ProtocolConstants.MAX_ATTACHMENT_TOTAL_BYTES + 1, 0, new byte[] {1}));
        assertThrows(UploadManager.UploadException.class,
                () -> uploads.acceptChunk(null, "a.txt", 1, 0, new byte[] {1, 2}),
                "dữ liệu dài hơn kích thước khai báo");

        String partial = uploads.acceptChunk(null, "a.txt", 10, 0, new byte[] {1}).uploadId();
        assertThrows(UploadManager.UploadException.class, () -> uploads.take(partial));
    }

    @Test
    void discardAllDeletesTempFilesAndCapsPendingUploads() throws IOException, UploadManager.UploadException {
        UploadManager uploads = new UploadManager(temp);
        for (int i = 0; i < ProtocolConstants.MAX_ATTACHMENTS_PER_MAIL + 2; i++) {
            uploads.acceptChunk(null, "f" + i + ".txt", 2, 0, new byte[] {1});
        }
        assertEquals(ProtocolConstants.MAX_ATTACHMENTS_PER_MAIL, uploads.pendingCount());
        try (var files = Files.list(temp)) {
            assertEquals(ProtocolConstants.MAX_ATTACHMENTS_PER_MAIL, files.count(), "tệp bị đẩy ra đã được xoá");
        }

        uploads.discardAll();
        assertEquals(0, uploads.pendingCount());
        try (var files = Files.list(temp)) {
            assertEquals(0, files.count());
        }
    }
}
