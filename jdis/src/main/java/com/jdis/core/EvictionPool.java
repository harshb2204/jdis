package com.jdis.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Eviction Pool for the Approximated LRU algorithm.
 *
 * The pool maintains a sorted collection of candidate keys for eviction,
 * ordered by idle time (highest idle time first — those are the best eviction
 * candidates since they were least recently used).
 *
 * Pool size is fixed at {@value #MAX_POOL_SIZE}. Keys from random samples are
 * added only when they are "better" candidates than existing entries (i.e.,
 * they have a higher idle time). The pool is kept sorted by idle time so that
 * the best eviction candidate is always at the front.
 *
 * This mirrors Redis's eviction pool implementation where:
 * - Sample N keys from the dataset
 * - Add them to the pool only if they are better candidates
 * - During eviction, pop the best candidate (highest idle time) from the pool
 */
public class EvictionPool {

    static final int MAX_POOL_SIZE = 16;

    // -------------------------------------------------------------------------
    // PoolItem — represents a candidate for eviction
    // -------------------------------------------------------------------------

    static class PoolItem {
        final String key;
        final int lastAccessedAt;  // LRU clock value when last accessed

        PoolItem(String key, int lastAccessedAt) {
            this.key = key;
            this.lastAccessedAt = lastAccessedAt;
        }
    }

    // -------------------------------------------------------------------------
    // Pool state
    // -------------------------------------------------------------------------

    /** Sorted list of eviction candidates (highest idle time first). */
    private final List<PoolItem> pool;

    /** Fast lookup to prevent duplicate entries in the pool. */
    private final Map<String, PoolItem> keyset;

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    private static final EvictionPool INSTANCE = new EvictionPool();

    public static EvictionPool getInstance() {
        return INSTANCE;
    }

    private EvictionPool() {
        this.pool = new ArrayList<>();
        this.keyset = new HashMap<>();
    }

    // -------------------------------------------------------------------------
    // Operations
    // -------------------------------------------------------------------------

    /**
     * Attempts to push a key into the eviction pool.
     *
     * The key is added only if:
     *   - It is not already in the pool, AND
     *   - The pool is not full, OR
     *   - The key is a better candidate (higher idle time) than the worst
     *     entry currently in the pool.
     *
     * After insertion, the pool is re-sorted by idle time (descending).
     *
     * @param key            the key to consider for eviction
     * @param lastAccessedAt the LRU clock value when the key was last accessed
     */
    public void push(String key, int lastAccessedAt) {
        // Don't add duplicates
        if (keyset.containsKey(key)) {
            return;
        }

        PoolItem item = new PoolItem(key, lastAccessedAt);

        if (pool.size() < MAX_POOL_SIZE) {
            // Pool has room — add directly
            keyset.put(key, item);
            pool.add(item);
            // Re-sort: highest idle time first (best eviction candidates first)
            pool.sort((a, b) -> Integer.compare(
                    LRUClock.getIdleTime(b.lastAccessedAt),
                    LRUClock.getIdleTime(a.lastAccessedAt)));
        } else {
            // Pool is full — only add if this item has higher idle time than the
            // worst candidate (last item in sorted pool = lowest idle time)
            PoolItem worst = pool.get(pool.size() - 1);
            if (LRUClock.getIdleTime(lastAccessedAt) > LRUClock.getIdleTime(worst.lastAccessedAt)) {
                // Remove the worst candidate
                pool.remove(pool.size() - 1);
                keyset.remove(worst.key);
                // Add the new (better) candidate
                keyset.put(key, item);
                pool.add(item);
                // Re-sort
                pool.sort((a, b) -> Integer.compare(
                        LRUClock.getIdleTime(b.lastAccessedAt),
                        LRUClock.getIdleTime(a.lastAccessedAt)));
            }
        }
    }

    /**
     * Pops the best eviction candidate from the pool (highest idle time).
     *
     * @return the PoolItem with the highest idle time, or null if the pool is empty
     */
    public PoolItem pop() {
        if (pool.isEmpty()) {
            return null;
        }
        PoolItem item = pool.remove(0);
        keyset.remove(item.key);
        return item;
    }

    /**
     * Returns the current number of items in the pool.
     */
    public int size() {
        return pool.size();
    }
}
