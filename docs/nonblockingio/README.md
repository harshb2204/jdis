# From Raw Sockets to Netty — Making Java Perform Like Redis

This document builds from the ground up:
- What is wrong with blocking I/O
- What Java NIO actually is
- How epoll works under the hood
- What Netty adds on top of NIO
- How we integrated Netty into this project
- Why our server now behaves like Redis

---

## The Problem with Blocking I/O

When you call `read()` on a socket, the OS **blocks your thread** until the other side sends data.

```
Thread 1: read(socket_A) ──── waiting ──── waiting ──── data arrives ──── done
```

If you want to handle 1000 clients, you need 1000 threads. Each thread:
- Consumes ~512KB of stack memory by default in Java
- Spends most of its time **sleeping**, waiting for the client to send something
- Requires the OS to **context switch** between threads — expensive

This is what `SyncTCPServer` does. It handles one client at a time. While it is reading from client A, client B cannot connect.

---

## The Insight: The Kernel Already Knows

Here is the key insight that makes everything click.

When a client sends data over TCP:
1. The data hits your **network card**
2. The network card fires a hardware **interrupt**
3. The kernel wakes up, reads from the network card, and places the data in a **kernel buffer**
4. Your process is scheduled, copies data from kernel buffer → user space buffer
5. Your `read()` call returns

The important part is **step 3**. The kernel knows, before your process even wakes up, that data is available for a specific socket (file descriptor). The kernel can tell you: *"hey, FD 7 has data ready, FD 12 has data ready"* — without you having to block on each one individually.

This is the foundation of **I/O Multiplexing**.

---

## epoll — The Linux Kernel's I/O Notification System

`epoll` is a Linux kernel subsystem that lets you monitor **many file descriptors at once** and get notified when any of them are ready for I/O.

Three syscalls:

### `epoll_create1(0)`
Creates a new epoll instance. Returns a file descriptor (the "epoll fd") that represents the monitoring set.

```c
int epollFD = epoll_create1(0);
```

### `epoll_ctl(epollFD, op, targetFD, &event)`
Adds, modifies, or removes a file descriptor from the monitoring set.

```c
// Tell epoll: "watch serverFD for incoming connections"
epoll_ctl(epollFD, EPOLL_CTL_ADD, serverFD, &event);

// Tell epoll: "watch clientFD for incoming data"
epoll_ctl(epollFD, EPOLL_CTL_ADD, clientFD, &event);

// Tell epoll: "stop watching clientFD" (client disconnected)
epoll_ctl(epollFD, EPOLL_CTL_DEL, clientFD, NULL);
```

### `epoll_wait(epollFD, events[], maxEvents, timeout)`
**Blocks** until one or more monitored FDs are ready. Returns the list of ready FDs.

```c
// Block until something is ready
int n = epoll_wait(epollFD, events, MAX_EVENTS, -1);

for (int i = 0; i < n; i++) {
    if (events[i].fd == serverFD) {
        // new client connecting — call accept()
    } else {
        // existing client has data — call read()
    }
}
```

This is the **event loop**. One thread. One `epoll_wait` call. Handles thousands of clients.

### Level-Triggered vs Edge-Triggered

epoll has two notification modes:

**Level-Triggered (LT)** — default:
- The kernel notifies you **repeatedly** as long as data is available
- If you don't read all the data, you get notified again next time
- Easier to program, slightly more kernel overhead

**Edge-Triggered (ET)** — `EPOLLET` flag:
- The kernel notifies you **only once** when the state changes (new data arrives)
- You must read until you get `EAGAIN` (no more data)
- Fewer kernel-to-userspace transitions → faster at high connection counts
- This is what Netty's native epoll transport uses

---

## Java NIO — The Java Wrapper Around epoll

Java NIO (`java.nio`) was introduced in Java 1.4 to provide non-blocking I/O. On Linux, the JVM implements `Selector` using `epoll` under the hood.

