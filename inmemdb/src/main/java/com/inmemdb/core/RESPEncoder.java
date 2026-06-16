package com.inmemdb.core;

/**
 * Encodes values into RESP (Redis Serialization Protocol) format.
 *
 * Encodes values into RESP (Redis Serialization Protocol) format.
 * Supports Simple Strings, Bulk Strings, Integers, and the Nil sentinel.
 */
public class RESPEncoder {

    /** RESP-encoded nil bulk string — returned when a key does not exist. */
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
}
