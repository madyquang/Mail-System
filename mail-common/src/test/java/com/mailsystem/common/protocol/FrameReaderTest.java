package com.mailsystem.common.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameReaderTest {

    @Test
    void splitsStreamIntoFramesOnNewline() throws IOException {
        FrameReader reader = new FrameReader(new StringReader("{\"a\":1}\n{\"b\":2}\r\n\n"), 100);
        assertEquals("{\"a\":1}", reader.readFrame());
        assertEquals("{\"b\":2}", reader.readFrame(), "CRLF được chấp nhận");
        assertEquals("", reader.readFrame());
        assertNull(reader.readFrame(), "hết luồng -> null");
    }

    @Test
    void reassemblesFrameDeliveredInManySmallReads() throws IOException {
        // Mô phỏng TCP: một frame tới thành nhiều mảnh 1 ký tự.
        Reader trickle = new Reader() {
            private final String data = "{\"hello\":\"world\"}\nnext\n";
            private int index;

            @Override
            public int read(char[] buffer, int offset, int length) {
                if (index >= data.length()) {
                    return -1;
                }
                buffer[offset] = data.charAt(index++);
                return 1;
            }

            @Override
            public void close() {
            }
        };
        FrameReader reader = new FrameReader(trickle, 100);
        assertEquals("{\"hello\":\"world\"}", reader.readFrame());
        assertEquals("next", reader.readFrame());
        assertNull(reader.readFrame());
    }

    @Test
    void rejectsOversizedFrameAndStaysInSync() throws IOException {
        String huge = "x".repeat(50_000);
        FrameReader reader = new FrameReader(new StringReader(huge + "\nok\n"), 1_000);
        assertThrows(FrameTooLargeException.class, reader::readFrame);
        assertEquals("ok", reader.readFrame(), "frame sau frame quá lớn vẫn đọc đúng");
    }

    @Test
    void acceptsFrameExactlyAtLimit() throws IOException {
        String atLimit = "y".repeat(10);
        FrameReader reader = new FrameReader(new StringReader(atLimit + "\n" + atLimit + "z\n"), 10);
        assertEquals(atLimit, reader.readFrame());
        assertThrows(FrameTooLargeException.class, reader::readFrame);
    }

    @Test
    void dropsUnterminatedTailWhenStreamEnds() throws IOException {
        FrameReader reader = new FrameReader(new StringReader("done\npartial"), 100);
        assertEquals("done", reader.readFrame());
        assertNull(reader.readFrame(), "frame dang dở khi kết nối đóng bị bỏ qua");
    }

    @Test
    void gsonNeverEmitsRawNewlineInsideFrame() {
        String json = ProtocolUtil.toJson(java.util.Map.of("body", "dòng 1\ndòng 2"));
        assertEquals(-1, json.indexOf('\n'), json);
    }
}
