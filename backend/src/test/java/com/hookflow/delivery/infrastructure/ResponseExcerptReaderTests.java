package com.hookflow.delivery.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ResponseExcerptReaderTests {
    @Test
    void readsOnlyFirstFourKiBOfLongResponse() throws IOException {
        byte[] response = "a".repeat(10_000).getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream input = new ByteArrayInputStream(response);

        String excerpt = ResponseExcerptReader.read(input);

        assertEquals("a".repeat(4096), excerpt);
        assertEquals(response.length - 4096, input.available());
    }

    @Test
    void dropsIncompleteUtf8CharacterAtByteBoundary() throws IOException {
        byte[] response = ("a".repeat(4095) + "中" + "tail").getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream input = new ByteArrayInputStream(response);

        String excerpt = ResponseExcerptReader.read(input);

        assertEquals("a".repeat(4095), excerpt);
        assertEquals(4095, excerpt.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(response.length - 4096, input.available());
    }

    @Test
    void preservesCompleteMultibyteCharactersWithinLimit() throws IOException {
        String response = "中".repeat(1365) + "a";

        String excerpt = ResponseExcerptReader.read(
                new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)));

        assertEquals(response, excerpt);
        assertEquals(4096, excerpt.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void returnsEmptyExcerptForEmptyStream() throws IOException {
        assertEquals("", ResponseExcerptReader.read(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void propagatesReadFailure() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("broken stream");
            }
        };

        assertThrows(IOException.class, () -> ResponseExcerptReader.read(broken));
    }

    @Test
    void rejectsMissingStream() {
        assertThrows(NullPointerException.class, () -> ResponseExcerptReader.read(null));
    }
}
