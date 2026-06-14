package com.inmemdb.core;

/**
 * Encodes values into RESP (Redis Serialization Protocol) format.
 * Equivalent to the Encode function in resp.go.
 */
public class RESPEncoder {

    /**
     * Encodes a string value into RESP format.
     *
     * @param value    the string to encode
     * @param isSimple if true, encodes as a RESP Simple String (+value\r\n);
     *                 if false, encodes as a RESP Bulk String ($len\r\nvalue\r\n)
     * @return the RESP-encoded byte array
     */
    public static byte[] encode(String value, boolean isSimple) {
        if (isSimple) {
            return String.format("+%s\r\n", value).getBytes();
        }
        return String.format("$%d\r\n%s\r\n", value.length(), value).getBytes();
    }
}
