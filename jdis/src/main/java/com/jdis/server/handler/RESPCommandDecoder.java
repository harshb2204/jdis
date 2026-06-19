package com.jdis.server.handler;

import com.jdis.core.RedisCmd;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Netty pipeline stage 1: bytes → RedisCmd
 *
 * Extends ByteToMessageDecoder so Netty handles all TCP fragmentation
 * automatically — if a full command hasn't arrived yet, Netty buffers
 * the bytes and calls decode() again when more data arrives.
 *
 * Supports two input formats:
 *   1. RESP array  (*2\r\n$4\r\nPING\r\n...)  — used by redis-cli
 *   2. Inline text (PING\r\n)                  — used by telnet
 *
 * Note: ByteToMessageDecoder is stateful (it holds a cumulation buffer per
 * connection), so this class must NOT be annotated @Sharable — a new instance
 * is created for each connection by the ChannelInitializer in NettyTCPServer.
 */
public class RESPCommandDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // Need at least 1 byte to determine the format
        if (in.readableBytes() < 1) return;

        // Peek at the first byte without consuming it
        byte firstByte = in.getByte(in.readerIndex());

        if (firstByte == '*') {
            decodeRESPArray(in, out);
        } else {
            decodeInline(in, out);
        }
    }

    /**
     * Decodes a RESP array command.
     * Format: *<count>\r\n  followed by <count> bulk strings $<len>\r\n<data>\r\n
     */
    private void decodeRESPArray(ByteBuf in, List<Object> out) {
        // Mark the reader index so we can reset if we don't have a full frame yet
        in.markReaderIndex();

        // Read '*'
        in.readByte();

        // Read the array element count
        int count = readInteger(in);
        if (count < 0) {
            in.resetReaderIndex();
            return; // not enough data yet
        }

        List<String> tokens = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // Expect '$'
            if (in.readableBytes() < 1) {
                in.resetReaderIndex();
                return;
            }
            byte marker = in.readByte();
            if (marker != '$') {
                in.resetReaderIndex();
                return;
            }

            // Read bulk string length
            int len = readInteger(in);
            if (len < 0) {
                in.resetReaderIndex();
                return;
            }

            // Need len bytes + \r\n
            if (in.readableBytes() < len + 2) {
                in.resetReaderIndex();
                return;
            }

            String token = in.readCharSequence(len, StandardCharsets.UTF_8).toString();
            in.skipBytes(2); // skip \r\n
            tokens.add(token);
        }

        if (tokens.isEmpty()) return;

        String cmd   = tokens.get(0).toUpperCase();
        String[] args = tokens.subList(1, tokens.size()).toArray(new String[0]);
        out.add(new RedisCmd(cmd, args));
    }

    /**
     * Decodes an inline (plain text) command — e.g. from telnet.
     * Waits until a full \r\n or \n terminated line is available.
     */
    private void decodeInline(ByteBuf in, List<Object> out) {
        // Find the end of the line
        int lineEnd = findLineEnd(in);
        if (lineEnd < 0) return; // not a full line yet

        int lineLen = lineEnd - in.readerIndex();
        String line = in.readCharSequence(lineLen, StandardCharsets.UTF_8).toString().trim();

        // Skip \r\n or \n
        if (in.readableBytes() > 0 && in.getByte(in.readerIndex()) == '\r') in.readByte();
        if (in.readableBytes() > 0 && in.getByte(in.readerIndex()) == '\n') in.readByte();

        if (line.isEmpty()) return;

        String[] parts = line.split("\\s+");
        String cmd    = parts[0].toUpperCase();
        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);
        out.add(new RedisCmd(cmd, args));
    }

    /**
     * Reads an ASCII integer followed by \r\n from the buffer.
     * Returns -1 if there isn't enough data yet.
     */
    private int readInteger(ByteBuf in) {
        int startIndex = in.readerIndex();
        int value = 0;
        while (in.readableBytes() > 0) {
            byte b = in.readByte();
            if (b == '\r') {
                if (in.readableBytes() < 1) {
                    in.readerIndex(startIndex);
                    return -1;
                }
                in.readByte(); // consume \n
                return value;
            }
            if (b >= '0' && b <= '9') {
                value = value * 10 + (b - '0');
            }
        }
        in.readerIndex(startIndex);
        return -1; // not enough data
    }

    /**
     * Finds the index of the next \n in the buffer, or -1 if not found.
     */
    private int findLineEnd(ByteBuf in) {
        int i = in.readerIndex();
        int end = in.writerIndex();
        while (i < end) {
            if (in.getByte(i) == '\n') return i;
            i++;
        }
        return -1;
    }
}
