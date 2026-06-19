package com.jdis.server.handler;

import com.jdis.core.Eval;
import com.jdis.core.RedisCmd;

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
 * <h3>Pipelining Support</h3>
 *
 * Redis pipelining allows a client to send multiple commands in a single
 * TCP segment without waiting for each response. The server processes all
 * commands and sends all responses back in a single write — reducing the
 * number of syscalls and round-trips.
 *
 * In Netty, when multiple commands arrive in one TCP read:
 * <ol>
 *   <li>RESPCommandDecoder.decode() is called in a loop, producing multiple
 *       RedisCmd objects from the buffer</li>
 *   <li>channelRead0() is called once per decoded command — we call
 *       ctx.write() (WITHOUT flush) to buffer the response</li>
 *   <li>channelReadComplete() is called ONCE after all messages from a single
 *       read batch are processed — we call ctx.flush() here to send all
 *       buffered responses in a single write syscall</li>
 * </ol>
 *
 * This is the Netty equivalent of Go's approach of collecting all responses
 * into a bytes.Buffer and writing them all at once.
 *
 * Marked @Sharable so a single instance can be shared across all
 * connections — safe because it holds no per-connection state.
 */
@ChannelHandler.Sharable
public class CommandHandler extends SimpleChannelInboundHandler<RedisCmd> {

    private static final Logger log = Logger.getLogger(CommandHandler.class.getName());

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RedisCmd cmd) {
        log.info("[" + ctx.channel().remoteAddress() + "] "
                + cmd.getCmd()
                + (cmd.getArgs().length > 0 ? " " + String.join(" ", cmd.getArgs()) : ""));
        // Write response without flushing — pipelining batches all responses
        // and flushes them together in channelReadComplete()
        Eval.evalAndRespond(cmd, ctx);
    }

    /**
     * Called once after ALL messages from a single read event have been
     * processed by channelRead0(). This is where we flush all buffered
     * responses in a single write syscall — the key to pipelining performance.
     *
     * If the client sent 3 pipelined commands, channelRead0() is called 3 times
     * (each calling ctx.write()), then channelReadComplete() is called once
     * (calling ctx.flush()) — resulting in 1 syscall instead of 3.
     */
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
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
