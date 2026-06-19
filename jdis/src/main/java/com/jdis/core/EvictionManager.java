package com.jdis.core;

import com.jdis.config.Config;

/**
 * Handles eviction of keys from the store when the number of keys
 * exceeds the configured limit ({@link Config#KEYS_LIMIT}).
 *
 * Currently supports:
 * <ul>
 *   <li><b>simple-first</b> — evicts the first key found while iterating the store</li>
 * </ul>
 *
 * TODO: Make the eviction strategy fully configuration-driven.
 * TODO: Support multiple eviction strategies (LRU, LFU, random, etc.).
 */
public class EvictionManager {

    /**
     * Evicts the first key found while iterating the store map.
     * Since HashMap iteration order is not guaranteed, this effectively
     * removes an arbitrary key.
     *
     * TODO: Make it more efficient by doing thorough sampling.
     */
    private static void evictFirst() {
        for (String key : Store.store.keySet()) {
            Store.store.remove(key);
            return;
        }
    }

    /**
     * Triggers eviction based on the configured eviction strategy.
     * Called by {@link Store#put(String, Store.Obj)} when the store
     * has reached its capacity.
     */
    public static void evict() {
        switch (Config.EVICTION_STRATEGY) {
            case "simple-first":
                evictFirst();
                break;
            default:
                // Unknown strategy — fall back to simple-first
                evictFirst();
                break;
        }
    }
}
