package com.inmemdb.server;

import com.inmemdb.config.Config;
import com.inmemdb.core.RESPDecoder;
import com.inmemdb.core.RESPEncoder;
import com.inmemdb.core.RedisCmd;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;

/**
 * Synchronous (single-client-at-a-time) TCP server.
 * Kept for reference and comparison with the async NIO and Netty servers.
 */
public class SyncTCPServer {

    private static final int BUFFER_SIZE = 512;

    /**
     * Reads raw bytes from the client channel, decodes the RESP payload into tokens,
     * and constructs a RedisCmd object.
     */
    private static RedisCmd readCommand(SocketChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        int bytesRead = channel.read(buffer);

        if (bytesRead == -1) {
            return null; // client closed connection
        }

        byte[] data = Arrays.copyOf(buffer.array(), bytesRead);

        String[] tokens;

        // RESP array starts with '*'; otherwise treat as inline (telnet) command
        if (data.length > 0 && data[0] == '*') {
            tokens = RESPDecoder.decodeArrayString(data);
        } else {
            String inline = new String(data).trim();
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
     * Sends a RESP-formatted error response to the client.
     */
    private static void respondError(String message, SocketChannel channel) throws IOException {
        channel.write(ByteBuffer.wrap(("-" + message + "\r\n").getBytes()));
    }

    /**
     * Evaluates the command and sends the response to the client.
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
            try {
                respondError(e.getMessage(), channel);
            } catch (IOException writeErr) {
                System.err.println("error writing error response: " + writeErr.getMessage());
            }
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
     * Main server loop — accepts connections sequentially, parses RESP commands,
     * evaluates them, and sends responses.
     */
    public static void run() throws IOException {
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(Config.HOST, Config.PORT));

        System.out.println("ready to accept connections on " + Config.HOST + ":" + Config.PORT);

        int clientCount = 0;

        while (true) {
            SocketChannel client = serverChannel.accept();
            clientCount++;
            System.out.println("client connected with address: " + client.getRemoteAddress()
                    + ", client count: " + clientCount);

            while (true) {
                RedisCmd cmd;
                try {
                    cmd = readCommand(client);
                } catch (Exception e) {
                    System.err.println("err: " + e.getMessage());
                    break;
                }

                if (cmd == null) {
                    clientCount--;
                    System.out.println("client disconnected: " + client.getRemoteAddress()
                            + ", client count: " + clientCount);
                    client.close();
                    break;
                }

                respond(cmd, client);
            }
        }
    }
}
