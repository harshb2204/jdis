package com.inmemdb.server;

import com.inmemdb.config.Config;
import com.inmemdb.core.Eval;
import com.inmemdb.core.RESPDecoder;
import com.inmemdb.core.RedisCmd;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;

public class SyncTCPServer {

    private static final int BUFFER_SIZE = 512;

    /**
     * Reads raw bytes from the client, decodes the RESP array into tokens,
     * and constructs a RedisCmd object.
     * Equivalent to readCommand in sync_tcp.go.
     */
    private static RedisCmd readCommand(Socket client) throws IOException {
        // TODO: Max read in one shot is 512 bytes
        // To allow input > 512 bytes, then repeated read until
        // we get EOF or designated delimiter
        InputStream in = client.getInputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead = in.read(buffer);
        if (bytesRead == -1) {
            return null;
        }

        byte[] data = Arrays.copyOf(buffer, bytesRead);

        String[] tokens;

        // Check if the data is RESP-encoded (starts with '*' for arrays)
        // If not, treat it as an inline command (plain text from telnet)
        if (data.length > 0 && data[0] == '*') {
            tokens = RESPDecoder.decodeArrayString(data);
        } else {
            // Inline command: split by whitespace
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
    private static void respondError(Exception err, Socket client) throws IOException {
        OutputStream out = client.getOutputStream();
        out.write(String.format("-%s\r\n", err.getMessage()).getBytes());
        out.flush();
    }

    /**
     * Evaluates the command and sends the response to the client.
     * If evaluation throws an error, sends a RESP error response.
     * Equivalent to respond in sync_tcp.go.
     */
    private static void respond(RedisCmd cmd, Socket client) {
        try {
            Eval.evalAndRespond(cmd, client);
        } catch (IOException e) {
            try {
                respondError(e, client);
            } catch (IOException writeErr) {
                System.err.println("error writing error response: " + writeErr.getMessage());
            }
        }
    }

    /**
     * Main server loop — accepts connections sequentially, parses RESP commands,
     * evaluates them, and sends responses.
     */
    public static void run() throws IOException {
        ServerSocket serverSocket = new ServerSocket(Config.PORT,
                50,
                java.net.InetAddress.getByName(Config.HOST));

        System.out.println("ready to accept connections on " + Config.HOST + ":" + Config.PORT);

        int clientCount = 0;

        while (true) {
            Socket client = serverSocket.accept();
            clientCount++;
            System.out.println("client connected with address: " + client.getRemoteSocketAddress()
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
                    System.out.println("client disconnected: " + client.getRemoteSocketAddress()
                            + ", client count: " + clientCount);
                    client.close();
                    break;
                }

                respond(cmd, client);
            }
        }
    }
}
