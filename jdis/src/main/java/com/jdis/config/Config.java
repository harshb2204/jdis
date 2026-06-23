package com.jdis.config;

public class Config {
    public static String HOST = "0.0.0.0";
    public static int PORT = 7379;
    public static int KEYS_LIMIT = 5;
    public static String EVICTION_STRATEGY = "simple-first";
    public static String AOF_FILE = "./jdis-master.aof";
}
