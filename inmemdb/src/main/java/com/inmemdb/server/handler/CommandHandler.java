package com.inmemdb.server.handler;

import com.inmemdb.core.Eval;
import com.inmemdb.core.RedisCmd;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * Netty pipeline stage 2: RedisCmd → RESP response
 *
 * Receives fully-decoded RedisCmd objects from RESPCommandDecoder,
 * delegates to Eval.evalAndRespond(), and writes the RESP-encoded
 * response back through the pipeline.
 *
 * Marked @Sharable so a single instance can be shared across all
 * connections — safe because it holds no per-connection state.
 *
 * Performance note:
 *   System.out.println is intentionally removed from channelRead0().
 *   It acquires a synchronized lock on every call — at 50k+ req/s
 *   that is 50k lock acquisitions per second on the hot path.
 *   Connect/disconnect logging is kept (low frequency events).
 */
@ChannelHandler.Sharable
public class CommandHandler extends SimpleChannelInboundHandler<RedisCmd> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RedisCmd cmd) {
        // Hot path — no logging, no allocation
        Eval.evalAndRespond(cmd, ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // Low-frequency event — logging is fine here
        System.out.println("client connected: " + ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // Low-frequency event — logging is fine here
        System.out.println("client disconnected: " + ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("error on channel " + ctx.channel().remoteAddress()
                + ": " + cause.getMessage());
        ctx.close();
    }
}
