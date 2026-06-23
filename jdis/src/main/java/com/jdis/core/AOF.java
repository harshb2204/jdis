package com.jdis.core;

import com.jdis.config.Config;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.logging.Logger;

/**
 * AOF (Append-Only File) persistence.
 *
 * Rewrites the entire in-memory store into a single AOF file. Each key is
 * serialized as a RESP-encoded SET command so the file can be replayed
 * (like {@code redis-cli --pipe}) to restore the data.
 *
 * TODO: Support Expiration (persist EXPIRE commands alongside SET)
 * TODO: Support non-kv data structures (lists, sets, hashes, etc.)
 * TODO: Support sync write (fsync after every command for durability)
 */
public class AOF {

    private static final Logger logger = Logger.getLogger(AOF.class.getName());

    /**
     * Writes a single key-value pair as a RESP-encoded SET command to the file.
     *
     * The command is serialized as a RESP array:
     *   *3\r\n$3\r\nSET\r\n$<keyLen>\r\n<key>\r\n$<valLen>\r\n<value>\r\n
     */
    private static void dumpKey(FileOutputStream fp, String key, Store.Obj obj) throws IOException {
        String[] tokens = {"SET", key, obj.value.toString()};
        byte[] encoded = RESPEncoder.encodeStringArray(tokens);
        fp.write(encoded);
    }

    /**
     * Rewrites the entire AOF file from scratch.
     *
     * Opens (or creates) the configured AOF file, iterates every key in the
     * store, and writes the corresponding SET command in RESP format.
     *
     * This is the equivalent of Redis's BGREWRITEAOF — although the current
     * implementation is synchronous (runs in the calling thread).
     *
     * TODO: Fork to a new thread so the main event loop is not blocked.
     */
    public static void dumpAllAOF() {
        try (FileOutputStream fp = new FileOutputStream(Config.AOF_FILE, false)) {
            logger.info("rewriting AOF file at " + Config.AOF_FILE);
            for (Map.Entry<String, Store.Obj> entry : Store.store.entrySet()) {
                dumpKey(fp, entry.getKey(), entry.getValue());
            }
            logger.info("AOF file rewrite complete");
        } catch (IOException e) {
            logger.severe("error writing AOF file: " + e.getMessage());
        }
    }
}
