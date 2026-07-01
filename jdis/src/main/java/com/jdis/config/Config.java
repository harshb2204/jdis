package com.jdis.config;

public class Config {
    public static String HOST = "0.0.0.0";
    public static int PORT = 7379;

    public static int KEYS_LIMIT = 100;

    /** Will evict EVICTION_RATIO of keys whenever eviction runs. */
    public static double EVICTION_RATIO = 0.40;

    public static String EVICTION_STRATEGY = "allkeys-lru";
    public static String AOF_FILE = "./jdis-master.aof";
}