### The Key Classes

**`ServerSocketChannel`** — non-blocking server socket
```java
ServerSocketChannel server = ServerSocketChannel.open();
server.configureBlocking(false);   // non-blocking
server.bind(new InetSocketAddress("0.0.0.0", 7379));
```

**`SocketChannel`** — non-blocking client socket
```java
SocketChannel client = server.accept();
client.configureBlocking(false);
```

**`Selector`** — the epoll wrapper
```java
Selector selector = Selector.open();  // epoll_create1 under the hood
```

**`SelectionKey`** — represents a channel registered with the selector
```java
// epoll_ctl(epollFD, EPOLL_CTL_ADD, serverFD, EPOLLIN)
server.register(selector, SelectionKey.OP_ACCEPT);

// epoll_ctl(epollFD, EPOLL_CTL_ADD, clientFD, EPOLLIN)
client.register(selector, SelectionKey.OP_READ);
```

### The NIO Event Loop

```java
while (true) {
    // epoll_wait — blocks until something is ready
    selector.select();

    Set<SelectionKey> keys = selector.selectedKeys();
    Iterator<SelectionKey> iter = keys.iterator();

    while (iter.hasNext()) {
        SelectionKey key = iter.next();
        iter.remove();

        if (key.isAcceptable()) {
            // server socket ready — accept new client
            SocketChannel client = serverChannel.accept();
            client.configureBlocking(false);
            client.register(selector, SelectionKey.OP_READ);

        } else if (key.isReadable()) {
            // client socket ready — read data
            SocketChannel client = (SocketChannel) key.channel();
            ByteBuffer buf = ByteBuffer.allocate(512);
            client.read(buf);
            // process command, write response
        }
    }
}
```

This is exactly what `AsyncTCPServer.java` does. One thread. Handles all clients.

### What NIO Does NOT Give You

NIO is a good foundation but has limitations:

1. **Level-triggered only** — `Selector` uses level-triggered epoll. More kernel notifications than necessary.
2. **`ByteBuffer` allocation** — every read allocates a new `ByteBuffer` on the Java heap → GC pressure at high throughput
3. **No TCP fragmentation handling** — if a command arrives in two TCP segments, you have to manually buffer and retry
4. **No pipeline abstraction** — all your logic is in one big `if/else` block in the event loop
5. **No connection lifecycle hooks** — you manually track connect/disconnect

This is where Netty comes in.

---

## Netty — Production-Grade NIO

Netty is a framework built on top of NIO that solves all the problems above. It is used by gRPC, Cassandra, RocketMQ, Elasticsearch, and many others.

### What Netty Adds

| Problem | NIO | Netty |
|---------|-----|-------|
| epoll mode | Level-triggered | Edge-triggered (native transport) |
| Buffer allocation | New `ByteBuffer` per read (GC) | Pooled off-heap `ByteBuf` (zero GC) |
| TCP fragmentation | Manual | Automatic (`ByteToMessageDecoder`) |
| Code structure | One big event loop | Pipeline of handlers per connection |
| Connection lifecycle | Manual tracking | `channelActive` / `channelInactive` hooks |

### The Native epoll Transport

Netty ships with `netty-transport-native-epoll` — a pre-compiled `.so` (shared library) for Linux that calls `epoll_create1`, `epoll_ctl`, `epoll_wait` directly via JNI, bypassing the JVM's NIO abstraction layer entirely.

```java
// Check if native epoll is available (Linux/WSL)
boolean useEpoll = Epoll.isAvailable();

// Use native epoll on Linux, fall back to NIO elsewhere
EventLoopGroup group = useEpoll
    ? new EpollEventLoopGroup(1)   // calls epoll_wait directly
    : new NioEventLoopGroup(1);    // calls Selector.select() → epoll_wait
```

On Linux, `EpollEventLoopGroup` uses **edge-triggered** epoll. The same JAR runs on Windows using NIO as a fallback.

