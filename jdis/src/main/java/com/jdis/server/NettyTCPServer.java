package com.jdis.server;

import com.jdis.config.Config;
import com.jdis.core.ExpiryManager;
import com.jdis.server.handler.CommandHandler;
import com.jdis.server.handler.RESPCommandDecoder;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * High-performance async TCP server built on Netty — single-threaded, like Redis.
 *
 * Threading model:
 *   A single EventLoopGroup with exactly 1 thread handles everything:
 *     - Accepting new TCP connections
 *     - Reading data from all connected clients
 *     - Decoding RESP commands
 *     - Evaluating commands and writing responses
 *     - Running the active-expiry cron (scheduled on the same thread)
 *
 *   This mirrors Redis's architecture exactly:
 *     - One thread calls epoll_wait() in a loop
 *     - Before blocking on epoll_wait(), Redis fires its serverCron() which
 *       runs the expiry sampling pass
 *     - In our Netty port, scheduleAtFixedRate() on the EventLoopGroup achieves
 *       the same effect — the cron task is queued onto the same I/O thread,
 *       so it runs between epoll_wait() wakeups, never concurrently with a
 *       command handler
 *     - Because only one thread ever touches the store, the HashMap needs no
 *       synchronization — identical to Redis
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

    /**
     * Handle to the active-expiry cron task.
     * To disable the cron at any time, simply call:
     *     cronFuture.cancel(false);
     * This is a one-liner that stops future executions without interrupting
     * a currently-running pass.
     */
    public static volatile ScheduledFuture<?> cronFuture;

    public static void run() throws InterruptedException {
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
            /*
             * Schedule the active-expiry cron on the event loop thread.
             *
             * Why scheduleAtFixedRate on the group, not a separate thread:
             *
             *   Redis runs its expiry sampling pass inside the same event loop
             *   iteration, before calling epoll_wait(). This means the cron and
             *   all command handlers share the same thread — zero concurrency,
             *   zero locks, zero races on the store.
             *
             *   scheduleAtFixedRate() on a Netty EventLoopGroup queues the task
             *   onto the I/O thread's task queue. Netty drains this queue between
             *   epoll_wait() wakeups — so the cron fires on the same thread as
             *   channelRead0(), evalSET(), evalGET(), etc.
             *
             *   The store HashMap is therefore only ever touched by one thread,
             *   making it safe without ConcurrentHashMap or any synchronization.
             */
            cronFuture = group.scheduleAtFixedRate(
                    ExpiryManager::deleteExpiredKeys,
                    1,          // initial delay — wait 1 s before first run
                    1,          // period — run every 1 s thereafter
                    TimeUnit.SECONDS
            );
            System.out.println("active expiry cron scheduled on event loop thread (interval: 1s)");

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
