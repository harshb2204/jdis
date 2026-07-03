package com.jdis.core;

import java.util.HashMap;
import java.util.Map;

import com.jdis.config.Config;

/**
 * In-memory key-value store.
 *
 * Each value is wrapped in an {@link Obj} that carries the raw value and an
 * optional expiry timestamp (milliseconds since epoch, or -1 for "no expiry").
 *
 * get() performs lazy expiry: if the key has expired it is deleted on access
 * and null is returned, exactly like Redis does.
 */
public class Store {

    // The single global store — package-private so ExpiryManager can iterate it.
    static final Map<String, Obj> store = new HashMap<>();

    // -------------------------------------------------------------------------
    // Obj — the value wrapper
    // -------------------------------------------------------------------------

    /**
     * Wraps a stored value together with its type/encoding metadata and
     * an optional expiry timestamp.
     *
     * {@code expiresAt == -1} means "no expiry" (the key lives forever).
     * Otherwise {@code expiresAt} is an absolute Unix timestamp in milliseconds.
     *
     * {@code typeEncoding} packs both the Redis object type (upper 4 bits) and
     * the encoding (lower 4 bits) into a single byte. For example:
     *   - STRING + INT  = 0x01  (value is a parseable integer)
     *   - STRING + RAW  = 0x00  (value is a long string)
     *   - STRING + EMBSTR = 0x08 (value is a short string ≤ 44 bytes)
     *
     * {@code lastAccessedAt} stores the 24-bit LRU clock value (lower 24 bits
     * of Unix time in seconds) for the Approximated LRU eviction algorithm.
     * Redis uses 24 bits; we store it in a 32-bit int for simplicity since
     * Java doesn't support bit fields. This is used to compute idle time and
     * determine which keys are least recently used during eviction.
     */
    public static class Obj {
        public Object value;         // mutable so INCR can update it in-place
        public byte typeEncoding;    // type (upper 4 bits) | encoding (lower 4 bits)
        public long expiresAt;       // -1 = no expiry; mutable so EXPIRE can update it
        public int lastAccessedAt;   // 24-bit LRU clock for approximated LRU eviction

        Obj(Object value, long expiresAt, byte typeEncoding) {
            this.value = value;
            this.expiresAt = expiresAt;
            this.typeEncoding = typeEncoding;
            this.lastAccessedAt = LRUClock.getCurrentClock();
        }
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Creates a new {@link Obj} with explicit type and encoding.
     *
     * @param value      the value to store
     * @param durationMs lifetime in milliseconds; {@code <= 0} means no expiry
     * @param oType      the object type (e.g., OBJ_TYPE_STRING)
     * @param oEnc       the object encoding (e.g., OBJ_ENCODING_INT)
     * @return a new Obj with the expiry and type/encoding set appropriately
     */
    public static Obj newObj(Object value, long durationMs, byte oType, byte oEnc) {
        long expiresAt = -1;
        if (durationMs > 0) {
            expiresAt = System.currentTimeMillis() + durationMs;
        }
        return new Obj(value, expiresAt, (byte) (oType | oEnc));
    }

    /**
     * Creates a new {@link Obj}, automatically deducing type and encoding
     * from the string value.
     *
     * @param value      the value to store (must be a String)
     * @param durationMs lifetime in milliseconds; {@code <= 0} means no expiry
     * @return a new Obj with the expiry set appropriately
     */
    public static Obj newObj(Object value, long durationMs) {
        byte[] te = ObjTypeEncoding.deduceTypeEncoding(value.toString());
        return newObj(value, durationMs, te[0], te[1]);
    }

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    /** Stores {@code obj} under {@code key}, overwriting any existing entry. */
    public static void put(String key, Obj obj) {
        if (store.size() >= Config.KEYS_LIMIT) {
            EvictionManager.evict();
        }
        // Update LRU clock on every write — marks this key as "just accessed"
        obj.lastAccessedAt = LRUClock.getCurrentClock();
        store.put(key, obj);
        KeyspaceStat.incrementStat(0, "keys");
    }

    /**
     * Returns the {@link Obj} for {@code key}, or {@code null} if the key does
     * not exist or has already expired.
     *
     * Performs <em>lazy expiry</em>: an expired key is deleted from the store
     * on first access so memory is reclaimed even without the background cron.
     */
    public static Obj get(String key) {
        Obj obj = store.get(key);
        if (obj != null && obj.expiresAt != -1 && obj.expiresAt <= System.currentTimeMillis()) {
            del(key);
            return null;
        }
        // Update LRU clock on every read — marks this key as "just accessed"
        if (obj != null) {
            obj.lastAccessedAt = LRUClock.getCurrentClock();
        }
        return obj;
    }

    /**
     * Deletes the entry for {@code key}.
     *
     * @return {@code true} if the key existed and was removed, {@code false} otherwise
     */
    public static boolean del(String key) {
        if (store.remove(key) != null) {
            KeyspaceStat.decrementStat(0, "keys");
            return true;
        }
        return false;
    }
}
