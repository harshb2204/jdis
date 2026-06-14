package com.inmemdb.core;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Evaluates Redis commands and sends responses back to the client.
 * Equivalent to eval.go in DiceDB.
 */
public class Eval {

    private static void evalPING(String[] args, Socket client) throws IOException {
        byte[] response;

        if (args.length >= 2) {
            throw new IOException("ERR wrong number of arguments for 'ping' command");
        }

        if (args.length == 0) {
            response = RESPEncoder.encode("PONG", true);
        } else {
            response = RESPEncoder.encode(args[0], false);
        }

        OutputStream out = client.getOutputStream();
        out.write(response);
        out.flush();
    }

    /**
     * Evaluates the given RedisCmd and sends the appropriate response to the client.
     */
    public static void evalAndRespond(RedisCmd cmd, Socket client) throws IOException {
        System.out.println("command: " + cmd.getCmd());
        switch (cmd.getCmd()) {
            case "PING":
                evalPING(cmd.getArgs(), client);
                break;
            default:
                evalPING(cmd.getArgs(), client);
                break;
        }
    }
}