### The Pooled ByteBuf Allocator

In NIO, every `channel.read()` allocates a new `ByteBuffer`:
```java
ByteBuffer buf = ByteBuffer.allocate(512);  // new heap allocation every time
client.read(buf);
// buf goes out of scope → GC collects it
```

At 100,000 requests/second, this creates 100,000 short-lived objects per second → GC pauses → latency spikes.

Netty's `PooledByteBufAllocator` maintains a pool of pre-allocated off-heap memory regions:
```
Pool: [buf1][buf2][buf3]...[bufN]
         ↑
    checked out for this read
    returned to pool after use
    never touched by GC
```

Zero allocation on the hot path. Zero GC pressure. This is one of the biggest reasons Netty is faster than raw NIO at high throughput.

### The Pipeline

Every accepted connection in Netty gets its own **pipeline** — a chain of handlers:

```
[Network bytes]
      ↓
[Handler 1: RESPCommandDecoder]   ← bytes → RedisCmd
      ↓
[Handler 2: CommandHandler]       ← RedisCmd → response
      ↓
[Network bytes out]
```

Each handler does one thing. Adding a new feature (TLS, compression, rate limiting) is just adding a new handler to the chain — no changes to existing code.

---

## How We Integrated Netty

### Step 1 — Add Dependencies (`pom.xml`)

```xml
<!-- Netty core -->
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-all</artifactId>
    <version>4.1.111.Final</version>
</dependency>

<!-- Native epoll transport for Linux/WSL -->
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-transport-native-epoll</artifactId>
    <version>4.1.111.Final</version>
    <classifier>linux-x86_64</classifier>
</dependency>
```

The `linux-x86_64` classifier downloads the pre-compiled `.so` for Linux x86_64 — the native epoll JNI library.

### Step 2 — The Server Bootstrap (`NettyTCPServer.java`)

```java
// 1. Detect transport
boolean useEpoll = Epoll.isAvailable();

// 2. Single event loop group — 1 thread, like Redis
EventLoopGroup group = useEpoll
    ? new EpollEventLoopGroup(1)
    : new NioEventLoopGroup(1);

// 3. Configure the server
ServerBootstrap bootstrap = new ServerBootstrap();
bootstrap
    .group(group)
    .channel(useEpoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
    .childHandler(new ChannelInitializer<SocketChannel>() {
        @Override
        protected void initChannel(SocketChannel ch) {
            // Pipeline for each new connection
            ch.pipeline().addLast("decoder", new RESPCommandDecoder());
            ch.pipeline().addLast("handler", new CommandHandler());
        }
    })
    .option(ChannelOption.SO_BACKLOG, 20000)
    .childOption(ChannelOption.TCP_NODELAY, true);

// 4. Bind and run
bootstrap.bind("0.0.0.0", 7379).sync()
         .channel().closeFuture().sync();
```

**Why `.group(group)` with a single group?**

Normally Netty uses two groups:
- **Boss group** — accepts connections
- **Worker group** — handles I/O

By passing the same single-thread group for both, we get one thread that does everything — exactly like Redis's main thread. No context switching between accept and I/O.

**Why `SO_BACKLOG = 20000`?**

The OS maintains a queue of fully-established TCP connections waiting to be `accept()`-ed. If 5000 clients connect simultaneously, the OS queues them. Setting this high prevents connections from being refused during bursts.

**Why `TCP_NODELAY = true`?**

Nagle's algorithm batches small writes to reduce packet count — good for bulk file transfers, bad for request/response protocols. With Nagle enabled, a 6-byte `+PONG\r\n` response might sit in the OS buffer waiting for more data to batch with. Disabling it sends each response immediately.

### Step 3 — The RESP Decoder (`RESPCommandDecoder.java`)

This is the first handler in the pipeline. It converts raw bytes into `RedisCmd` objects.

