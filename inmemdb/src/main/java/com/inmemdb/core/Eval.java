package com.inmemdb.core;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

import java.nio.charset.StandardCharsets;

/**
 * Evaluates Redis commands and writes RESP responses back to the client.
 *
 * This version adds SET (with optional EX), GET, and TTL commands.
 *
 * Performance optimisations:
 *  1. Static pre-computed ByteBuf for constant responses (PONG, OK, NIL, error
 *     strings) — zero allocation per response on the common path.
 *     Unpooled.unreleasableBuffer wraps a direct (off-heap) buffer so it is
 *     never GC'd and never released by Netty's reference counting.
 *
 *  2. ctx.alloc().buffer() for dynamic responses — uses Netty's pooled
 *     off-heap allocator instead of Unpooled, so the buffer is returned to
 *     the pool after the write completes rather than being GC'd.
 *
 *  3. No String.format — direct byte writes avoid intermediate String/char[]
 *     allocations on the hot path.
 */
public class Eval {

    // -------------------------------------------------------------------------
    // Pre-computed, reusable RESP responses
    // -------------------------------------------------------------------------

    private static final ByteBuf PONG_RESPONSE = staticBuf("+PONG\r\n");
    private static final ByteBuf OK_RESPONSE   = staticBuf("+OK\r\n");
    private static final ByteBuf NIL_RESPONSE  = staticBuf("$-1\r\n");
    private static final ByteBuf TTL_NO_KEY    = staticBuf(":-2\r\n");
    private static final ByteBuf TTL_NO_EXPIRY = staticBuf(":-1\r\n");

    private static final ByteBuf ERR_PING_ARGS = staticBuf(
            "-ERR wrong number of arguments for 'ping' command\r\n");
    private static final ByteBuf ERR_SET_ARGS  = staticBuf(
            "-ERR wrong number of arguments for 'set' command\r\n");
    private static final ByteBuf ERR_GET_ARGS  = staticBuf(
            "-ERR wrong number of arguments for 'get' command\r\n");
    private static final ByteBuf ERR_TTL_ARGS  = staticBuf(
            "-ERR wrong number of arguments for 'ttl' command\r\n");
    private static final ByteBuf ERR_SYNTAX    = staticBuf(
            "-ERR syntax error\r\n");
    private static final ByteBuf ERR_NOT_INT   = staticBuf(
            "-ERR value is not an integer or out of range\r\n");

    /** Allocates an unreleasable direct buffer pre-filled with the given string. */
    private static ByteBuf staticBuf(String s) {
        return Unpooled.unreleasableBuffer(
                Unpooled.directBuffer().writeBytes(s.getBytes(StandardCharsets.UTF_8)));
    }

    // -------------------------------------------------------------------------
    // PING
    // -------------------------------------------------------------------------

    private static void evalPING(String[] args, ChannelHandlerContext ctx) {
        if (args.length >= 2) {
            ctx.writeAndFlush(ERR_PING_ARGS.duplicate());
            return;
        }

        if (args.length == 0) {
            ctx.writeAndFlush(PONG_RESPONSE.duplicate());
        } else {
            // Dynamic bulk string: $<len>\r\n<arg>\r\n
            byte[] argBytes = args[0].getBytes(StandardCharsets.UTF_8);
            ByteBuf buf = ctx.alloc().buffer(argBytes.length + 16);
            buf.writeByte('$');
            writeAsciiLong(buf, argBytes.length);
            buf.writeByte('\r');
            buf.writeByte('\n');
            buf.writeBytes(argBytes);
            buf.writeByte('\r');
            buf.writeByte('\n');
            ctx.writeAndFlush(buf);
        }
    }

    // -------------------------------------------------------------------------
    // SET key value [EX seconds]
    // -------------------------------------------------------------------------

    /**
     * SET key value [EX seconds]
     *
     * Stores the key-value pair in the store. If EX is provided, the key
     * expires after the given number of seconds.
     */
    private static void evalSET(String[] args, ChannelHandlerContext ctx) {
        // Need at least key + value
        if (args.length <= 1) {
            ctx.writeAndFlush(ERR_SET_ARGS.duplicate());
            return;
        }

        String key   = args[0];
        String value = args[1];
        long exDurationMs = -1;

        // Parse optional [EX seconds] — args[2] onward
        for (int i = 2; i < args.length; i++) {
            switch (args[i].toUpperCase()) {
                case "EX":
                    i++;
                    if (i == args.length) {
                        ctx.writeAndFlush(ERR_SYNTAX.duplicate());
                        return;
                    }
                    try {
                        long exDurationSec = Long.parseLong(args[i]);
                        exDurationMs = exDurationSec * 1000;
                    } catch (NumberFormatException e) {
                        ctx.writeAndFlush(ERR_NOT_INT.duplicate());
                        return;
                    }
                    break;
                default:
                    ctx.writeAndFlush(ERR_SYNTAX.duplicate());
                    return;
            }
        }

        // Store the key-value pair
        Store.put(key, Store.newObj(value, exDurationMs));
        ctx.writeAndFlush(OK_RESPONSE.duplicate());
    }

