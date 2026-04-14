package com.inmemdb.server;

import com.inmemdb.config.Config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket; // listens for incoming connections
import java.net.Socket;       // represents a client connection



public class SyncTCPServer {

    private static final int BUFFER_SIZE = 512;

    // Reads up to 512 bytes from the client connection
    private static String readCommand(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead = in.read(buffer); // blocking call waits until data arrives or client dc
        if (bytesRead == -1) {
            return null;
        }
        String command = new String(buffer, 0, bytesRead).trim();
        System.out.println("received command: " + command);
        return command;
    }

    // Echoes the command back to the client
    private static void respond(String command, Socket client) throws IOException {
        OutputStream out = client.getOutputStream();
        out.write((command + "\n").getBytes());
        out.flush();
    }

    // Main server loop — accepts connections sequentially and echoes commands
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
                String command = readCommand(client);
                if (command == null) {
                    clientCount--;
                    System.out.println("client disconnected: " + client.getRemoteSocketAddress()
                            + ", client count: " + clientCount);
                    client.close();
                    break;
                }
                respond(command, client);
            }
        }
    }
}
