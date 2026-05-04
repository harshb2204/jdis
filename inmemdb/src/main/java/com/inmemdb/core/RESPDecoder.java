package com.inmemdb.core;

import java.util.ArrayList;
import java.util.List;

public class RESPDecoder {

    public record DecodeResult(Object value, int delta) {
    }

    // value -> parsed result
    // delta -> bytes consumed

    // reads a length integer from data until a non-digit byte, returns [length,
    // delta]
    /*
     * $5\r\nhello\r\n
     * ^
     * this part → "5\r\n"
     * 
     */
    private static int[] readLength(byte[] data) {
        int pos = 0, length = 0;
        for (; pos < data.length; pos++) {
            byte b = data[pos];
            if (b < '0' || b > '9') {
                return new int[] { length, pos + 2 }; // +2 to skip \r\n
            }
            length = length * 10 + (b - '0');
        }
        return new int[] { 0, 0 };
    }

    // +OK\r\n
    private static DecodeResult readSimpleString(byte[] data) {
        int pos = 1; // skip '+'
        while (data[pos] != '\r')
            pos++;
        return new DecodeResult(new String(data, 1, pos - 1), pos + 2);
    }

    // -Error message\r\n
    private static DecodeResult readError(byte[] data) {
        int pos = 1; // skip '-'
        while (data[pos] != '\r')
            pos++;
        return new DecodeResult(new String(data, 1, pos - 1), pos + 2);
    }

    // :1000\r\n
    private static DecodeResult readInt64(byte[] data) {
        int pos = 1; // skip ':'
        long value = 0;
        while (data[pos] != '\r') {
            value = value * 10 + (data[pos] - '0');
            pos++;
        }
        return new DecodeResult(value, pos + 2);
    }

    // $5\r\nhello\r\n
    private static DecodeResult readBulkString(byte[] data) {
        int pos = 1; // skip '$'
        int[] lenResult = readLength(data, pos);
        int len = lenResult[0];
        int delta = lenResult[1];
        pos += delta;
        String str = new String(data, pos, len);
        return new DecodeResult(str, pos + len + 2);
    }

    // *2\r\n$5\r\nhello\r\n$5\r\nworld\r\n
    private static DecodeResult readArray(byte[] data) {
        int pos = 1; // skip '*'
        int[] lenResult = readLength(data, pos);
        int count = lenResult[0];
        int delta = lenResult[1];
        pos += delta;

        List<Object> elems = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] slice = new byte[data.length - pos];
            System.arraycopy(data, pos, slice, 0, slice.length);
            DecodeResult result = decodeOne(slice);
            elems.add(result.value());
            pos += result.delta();
        }
        return new DecodeResult(elems, pos);
    }

    // helper to call readLength starting at an offset
    private static int[] readLength(byte[] data, int offset) {
        byte[] array = new byte[data.length - offset];
        System.arraycopy(data, offset, array, 0, array.length);
        return readLength(array);
    }

    public static DecodeResult decodeOne(byte[] data) {
        if (data.length == 0)
            throw new IllegalArgumentException("no data");
        return switch ((char) data[0]) {
            case '+' -> readSimpleString(data);
            case '-' -> readError(data);
            case ':' -> readInt64(data);
            case '$' -> readBulkString(data);
            case '*' -> readArray(data);
            default -> new DecodeResult(null, 0);
        };
    }

    public static Object decode(byte[] data) {
        if (data.length == 0)
            throw new IllegalArgumentException("no data");
        return decodeOne(data).value();
    }
}
