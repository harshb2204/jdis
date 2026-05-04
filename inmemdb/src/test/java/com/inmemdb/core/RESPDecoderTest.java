package com.inmemdb.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RESPDecoderTest {

    @Test
    void testSimpleString() {
        assertEquals("OK", RESPDecoder.decode("+OK\r\n".getBytes()));
    }

    @Test
    void testError() {
        assertEquals("Error message", RESPDecoder.decode("-Error message\r\n".getBytes()));
    }

    @Test
    void testInt64() {
        assertEquals(0L,    RESPDecoder.decode(":0\r\n".getBytes()));
        assertEquals(1000L, RESPDecoder.decode(":1000\r\n".getBytes()));
    }

    @Test
    void testBulkString() {
        assertEquals("hello", RESPDecoder.decode("$5\r\nhello\r\n".getBytes()));
        assertEquals("",      RESPDecoder.decode("$0\r\n\r\n".getBytes()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testArray() {
        List<Object> empty = (List<Object>) RESPDecoder.decode("*0\r\n".getBytes());
        assertEquals(0, empty.size());

        List<Object> strings = (List<Object>) RESPDecoder.decode("*2\r\n$5\r\nhello\r\n$5\r\nworld\r\n".getBytes());
        assertEquals(List.of("hello", "world"), strings);

        List<Object> ints = (List<Object>) RESPDecoder.decode("*3\r\n:1\r\n:2\r\n:3\r\n".getBytes());
        assertEquals(List.of(1L, 2L, 3L), ints);

        List<Object> mixed = (List<Object>) RESPDecoder.decode("*5\r\n:1\r\n:2\r\n:3\r\n:4\r\n$5\r\nhello\r\n".getBytes());
        assertEquals(List.of(1L, 2L, 3L, 4L, "hello"), mixed);

        List<Object> nested = (List<Object>) RESPDecoder.decode("*2\r\n*3\r\n:1\r\n:2\r\n:3\r\n*2\r\n+Hello\r\n-World\r\n".getBytes());
        assertEquals(2, nested.size());
        assertEquals(List.of(1L, 2L, 3L), nested.get(0));
        assertEquals(List.of("Hello", "World"), nested.get(1));
    }
}
