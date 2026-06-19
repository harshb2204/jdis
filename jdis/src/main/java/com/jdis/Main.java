package com.jdis;

import com.jdis.config.Config;
import com.jdis.server.NettyTCPServer;

public class Main {

    // Parses optional --host and --port CLI arguments
    private static void setupFlags(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--host":
                    Config.HOST = args[i + 1];
                    break;
                case "--port":
                    Config.PORT = Integer.parseInt(args[i + 1]);
                    break;
            }
        }
    }

    // java -jar bin/jdis.jar
    // redis-cli -p 7379
    // telnet localhost 7379

    public static void main(String[] args) throws Exception {
        setupFlags(args);
        System.out.println("starting a simple redis-compatible server");
        NettyTCPServer.run();
    }
}
