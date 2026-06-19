package com.jdis.server;

import com.jdis.config.Config;
import com.jdis.core.RESPDecoder;
import com.jdis.core.RESPEncoder;
import com.jdis.core.RedisCmd;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;

/**
 * Async TCP server using Java NIO Selector (backed by epoll on Linux/WSL).
 *
 * A single ServerSocketChannel is registered with a Selector for ACCEPT events.
 * When a new client connects, its SocketChannel is set non-blocking and
 * registered with the Selector for READ events.
 * The event loop calls selector.select() (epoll_wait equivalent), then
 * iterates over ready keys:
 *   - ACCEPT key  → accept the client, register for READ
 *   - READ  key   → read + decode RESP command, evaluate, respond
 *
 * Java NIO Selector uses epoll on Linux/WSL, enabling a single thread to
 * handle thousands of concurrent clients — the same model Redis uses.
 *
 * Kept for reference. The production server is NettyTCPServer.
 */
public class AsyncTCPServer {

    private static final int BUFFER_SIZE = 512;
    private static int concurrentClients = 0;

    /**
     * Reads up to BUFFER_SIZE bytes from the channel, decodes the RESP payload
     * and returns a RedisCmd. Returns null when the client has closed the
     * connection (channel read returns -1).
     */
    private static RedisCmd readCommand(SocketChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        int bytesRead = channel.read(buffer);

        if (bytesRead == -1) {
            return null;
        }

        if (bytesRead == 0) {
            return null;
        }

        byte[] data = Arrays.copyOf(buffer.array(), bytesRead);

        String[] tokens;

        // RESP array starts with '*'; otherwise treat as inline (telnet) command
        if (data.length > 0 && data[0] == '*') {
            tokens = RESPDecoder.decodeArrayString(data);
        } else {
            String inline = new String(data).trim();
            if (inline.isEmpty()) return null;
            tokens = inline.split("\\s+");
        }

        if (tokens.length == 0) {
            return null;
        }

        return new RedisCmd(
                tokens[0].toUpperCase(),
                Arrays.copyOfRange(tokens, 1, tokens.length));
    }

    /**
     * Writes a RESP error line back to the client channel.
     */
    private static void respondError(String message, SocketChannel channel) {
        try {
            channel.write(ByteBuffer.wrap(("-" + message + "\r\n").getBytes()));
        } catch (IOException writeErr) {
            System.err.println("error writing error response: " + writeErr.getMessage());
        }
    }

    /**
     * Evaluates the command and writes the RESP response to the channel.
     */
    private static void respond(RedisCmd cmd, SocketChannel channel) {
        System.out.println("command: " + cmd.getCmd());
        try {
            switch (cmd.getCmd()) {
                case "PING":
                    evalPING(cmd.getArgs(), channel);
                    break;
                default:
                    evalPING(cmd.getArgs(), channel);
                    break;
            }
        } catch (IOException e) {
            respondError(e.getMessage(), channel);
        }
    }

    private static void evalPING(String[] args, SocketChannel channel) throws IOException {
        if (args.length >= 2) {
            throw new IOException("ERR wrong number of arguments for 'ping' command");
        }
        byte[] response = args.length == 0
                ? RESPEncoder.encode("PONG", true)
                : RESPEncoder.encode(args[0], false);
        channel.write(ByteBuffer.wrap(response));
    }

    /**
     * Main event loop using epoll-backed NIO Selector.
     *
     *  1. Open a non-blocking ServerSocketChannel and bind it.
     *  2. Create a Selector (epoll instance on Linux).
     *  3. Register the server channel for OP_ACCEPT.
     *  4. Loop forever:
     *       a. selector.select()  — blocks until at least one channel is ready
     *       b. For each ready key:
     *            - OP_ACCEPT  → accept client, set non-blocking, register for OP_READ
     *            - OP_READ    → readCommand → respond; on error close and deregister
     */
    public static void run() throws IOException {
        System.out.println("starting an asynchronous TCP server on "
                + Config.HOST + ":" + Config.PORT);

        // 1. Create and configure the server socket channel (non-blocking)
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(Config.HOST, Config.PORT));

        // 2. Create the Selector — uses epoll on Linux/WSL
        Selector selector = Selector.open();

        // 3. Register server channel for ACCEPT events
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("ready to accept connections on "
                + Config.HOST + ":" + Config.PORT);

        // 4. Event loop
        while (true) {
            // Blocks until at least one channel is ready
            int readyCount = selector.select();
            if (readyCount == 0) continue;

            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> iter = selectedKeys.iterator();

            while (iter.hasNext()) {
                SelectionKey key = iter.next();
                iter.remove(); // must remove manually to avoid re-processing

                if (!key.isValid()) continue;

                if (key.isAcceptable()) {
                    // New client connecting — accept and register for reads
                    SocketChannel clientChannel = serverChannel.accept();
                    if (clientChannel == null) continue;

                    clientChannel.configureBlocking(false);

                    concurrentClients++;
                    System.out.println("client connected: "
                            + clientChannel.getRemoteAddress()
                            + ", concurrent clients: " + concurrentClients);

                    // Register the new client for READ events
                    clientChannel.register(selector, SelectionKey.OP_READ);

                } else if (key.isReadable()) {
                    // Existing client sent data — read, decode, evaluate, respond
                    SocketChannel clientChannel = (SocketChannel) key.channel();

                    RedisCmd cmd;
                    try {
                        cmd = readCommand(clientChannel);
                    } catch (IOException e) {
                        System.err.println("read error from "
                                + safeRemoteAddress(clientChannel) + ": " + e.getMessage());
                        key.cancel();
                        clientChannel.close();
                        concurrentClients--;
                        System.out.println("client disconnected (error), concurrent clients: "
                                + concurrentClients);
                        continue;
                    }

                    if (cmd == null) {
                        // Client closed connection (EOF)
                        key.cancel();
                        System.out.println("client disconnected: "
                                + safeRemoteAddress(clientChannel)
                                + ", concurrent clients: " + (--concurrentClients));
                        clientChannel.close();
                        continue;
                    }

                    respond(cmd, clientChannel);
                }
            }
        }
    }

    private static String safeRemoteAddress(SocketChannel ch) {
        try {
            return ch.getRemoteAddress().toString();
        } catch (IOException e) {
            return "<unknown>";
        }
    }
}
