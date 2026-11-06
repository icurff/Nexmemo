package com.icurff.command;

import com.icurff.storage.KvStore;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CommandHandlerTest {
    private final CommandHandler handler = new CommandHandler(new KvStore());

    private String execute(String... words) {
        List<byte[]> arguments = new ArrayList<>();
        for (String word : words) {
            arguments.add(word.getBytes(StandardCharsets.UTF_8));
        }
        return StandardCharsets.UTF_8.decode(handler.execute(arguments)).toString();
    }

    @Test
    void supportsCaseInsensitiveCommandsAndCaseSensitiveKeys() {
        assertEquals("+OK\r\n", execute("sEt", "Name", "An"));
        assertEquals("$2\r\nAn\r\n", execute("gEt", "Name"));
        assertEquals("$-1\r\n", execute("GET", "name"));
        assertEquals("+OK\r\n", execute("SET", "Name", "Binh"));
        assertEquals("$4\r\nBinh\r\n", execute("GET", "Name"));
        assertEquals(":1\r\n", execute("eXiStS", "Name"));
        assertEquals(":1\r\n", execute("dEl", "Name"));
        assertEquals("$-1\r\n", execute("GET", "Name"));
    }

    @Test
    void countsMissingAndRepeatedKeys() {
        execute("SET", "a", "1");
        execute("SET", "b", "2");
        assertEquals(":3\r\n", execute("EXISTS", "a", "missing", "b", "a"));
        assertEquals(":2\r\n", execute("DEL", "a", "missing", "a", "b"));
        assertEquals(":0\r\n", execute("EXISTS", "a", "b"));
        assertEquals(":0\r\n", execute("DEL", "a"));
    }

    @Test
    void rejectsWrongArityWithoutChangingStorage() {
        execute("SET", "a", "original");
        String[][] invalid = {
                {"SET"}, {"SET", "a"},
                {"GET"}, {"GET", "a", "b"}, {"DEL"}, {"EXISTS"}
        };
        for (String[] command : invalid) {
            String name = command[0].toLowerCase(java.util.Locale.ROOT);
            assertEquals("-ERR wrong number of arguments for '" + name + "' command\r\n",
                    execute(command));
            assertEquals("$8\r\noriginal\r\n", execute("GET", "a"));
        }
        assertEquals("-ERR syntax error\r\n", execute("SET", "a", "changed", "NX"));
        assertEquals("$8\r\noriginal\r\n", execute("GET", "a"));
    }

    @Test
    void returnsBinaryAndEmptyValuesAsBulkStrings() {
        byte[] key = {0, (byte) 255};
        byte[] value = {13, 10, 0, (byte) 128};
        handler.execute(List.of("SET".getBytes(StandardCharsets.US_ASCII), key, value));
        ByteBuffer response = handler.execute(List.of("GET".getBytes(StandardCharsets.US_ASCII), key));
        byte[] actual = new byte[response.remaining()];
        response.get(actual);
        assertArrayEquals(new byte[]{'$', '4', 13, 10, 13, 10, 0, (byte) 128, 13, 10}, actual);
        assertEquals("+OK\r\n", execute("SET", "", ""));
        assertEquals("$0\r\n\r\n", execute("GET", ""));
        assertEquals("$2\r\nAn\r\n", execute("ECHO", "An"));
        assertEquals("+PONG\r\n", execute("PING"));
    }
}
