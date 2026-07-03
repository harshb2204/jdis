package com.jdis.core;

import java.util.Iterator;
import java.util.Map;

import com.jdis.config.Config;

/**
 * Handles eviction of keys from the store when the number of keys
 * exceeds the configured limit ({@link Config#KEYS_LIMIT}).
 *
 * Supports:
 *
 *   simple-first  — evicts the first key found while iterating the store
 *   allkeys-random — randomly removes keys to free up space based on
 *       {@link Config#EVICTION_RATIO}
 *   allkeys-lru   — approximated LRU: samples keys, populates an eviction pool
 *       sorted by idle time, and evicts the least recently used keys
 *
 */
public class EvictionManager {

    /** Number of keys to sample when populating the eviction pool. */
    private static final int SAMPLE_SIZE = 5;

    /**
     * Evicts the first key found while iterating the store map.
     * Since HashMap iteration order is not guaranteed, this effectively
     * removes an arbitrary key.
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
     * depends on the hash of the inserted key.
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

    // -------------------------------------------------------------------------
    // Approximated LRU Algorithm
    // -------------------------------------------------------------------------

    /**
     * Populates the eviction pool by sampling keys from the store.
     *
     * Samples up to {@value #SAMPLE_SIZE} keys from the store and pushes them
     * into the eviction pool. The pool maintains keys sorted by idle time
     * (highest idle time first), so only keys that are better eviction
     * candidates than existing pool entries will be retained.
     *
     * Since HashMap iteration order is non-deterministic (depends on internal
     * hashing), iterating and taking the first N keys is effectively random
     * sampling — similar to what Redis does with dictGetRandomKeys().
     */
    private static void populateEvictionPool() {
        EvictionPool ePool = EvictionPool.getInstance();
        int sampled = 0;
        for (Map.Entry<String, Store.Obj> entry : Store.store.entrySet()) {
            ePool.push(entry.getKey(), entry.getValue().lastAccessedAt);
            sampled++;
            if (sampled >= SAMPLE_SIZE) {
                break;
            }
        }
    }

    /**
     * Approximated LRU eviction strategy.
     *
     * Algorithm:
     * 1. Sample {@value #SAMPLE_SIZE} keys from the store and add them to the
     *    eviction pool (sorted by idle time, highest first).
     * 2. Pop the best candidates (highest idle time = least recently used) from
     *    the pool and delete them.
     * 3. Continue until we've evicted enough keys to meet the
     *    {@link Config#EVICTION_RATIO} target.
     *
     * The eviction pool has a fixed size of 16. Keys from samples are added
     * only when they are better candidates than existing pool entries. This
     * means over multiple eviction passes, the pool accumulates increasingly
     * better candidates, improving eviction accuracy.
     *
     * This is the same algorithm used by Redis (since Redis 3.0) — it provides
     * near-optimal LRU behavior without the memory overhead of a true LRU
     * implementation (which would require a doubly-linked list with prev/next
     * pointers per key).
     */
    static void evictAllkeysLRU() {
        populateEvictionPool();
        EvictionPool ePool = EvictionPool.getInstance();
        int evictCount = (int) (Config.EVICTION_RATIO * Config.KEYS_LIMIT);
        for (int i = 0; i < evictCount && ePool.size() > 0; i++) {
            EvictionPool.PoolItem item = ePool.pop();
            if (item == null) {
                return;
            }
            Store.del(item.key);
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
            case "allkeys-lru":
                evictAllkeysLRU();
                break;
            default:
                // Unknown strategy — fall back to simple-first
                evictFirst();
                break;
        }
    }
}