    // -------------------------------------------------------------------------
    // GET key
    // -------------------------------------------------------------------------

    /**
     * GET key
     *
     * Returns the value for the key, or nil if the key does not exist or has
     * already expired.
     */
    private static void evalGET(String[] args, ChannelHandlerContext ctx) {
        if (args.length != 1) {
            ctx.writeAndFlush(ERR_GET_ARGS.duplicate());
            return;
        }

        String key = args[0];
        Store.Obj obj = Store.get(key);

        // Key does not exist → nil
        if (obj == null) {
            ctx.writeAndFlush(NIL_RESPONSE.duplicate());
            return;
        }

        // Key has expired → nil
        if (obj.expiresAt != -1 && obj.expiresAt <= System.currentTimeMillis()) {
            ctx.writeAndFlush(NIL_RESPONSE.duplicate());
            return;
        }

        // Return the value as a RESP bulk string
        byte[] valBytes = obj.value.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = ctx.alloc().buffer(valBytes.length + 16);
        buf.writeByte('$');
        writeAsciiLong(buf, valBytes.length);
        buf.writeByte('\r');
        buf.writeByte('\n');
        buf.writeBytes(valBytes);
        buf.writeByte('\r');
        buf.writeByte('\n');
        ctx.writeAndFlush(buf);
    }

    // -------------------------------------------------------------------------
    // TTL key
    // -------------------------------------------------------------------------

    /**
     * TTL key
     *
     * Returns the remaining time-to-live of a key in seconds:
     *   -2  → key does not exist (or has expired)
     *   -1  → key exists but has no expiry
     *   >=0 → seconds remaining
     */
    private static void evalTTL(String[] args, ChannelHandlerContext ctx) {
        if (args.length != 1) {
            ctx.writeAndFlush(ERR_TTL_ARGS.duplicate());
            return;
        }

        String key = args[0];
        Store.Obj obj = Store.get(key);

        // Key does not exist → -2
        if (obj == null) {
            ctx.writeAndFlush(TTL_NO_KEY.duplicate());
            return;
        }

        // Key exists but has no expiry → -1
        if (obj.expiresAt == -1) {
            ctx.writeAndFlush(TTL_NO_EXPIRY.duplicate());
            return;
        }

        // Compute remaining TTL in seconds
        long durationMs = obj.expiresAt - System.currentTimeMillis();

        // Key has already expired → -2
        if (durationMs < 0) {
            ctx.writeAndFlush(TTL_NO_KEY.duplicate());
            return;
        }

        // Return remaining seconds as a RESP integer
        long ttlSec = durationMs / 1000;
        ByteBuf buf = ctx.alloc().buffer(24);
        buf.writeByte(':');
        writeAsciiLong(buf, ttlSec);
        buf.writeByte('\r');
        buf.writeByte('\n');
        ctx.writeAndFlush(buf);
    }

    // -------------------------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------------------------

    /**
     * Evaluates the given RedisCmd and writes the appropriate RESP response.
     *
     * @param cmd the parsed Redis command
     * @param ctx the Netty ChannelHandlerContext to write the response to
     */
    public static void evalAndRespond(RedisCmd cmd, ChannelHandlerContext ctx) {
        switch (cmd.getCmd()) {
            case "PING":
                evalPING(cmd.getArgs(), ctx);
                break;
            case "SET":
                evalSET(cmd.getArgs(), ctx);
                break;
            case "GET":
                evalGET(cmd.getArgs(), ctx);
                break;
            case "TTL":
                evalTTL(cmd.getArgs(), ctx);
                break;
            default:
                evalPING(cmd.getArgs(), ctx);
                break;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Writes an ASCII decimal long directly into a ByteBuf.
     * Avoids Long.toString() + String.getBytes() allocations.
     */
    private static void writeAsciiLong(ByteBuf buf, long value) {
        if (value == 0) {
            buf.writeByte('0');
            return;
        }
        // Handle negative values (e.g. should not occur here, but be safe)
        if (value < 0) {
            buf.writeByte('-');
            value = -value;
        }
        // Write digits in reverse into a small stack array, then copy forward
        byte[] digits = new byte[20];
        int pos = 0;
        while (value > 0) {
            digits[pos++] = (byte) ('0' + (value % 10));
            value /= 10;
        }
        for (int i = pos - 1; i >= 0; i--) {
            buf.writeByte(digits[i]);
        }
    }
}
