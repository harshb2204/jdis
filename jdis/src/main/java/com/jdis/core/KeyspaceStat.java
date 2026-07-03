package com.jdis.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks keyspace statistics for monitoring and the INFO command.
 *
 * where each map holds metrics like "keys" count.
 *
 * In this single-DB implementation, only index 0 is used.
 */
public class KeyspaceStat {

    /** Number of logical databases to track (matching Redis default of 4 for this impl). */
    private static final int NUM_DBS = 4;

    /** Array of metric maps, one per logical DB. */
    @SuppressWarnings("unchecked")
    private static final Map<String, Integer>[] stats = new Map[NUM_DBS];

    /**
     * Updates (or initializes) a metric for the given database number.
     *
     * @param dbNum  the logical database index (0-based)
     * @param metric the metric name (e.g., "keys")
     * @param value  the value to set
     */
    public static void updateDBStat(int dbNum, String metric, int value) {
        if (dbNum < 0 || dbNum >= NUM_DBS) return;
        if (stats[dbNum] == null) {
            stats[dbNum] = new HashMap<>();
        }
        stats[dbNum].put(metric, value);
    }

    /**
     * Increments a metric for the given database number.
     *
     * @param dbNum  the logical database index (0-based)
     * @param metric the metric name (e.g., "keys")
     */
    public static void incrementStat(int dbNum, String metric) {
        if (dbNum < 0 || dbNum >= NUM_DBS) return;
        if (stats[dbNum] == null) {
            stats[dbNum] = new HashMap<>();
        }
        stats[dbNum].merge(metric, 1, Integer::sum);
    }

    /**
     * Decrements a metric for the given database number.
     *
     * @param dbNum  the logical database index (0-based)
     * @param metric the metric name (e.g., "keys")
     */
    public static void decrementStat(int dbNum, String metric) {
        if (dbNum < 0 || dbNum >= NUM_DBS) return;
        if (stats[dbNum] == null) {
            stats[dbNum] = new HashMap<>();
        }
        stats[dbNum].merge(metric, -1, Integer::sum);
    }

    /**
     * Returns the metric value for the given database, or 0 if not set.
     */
    public static int getStat(int dbNum, String metric) {
        if (dbNum < 0 || dbNum >= NUM_DBS || stats[dbNum] == null) return 0;
        return stats[dbNum].getOrDefault(metric, 0);
    }

    /**
     * Returns the stats array for building the INFO response.
     */
    public static Map<String, Integer>[] getAllStats() {
        return stats;
    }

    /**
     * Returns the number of databases tracked.
     */
    public static int getNumDbs() {
        return NUM_DBS;
    }
}
