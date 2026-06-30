package com.jdis.core;

/**
 * LRU Clock utilities for the Approximated LRU algorithm.
 *
 * Redis uses 24 bits to store the last access time of each object. Instead of
 * storing a full 64-bit timestamp, it stores only the lower 24 bits of the
 * current Unix time in seconds. This saves 8 bits (40 bits saved vs a full
 * timestamp) per object — a significant saving for an in-memory database
 * with millions of keys.
 *
 * The 24-bit clock covers a span of 2^24 seconds ≈ 194 days. After that, the
 * clock wraps around. The idle time calculation handles this wraparound
 * correctly.
 *
 * Key insight: we don't need the absolute time — we only need to compare
 * relative idle times between keys to determine which was least recently used.
 *
 * In our Java implementation, we use a full 32-bit int for the clock value
 * because Java doesn't support bit fields.
 * However, we still mask to 24 bits to stay faithful to the Redis algorithm.
 */
public class LRUClock {

    /**
     * 24-bit mask: 0x00FFFFFF = 16,777,215.
     * Covers a time span of ~194 days in seconds.
     */
    private static final int LRU_CLOCK_MASK = 0x00FFFFFF;

    /**
     * Returns the current LRU clock value.
     *
     * This is the lower 24 bits of the current Unix time in seconds.
     * The clock wraps around every ~194 days.
     *
     * @return current clock value (24-bit, stored in an int)
     */
    public static int getCurrentClock() {
        return (int) (System.currentTimeMillis() / 1000) & LRU_CLOCK_MASK;
    }

    /**
     * Computes the idle time of an object given its last accessed clock value.
     *
     * Handles the wraparound case correctly:
     * - If current clock >= lastAccessedAt: idle = current - lastAccessedAt
     * - If current clock < lastAccessedAt (wraparound): idle = (MAX -
     * lastAccessedAt) + current
     *
     * Example with a 5-bit clock (max = 31):
     * Key accessed at t=24, current time t=6 (wrapped around)
     * idle = (31 - 24) + 6 = 13 seconds
     *
     * @param lastAccessedAt the LRU clock value when the object was last accessed
     * @return the idle time in seconds
     */
    public static int getIdleTime(int lastAccessedAt) {
        int current = getCurrentClock();
        if (current >= lastAccessedAt) {
            return current - lastAccessedAt;
        }
        // Clock has wrapped around
        return (LRU_CLOCK_MASK - lastAccessedAt) + current;
    }
}