```java
public class RESPCommandDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 1) return;

        byte firstByte = in.getByte(in.readerIndex());
        if (firstByte == '*') {
            decodeRESPArray(in, out);   // redis-cli sends RESP arrays
        } else {
            decodeInline(in, out);      // telnet sends plain text
        }
    }
}
```

**Why `ByteToMessageDecoder`?**

TCP is a stream — there are no message boundaries. A `PING` command sent by `redis-cli` is:
```
*1\r\n$4\r\nPING\r\n
```
This might arrive as one TCP segment, or it might be split:
```
Segment 1: *1\r\n$4\r\n
Segment 2: PING\r\n
```

`ByteToMessageDecoder` maintains a **cumulation buffer** per connection. When segment 1 arrives, `decode()` is called — it sees an incomplete frame, resets the reader index, and returns without producing output. When segment 2 arrives, Netty appends it to the cumulation buffer and calls `decode()` again — now the full frame is there, it decodes successfully.

You never have to think about partial reads. Netty handles it.

**`markReaderIndex` / `resetReaderIndex`:**

```java
private void decodeRESPArray(ByteBuf in, List<Object> out) {
    in.markReaderIndex();   // save current position

    in.readByte();          // consume '*'
    int count = readInteger(in);
    if (count < 0) {
        in.resetReaderIndex();  // not enough data — undo all reads
        return;                 // Netty will call decode() again when more data arrives
    }
    // ... rest of parsing
}
```

This is the key pattern: mark before you start, reset if you don't have a complete frame.

**Why NOT `@Sharable`?**

`ByteToMessageDecoder` holds a per-connection cumulation buffer as instance state. If you marked it `@Sharable` and shared one instance across connections, all connections would share the same buffer — corrupting each other's data. A new instance is created per connection by the `ChannelInitializer`.

### Step 4 — The Command Handler (`CommandHandler.java`)

```java
@ChannelHandler.Sharable
public class CommandHandler extends SimpleChannelInboundHandler<RedisCmd> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RedisCmd cmd) {
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
        ctx.close();
    }
}
```

`SimpleChannelInboundHandler<RedisCmd>` only fires `channelRead0` when the message is a `RedisCmd` — the type produced by `RESPCommandDecoder`. It also automatically releases the message's reference count (Netty uses reference-counted buffers to avoid GC).

This handler is `@Sharable` because it holds no per-connection state — one instance is shared across all connections safely.

### Step 5 — The Evaluator (`Eval.java`)

```java
public static void evalAndRespond(RedisCmd cmd, ChannelHandlerContext ctx) {
    switch (cmd.getCmd()) {
        case "PING":
            evalPING(cmd.getArgs(), ctx);
            break;
    }
}

private static void evalPING(String[] args, ChannelHandlerContext ctx) {
    byte[] response = args.length == 0
        ? RESPEncoder.encode("PONG", true)    // +PONG\r\n
        : RESPEncoder.encode(args[0], false); // $N\r\n<arg>\r\n

    ctx.writeAndFlush(Unpooled.wrappedBuffer(response));
}
```

`Unpooled.wrappedBuffer(response)` wraps the existing `byte[]` without copying it — zero allocation. `writeAndFlush` sends it through the outbound pipeline and flushes to the network immediately.

---

## The Full Picture — How It All Connects

```
                        ┌─────────────────────────────────────────┐
                        │           NettyTCPServer                │
                        │                                         │
                        │   EpollEventLoopGroup(1)                │
                        │   └── 1 thread                          │
                        │       └── epoll_wait() loop             │
                        │                                         │
                        │   When server FD ready:                 │
                        │     accept() new client                 │
                        │     register clientFD with epoll        │
                        │                                         │
                        │   When client FD ready:                 │
                        │     read bytes into ByteBuf             │
                        │     ↓                                   │
                        │   [RESPCommandDecoder]                  │
                        │     ByteBuf → RedisCmd                  │
                        │     handles TCP fragmentation           │
                        │     ↓                                   │
                        │   [CommandHandler]                      │
                        │     RedisCmd → Eval.evalAndRespond()    │
                        │     ↓                                   │
                        │   ctx.writeAndFlush("+PONG\r\n")        │
                        │     bytes go back to client             │
                        └─────────────────────────────────────────┘
```

