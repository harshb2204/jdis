package com.jdis.core;

import java.util.Iterator;

import com.jdis.config.Config;

/**
 * Handles eviction of keys from the store when the number of keys
 * exceeds the configured limit ({@link Config#KEYS_LIMIT}).
 *
 * Supports:
 * 
 *   simple-first — evicts the first key found while iterating the store
 *   allkeys-random — randomly removes keys to free up space based on
 *       {@link Config#EVICTION_RATIO
 * 
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
        Iterator<String> it = Store.store.keySet().iterator();
        if (it.hasNext()) {
            String key = it.next();
            it.remove();
            KeyspaceStat.decrementStat(0, "keys");
        }
    }

    /**
     * Randomly removes keys to make space for the new data added.
     * The number of keys removed will be sufficient to free up at least
     * {@link Config#EVICTION_RATIO} fraction of {@link Config#KEYS_LIMIT} keys.
     *
     * Iteration of Java HashMap can be considered pseudo-random because it
     * depends on the hash of the inserted key 
     */
    private static void evictAllkeysRandom() {
        long evictCount = (long) (Config.EVICTION_RATIO * Config.KEYS_LIMIT);
        Iterator<String> it = Store.store.keySet().iterator();
        while (it.hasNext() && evictCount > 0) {
            it.next();
            it.remove();
            KeyspaceStat.decrementStat(0, "keys");
            evictCount--;
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
            case "allkeys-random":
                evictAllkeysRandom();
                break;
            default:
                // Unknown strategy — fall back to simple-first
                evictFirst();
                break;
        }
    }
}
