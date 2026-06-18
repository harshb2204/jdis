package com.inmemdb.core;

/**
 * Encodes values into RESP (Redis Serialization Protocol) format.
 * Supports Simple Strings, Bulk Strings, Integers, and the Nil sentinel.
 */
public class RESPEncoder {

    /** RESP-encoded nil bulk string — returned when a key does not exist or the type is unknown. */
    public static final byte[] RESP_NIL = "$-1\r\n".getBytes();

    /**
     * Encodes a String value into RESP format.
     *
     * @param value    the string to encode
     * @param isSimple if true, encodes as a RESP Simple String (+value\r\n);
     *                 if false, encodes as a RESP Bulk String ($len\r\nvalue\r\n)
     * @return the RESP-encoded byte array
     */
    public static byte[] encode(String value, boolean isSimple) {
        if (isSimple) {
            return ("+" + value + "\r\n").getBytes();
        }
        return ("$" + value.length() + "\r\n" + value + "\r\n").getBytes();
    }

    /**
     * Encodes a long (int64) value as a RESP Integer (:<value>\r\n).
     *
     * @param value the integer to encode
     * @return the RESP-encoded byte array
     */
    public static byte[] encode(long value) {
        return (":" + value + "\r\n").getBytes();
    }

    /**
     * Encodes an arbitrary Object into RESP format.
     * Dispatches to the appropriate typed overload; returns RESP_NIL for unknown types.
     *
     * @param value    the value to encode
     * @param isSimple passed through to the String overload when value is a String
     * @return the RESP-encoded byte array
     */
    public static byte[] encode(Object value, boolean isSimple) {
        if (value instanceof String) {
            return encode((String) value, isSimple);
        }
        if (value instanceof Long) {
            return encode((long) (Long) value);
        }
        if (value instanceof Integer) {
            return encode((long) (Integer) value);
        }
        return RESP_NIL;
    }
}
