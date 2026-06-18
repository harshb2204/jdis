package com.inmemdb.core;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Active expiry manager — periodically scans the store and deletes expired keys.
 *
 * Redis uses a two-pronged approach to expiry:
 *
 *   1. Lazy expiry  — checked on every read in {@link Store#get(String)}.
 *                     A key is deleted the first time it is accessed after expiry.
 *                     Cost: O(1) per access, but memory is not freed until the key
 *                     is touched again.
 *
 *   2. Active expiry — a periodic cron that proactively frees memory for keys
 *                      that are never accessed again after they expire.
 *                      This class implements that second prong.
 *
 * Algorithm:
 *   - Sample up to {@value #SAMPLE_SIZE} keys that have an expiry set.
 *   - Delete any of those that have already expired.
 *   - If ≥ 25 % of the sample was expired, repeat immediately (the store likely
 *     has many more expired keys — keep going until the ratio drops below 25 %).
 *
 * Sampling — true random selection:
 *   Each call to expireSample() snapshots the store's entry set into an array,
 *   then picks SAMPLE_SIZE entries at uniformly random indices using
 *   ThreadLocalRandom. This gives true random sampling (not hash-order iteration),
 *   matching the intent of Redis's dictGetRandomKeys().
 *
 *   Cost: O(n) for the toArray() snapshot, where n = total keys in the store.
 *   This is acceptable because the cron runs only once per second and the
 *   snapshot is a shallow array of references (no value copying).
 *
 *   The ideal O(1) approach (what Redis does) is to maintain a separate
 *   "expiring keys" list alongside the store, so sampling never needs to
 *   touch non-expiring keys at all.
 *
 * Threading model — faithful to Redis:
 *   This class contains NO threads of its own. {@link #deleteExpiredKeys()} is a
 *   plain method that is scheduled on the Netty event loop thread via
 *   {@code EventLoopGroup.scheduleAtFixedRate()} in {@code NettyTCPServer}.
 *
 *   Because the cron runs on the same thread as all command processing (the Netty
 *   I/O thread), it accesses the store with zero concurrency — exactly like Redis,
 *   where the expiry cron fires inside the same event loop iteration, before
 *   epoll_wait() is called.
 *
 *   Consequence: the store can remain a plain {@link java.util.HashMap} — no
 *   synchronization, no {@code ConcurrentHashMap}, no locks needed.
 */
public class ExpiryManager {

    private static final Logger log = Logger.getLogger(ExpiryManager.class.getName());

    /** How many keys with an expiry to inspect per sample round. */
    static final int SAMPLE_SIZE = 20;

    /**
     * If the expired fraction of a sample is at or above this threshold,
     * run another sample immediately instead of sleeping.
     */
    static final float EXPIRY_THRESHOLD = 0.25f;

    // -------------------------------------------------------------------------
    // Public API — called by NettyTCPServer on the event loop thread
    // -------------------------------------------------------------------------

    /**
     * Runs repeated sample-and-delete passes until the expired fraction of a
     * sample drops below {@value #EXPIRY_THRESHOLD}.
     *
     * <p>This method is designed to be scheduled on the Netty event loop thread:
     * <pre>
     *   group.scheduleAtFixedRate(
     *       ExpiryManager::deleteExpiredKeys, 1, 1, TimeUnit.SECONDS);
     * </pre>
     *
     * <p>Because it runs on the same thread as all command handlers, it accesses
     * the store without any synchronization — identical to how Redis does it.
     */
    public static void deleteExpiredKeys() {
        while (true) {
            float expiredFraction = expireSample();
            if (expiredFraction < EXPIRY_THRESHOLD) {
                break;
            }
            // More than 25 % of the sample was expired — likely many more remain.
            // Loop immediately rather than waiting for the next scheduled tick.
        }
        log.info("expiry pass complete, store size=" + Store.store.size());
    }

    // -------------------------------------------------------------------------
    // Sampling logic
    // -------------------------------------------------------------------------

    /**
     * Picks up to {@value #SAMPLE_SIZE} entries at <em>uniformly random</em>
     * positions, deletes those that have already expired, and returns the
     * fraction of the sample that was expired.
     *
     * <p>How randomness is achieved:
     * <ol>
     *   <li>Snapshot {@code store.entrySet()} into an {@code Object[]} — O(n),
     *       but a shallow copy (only references, no value duplication).</li>
     *   <li>Pick {@value #SAMPLE_SIZE} random indices into that array using
     *       {@link ThreadLocalRandom} — O(SAMPLE_SIZE), i.e. O(1).</li>
     * </ol>
     *
     * <p>Keys with no expiry ({@code expiresAt == -1}) are skipped and do not
     * count against the sample limit, so the sample stays representative of
     * keys that could actually expire.
     *
     * @return fraction of sampled keys that were expired (0.0 – 1.0)
     */
    @SuppressWarnings("unchecked")
    private static float expireSample() {
        // Snapshot the entry set into an array so we can index into it randomly.
        // toArray() is O(n) but only copies references — no value duplication.
        Object[] entries = Store.store.entrySet().toArray();
        if (entries.length == 0) return 0f;

        long now = System.currentTimeMillis();
        Set<String> toDelete = new HashSet<>();
        int sampledWithExpiry = 0;
        int expiredCount      = 0;

        // We want SAMPLE_SIZE *distinct* keys that have an expiry set.
        // Track visited indices to avoid sampling the same entry twice
        // (sampling without replacement), which would cause duplicate counts.
        Set<Integer> visitedIndices = new HashSet<>();
        int maxAttempts = entries.length * 4; // avoid infinite loop on sparse expiry sets
        int attempts = 0;

        while (sampledWithExpiry < SAMPLE_SIZE && attempts < maxAttempts
                && visitedIndices.size() < entries.length) {
            int idx = ThreadLocalRandom.current().nextInt(entries.length);
            attempts++;

            // Skip indices we have already visited — no duplicate sampling
            if (!visitedIndices.add(idx)) {
                continue;
            }

            Map.Entry<String, Store.Obj> entry =
                    (Map.Entry<String, Store.Obj>) entries[idx];

            Store.Obj obj = entry.getValue();

            if (obj.expiresAt == -1) {
                // Key has no expiry — skip, don't count against the sample limit.
                continue;
            }

            sampledWithExpiry++;

            if (obj.expiresAt <= now) {
                toDelete.add(entry.getKey());
                expiredCount++;
            }
        }

        for (String key : toDelete) {
            Store.store.remove(key);
        }

        if (!toDelete.isEmpty()) {
            log.info("expiry sample: deleted " + expiredCount + " / " + SAMPLE_SIZE + " keys");
        }

        // Denominator is always SAMPLE_SIZE so the threshold comparison is consistent
        // even when the store has fewer than SAMPLE_SIZE expiring keys.
        return (float) expiredCount / (float) SAMPLE_SIZE;
    }
}
