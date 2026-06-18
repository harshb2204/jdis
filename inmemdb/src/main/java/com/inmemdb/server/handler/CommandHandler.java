package com.inmemdb.server.handler;

import com.inmemdb.core.Eval;
import com.inmemdb.core.RedisCmd;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.logging.Logger;

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
 * Logging note:
 *   System.out.println is avoided on the hot path because it acquires
 *   a synchronized lock on every call. Instead we use java.util.logging
 *   at INFO level so each command is visible in the server output without
 *   the synchronization overhead of PrintStream.
 */
@ChannelHandler.Sharable
public class CommandHandler extends SimpleChannelInboundHandler<RedisCmd> {

    private static final Logger log = Logger.getLogger(CommandHandler.class.getName());

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RedisCmd cmd) {
        log.info("[" + ctx.channel().remoteAddress() + "] "
                + cmd.getCmd()
                + (cmd.getArgs().length > 0 ? " " + String.join(" ", cmd.getArgs()) : ""));
        Eval.evalAndRespond(cmd, ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        System.out.println("client connected: " + ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        System.out.println("client disconnected: " + ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("error on channel " + ctx.channel().remoteAddress()
                + ": " + cause.getMessage());
        ctx.close();
    }
}
