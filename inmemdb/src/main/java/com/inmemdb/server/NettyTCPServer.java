package com.inmemdb.server;

import com.inmemdb.config.Config;
import com.inmemdb.server.handler.CommandHandler;
import com.inmemdb.server.handler.RESPCommandDecoder;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * High-performance async TCP server built on Netty — single-threaded, like Redis.
 *
 * Threading model:
 *   A single EventLoopGroup with exactly 1 thread handles everything:
 *     - Accepting new TCP connections
 *     - Reading data from all connected clients
 *     - Decoding RESP commands
 *     - Evaluating commands and writing responses
 *
 *   This mirrors Redis's architecture: one thread, epoll I/O multiplexing,
 *   never blocking — the thread only runs when there is actual work to do.
 *
 * On Linux/WSL, Netty uses its native epoll transport which gives:
 *   - Edge-triggered epoll (EPOLLET) — kernel notifies only on state change
 *   - Pooled off-heap ByteBuf allocator — zero GC pressure on the hot path
 *   - Scatter/gather I/O via writev()
 *
 * Falls back to NIO (java.nio.Selector) on non-Linux platforms.
 *
 * Netty pipeline per connection:
 *   [RESPCommandDecoder] → [CommandHandler]
 *
 *   RESPCommandDecoder : ByteBuf → RedisCmd  (frame decoder + RESP parser)
 *   CommandHandler     : RedisCmd → response written back as ByteBuf
 */
public class NettyTCPServer {

    public static void run() throws InterruptedException {
        // Detect whether native epoll is available (Linux/WSL)
        boolean useEpoll = Epoll.isAvailable();
        System.out.println("starting Netty TCP server on "
                + Config.HOST + ":" + Config.PORT
                + " [transport: " + (useEpoll ? "native epoll" : "NIO") + ", threads: 1]");

        /*
         * Single event loop group — 1 thread for everything.
         *
         * This is the same model Redis uses:
         *   - One thread calls epoll_wait()
         *   - When the server socket is ready → accept the new client
         *   - When a client socket is ready  → read, decode, evaluate, respond
         *   - No context switching, no synchronization, no locks needed
         *
         * Passing the same group as both boss and worker tells Netty to use
         * one thread for both accepting connections and handling I/O.
         */
        EventLoopGroup group = useEpoll
                ? new EpollEventLoopGroup(1)
                : new NioEventLoopGroup(1);

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap
                // Same single-thread group for both accept and I/O
                .group(group)
                // Use native EpollServerSocketChannel on Linux, NioServerSocketChannel elsewhere
                .channel(useEpoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        // 1. Decode incoming bytes into RedisCmd objects
                        pipeline.addLast("decoder", new RESPCommandDecoder());
                        // 2. Evaluate RedisCmd and write RESP response
                        pipeline.addLast("handler", new CommandHandler());
                    }
                })
                // Server socket options
                .option(ChannelOption.SO_BACKLOG, 20000)
                // Child (client) socket options
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)           // disable Nagle — low latency
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT); // pooled off-heap buffers

            // Bind and start accepting connections
            ChannelFuture future = bootstrap.bind(Config.HOST, Config.PORT).sync();
            System.out.println("ready to accept connections on " + Config.HOST + ":" + Config.PORT);

            // Block until the server socket is closed
            future.channel().closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }
}
