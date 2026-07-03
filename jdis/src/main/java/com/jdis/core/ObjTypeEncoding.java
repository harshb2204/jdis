package com.jdis.core;

/**
 * Redis Object type and encoding constants + utilities.
 *
 * In Redis, every value stored has a type (string, list, set, hash, etc.)
 * and an encoding (how it's represented in memory). These are packed into
 * a single byte:
 *   - Upper 4 bits = type   (e.g., STRING = 0x00)
 *   - Lower 4 bits = encoding (e.g., INT = 0x01, RAW = 0x00, EMBSTR = 0x08)
 *
 * This allows Redis to optimize storage — for example, a string value "42"
 * is stored with encoding INT, while "hello" uses EMBSTR (short embedded string)
 * or RAW (long string with a pointer).
 *
 * The INCR command can only operate on strings with INT encoding.
 */
public class ObjTypeEncoding {

    // -------------------------------------------------------------------------
    // Types (upper 4 bits)
    // -------------------------------------------------------------------------

    /** String type — the only type supported currently. */
    public static final byte OBJ_TYPE_STRING = (byte) (0 << 4);

    // -------------------------------------------------------------------------
    // Encodings (lower 4 bits)
    // -------------------------------------------------------------------------

    /** Raw string encoding — used for strings longer than 44 bytes. */
    public static final byte OBJ_ENCODING_RAW = 0;

    /** Integer encoding — the string is a valid 64-bit integer. */
    public static final byte OBJ_ENCODING_INT = 1;

    /** Embedded string encoding — short strings (≤ 44 bytes) stored inline. */
    public static final byte OBJ_ENCODING_EMBSTR = 8;

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /** Extracts the type from a typeEncoding byte (upper 4 bits). */
    public static byte getType(byte typeEncoding) {
        return (byte) (((typeEncoding >> 4) & 0x0F) << 4);
    }

    /** Extracts the encoding from a typeEncoding byte (lower 4 bits). */
    public static byte getEncoding(byte typeEncoding) {
        return (byte) (typeEncoding & 0x0F);
    }

    /**
     * Asserts that the object's type matches the expected type.
     *
     * @return null if OK, or an error message string if the type doesn't match
     */
    public static String assertType(byte typeEncoding, byte expectedType) {
        if (getType(typeEncoding) != expectedType) {
            return "WRONGTYPE Operation against a key holding the wrong kind of value";
        }
        return null;
    }

    /**
     * Asserts that the object's encoding matches the expected encoding.
     *
     * @return null if OK, or an error message string if the encoding doesn't match
     */
    public static String assertEncoding(byte typeEncoding, byte expectedEncoding) {
        if (getEncoding(typeEncoding) != expectedEncoding) {
            return "ERR value is not an integer or out of range";
        }
        return null;
    }

    /**
     * Deduces the type and encoding for a string value.
     *
     * Similar to Redis's tryObjectEncoding function:
     *   - If the value is a valid 64-bit integer → (STRING, INT)
     *   - If the value is ≤ 44 bytes → (STRING, EMBSTR)
     *   - Otherwise → (STRING, RAW)
     *
     * @param value the string value to analyze
     * @return a 2-element array: [type, encoding]
     */
    public static byte[] deduceTypeEncoding(String value) {
        byte oType = OBJ_TYPE_STRING;

        // Try to parse as integer
        try {
            Long.parseLong(value);
            return new byte[]{oType, OBJ_ENCODING_INT};
        } catch (NumberFormatException ignored) {
        }

        // Short string → embedded string encoding
        if (value.length() <= 44) {
            return new byte[]{oType, OBJ_ENCODING_EMBSTR};
        }

        // Long string → raw encoding
        return new byte[]{oType, OBJ_ENCODING_RAW};
    }
}
