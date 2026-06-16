package com.inmemdb.core;

import java.util.HashMap;
import java.util.Map;

/**
 * In-memory key-value store.
 *
 * Each value is wrapped in an {@link Obj} that carries the raw value and an
 * optional expiry timestamp (milliseconds since epoch, or -1 for "no expiry").
 *
 * Get() does NOT do lazy expiry — the TTL check is done by the caller
 * (evalGET() and evalTTL() in Eval.java).
 */
public class Store {

    // The single global store — package-private so Eval can call Put/Get directly.
    private static final Map<String, Obj> store = new HashMap<>();

    // -------------------------------------------------------------------------
    // Obj — the value wrapper
    // -------------------------------------------------------------------------

    /**
     * Wraps a stored value together with its optional expiry timestamp.
     *
     * {@code expiresAt == -1} means "no expiry" (the key lives forever).
     * Otherwise {@code expiresAt} is an absolute Unix timestamp in milliseconds.
     */
    public static class Obj {
        public final Object value;
        public final long expiresAt; // -1 = no expiry

        Obj(Object value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Creates a new {@link Obj}.
     *
     * @param value      the value to store
     * @param durationMs lifetime in milliseconds; {@code <= 0} means no expiry
     * @return a new Obj with the expiry set appropriately
     */
    public static Obj newObj(Object value, long durationMs) {
        long expiresAt = -1;
        if (durationMs > 0) {
            expiresAt = System.currentTimeMillis() + durationMs;
        }
        return new Obj(value, expiresAt);
    }

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    /** Stores {@code obj} under {@code key}, overwriting any existing entry. */
    public static void put(String key, Obj obj) {
        store.put(key, obj);
    }

    /**
     * Returns the {@link Obj} for {@code key}, or {@code null} if the key does
     * not exist. No expiry check here — expiry is checked by the caller.
     */
    public static Obj get(String key) {
        return store.get(key);
    }
}