One thread. One epoll instance. Thousands of clients. No locks. No context switching. No GC on the hot path.

---

## Why This Performs Like Redis

Redis's architecture in one sentence: **one thread + epoll + in-memory operations**.

Our server's architecture: **one thread + Netty native epoll + in-memory operations**.

The match is exact:

| Redis | Our Server |
|-------|-----------|
| Single main thread | `EpollEventLoopGroup(1)` — 1 thread |
| `epoll_create1` | `EpollEventLoopGroup` constructor |
| `epoll_ctl(ADD, serverFD)` | `serverChannel.register(selector, OP_ACCEPT)` |
| `epoll_ctl(ADD, clientFD)` | `clientChannel.register(selector, OP_READ)` |
| `epoll_wait` loop | Netty's internal event loop |
| `read(fd, buf)` | `ByteBuf` read via native transport |
| Command parsing | `RESPCommandDecoder` |
| Command execution | `Eval.evalAndRespond()` |
| `write(fd, response)` | `ctx.writeAndFlush()` |
| No locks needed | No locks needed (single thread) |

The key reason Redis can be single-threaded and still handle 100,000+ requests/second:
- **Network I/O is slow** — waiting for clients to send data. epoll handles this with zero CPU cost.
- **In-memory operations are fast** — a `GET` or `SET` takes microseconds. The single thread is never the bottleneck.
- **No lock contention** — because only one thread ever touches the data store, there is no need for mutexes or synchronization. This eliminates a huge source of latency in multi-threaded servers.

---

## Evolution of Our Server

### Phase 1 — `SyncTCPServer` (Blocking I/O)

```
Thread: accept() → read() → eval() → write() → accept() → ...
                    ↑
              blocks here until client sends data
              meanwhile: no other client can connect
```

One client at a time. Simple but useless for production.

### Phase 2 — `AsyncTCPServer` (NIO Selector)

```
Thread: selector.select() → for each ready key → read/accept → eval → write
              ↑
        epoll_wait under the hood (level-triggered)
        many clients, one thread
        but: ByteBuffer allocations → GC pressure
             level-triggered → more kernel notifications
```

Many clients, one thread. Good for learning. Not optimal for production.

### Phase 3 — `NettyTCPServer` (Netty + Native epoll)

```
Thread: epoll_wait() → for each ready event → read into pooled ByteBuf
              ↑                                      ↓
        edge-triggered                    RESPCommandDecoder
        native JNI                              ↓
        zero GC                          CommandHandler
                                               ↓
                                         Eval → writeAndFlush
```

Many clients, one thread, edge-triggered epoll, zero GC on hot path, automatic TCP fragmentation handling. This is the production model.

---

## Key Takeaways

1. **Blocking I/O** requires one thread per client — doesn't scale.

2. **epoll** lets the kernel tell you which file descriptors are ready — one thread handles thousands of clients.

3. **Java NIO** wraps epoll with `Selector` — works but uses level-triggered mode and allocates `ByteBuffer` per read.

4. **Netty** builds on NIO and adds:
   - Native edge-triggered epoll (faster kernel notifications)
   - Pooled off-heap `ByteBuf` (zero GC)
   - `ByteToMessageDecoder` (automatic TCP fragmentation handling)
   - Pipeline architecture (clean separation of concerns)

5. **Single-threaded + epoll** is the right model for an in-memory database because:
   - I/O wait is handled by the kernel (epoll) — no thread blocking
   - In-memory ops are fast — the single thread is never the bottleneck
   - No locks needed — eliminates synchronization overhead entirely
