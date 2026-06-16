# Redis Internals

Building a simplified Redis from scratch in Java to understand its internals — one layer at a time.

---



## Project Overview

This project is a ground-up implementation of a Redis-compatible in-memory database server in Java. The goal is to understand how Redis works internally — starting from raw TCP socket handling, the RESP wire protocol, and building up toward a full key-value store.

The current implementation (`inmemdb/`) is **Phase 3**: a high-performance, fully concurrent TCP server built on **Netty** with native **epoll** (Linux/WSL) that:
- Listens on a configurable host/port (default `0.0.0.0:7379`)
- Accepts and handles **thousands of concurrent clients** in a single thread using epoll I/O multiplexing
- Uses Netty's **native epoll transport** on Linux/WSL (edge-triggered, zero-copy, pooled off-heap buffers)
- Parses incoming data as RESP protocol (from `redis-cli`) or inline commands (from `telnet`)
- Handles TCP fragmentation automatically — partial reads are buffered and retried
- Evaluates commands and responds with proper RESP-encoded responses
- Supports `PING`, `SET` (with optional `EX` expiry), `GET`, and `TTL` with full Redis-compatible behavior
- Includes a RESP protocol decoder and encoder
- Maintains an in-memory key-value store with optional per-key TTL (time-to-live)

### Evolution of the server

| Phase | Server | Concurrency model |
|-------|--------|-------------------|
| 1 | `SyncTCPServer` | Single client at a time (blocking I/O) |
| 2 | `AsyncTCPServer` | Many clients, NIO `Selector` (level-triggered epoll) |
| 3 | `NettyTCPServer` ← **current** | Many clients, **1 thread**, Netty native epoll (edge-triggered, pooled buffers) — same model as Redis |

---

## Project Structure

```
inmemdb/
├── pom.xml                          # Maven build file (includes Netty dependencies)
├── bin/
│   ├── inmemdb.jar                  # Pre-built executable JAR
│   └── libs/                        # Netty and dependency JARs (auto-copied by Maven)
└── src/
    └── main/
        └── java/
            └── com/inmemdb/
                ├── Main.java                        # Entry point — parses CLI args, starts server
                ├── config/
                │   └── Config.java                  # Global config (HOST, PORT)
                ├── core/
                │   ├── Store.java                    # In-memory key-value store (Obj, put, get)
                │   ├── RESPDecoder.java              # RESP protocol parser (decode)
                │   ├── RESPEncoder.java              # RESP protocol encoder (encode responses)
                │   ├── RedisCmd.java                 # Command object (cmd + args)
                │   └── Eval.java                    # Command evaluator — PING, SET, GET, TTL
                └── server/
                    ├── NettyTCPServer.java           # ← Active server: Netty + native epoll
                    ├── AsyncTCPServer.java           # Reference: NIO Selector (level-triggered epoll)
                    ├── SyncTCPServer.java            # Reference: single-client blocking server
                    └── handler/
                        ├── RESPCommandDecoder.java   # Netty pipeline stage 1: ByteBuf → RedisCmd
                        └── CommandHandler.java       # Netty pipeline stage 2: RedisCmd → response
```

---

## How to Run

### Prerequisites

- Java 17 or higher (required for `record` types and switch expressions used in `RESPDecoder.java`)
- Maven 3.x (only needed if you want to build from source)

---

### Option 1 — Build from source and run

```bash
# Navigate to the inmemdb directory
cd inmemdb

# Build the project (compiles, packages JAR, copies Netty libs to bin/libs/)
mvn package

# Run the JAR
java -jar bin/inmemdb.jar
```

---

### Option 2 — Run with custom host/port

```bash
java -jar bin/inmemdb.jar --host 127.0.0.1 --port 6379
```

---

### Connect to the server

Once the server is running, connect using `telnet` or `redis-cli`:

**From Windows (PowerShell / CMD):**
```bash
# Using telnet
telnet localhost 7379

# Using redis-cli (if installed)
redis-cli -p 7379
```

**From WSL (Linux on Windows):**

WSL uses a virtual network and cannot reach Windows `localhost` directly. Use the Windows host IP instead:

```bash
# Find the Windows host IP from inside WSL
cat /etc/resolv.conf | grep nameserver

# Then connect using that IP (typically in the 172.x.x.x range)
telnet 172.17.32.1 7379
redis-cli -h 172.17.32.1 -p 7379
```

**Running directly in WSL (recommended for native epoll):**

```bash
# Build and run inside WSL — this activates Netty's native epoll transport
cd inmemdb && mvn package -q && java -jar bin/inmemdb.jar
```

You should see output like:
```
starting a simple redis-compatible server
starting Netty TCP server on 0.0.0.0:7379 [transport: native epoll]
ready to accept connections on 0.0.0.0:7379
```

> When running on Windows (not WSL), the transport line will say `[transport: NIO]` — Netty automatically falls back to Java NIO since native epoll is Linux-only.

---

### Try the PING command

**Using `redis-cli`** (human-friendly output):
```
127.0.0.1:7379> PING
PONG
127.0.0.1:7379> PING hello
"hello"
127.0.0.1:7379> PING hello world
(error) ERR wrong number of arguments for 'ping' command
```

**Using `telnet`** (raw RESP output):
```
PING
+PONG
PING hello
$5
hello
PING hello world
-ERR wrong number of arguments for 'ping' command
```

The difference is that `redis-cli` parses the RESP response and displays it in a user-friendly format, while `telnet` shows the raw RESP wire format:
- `+PONG` → RESP Simple String (prefix `+`)
- `$5\r\nhello` → RESP Bulk String (prefix `$`, length 5, then the data)
- `-ERR ...` → RESP Error (prefix `-`)

---

### Try SET, GET, and TTL

**Using `redis-cli`** (human-friendly output):
```
# Basic SET and GET
127.0.0.1:7379> SET city tokyo
OK
127.0.0.1:7379> GET city
"tokyo"

# GET a key that doesn't exist → nil
127.0.0.1:7379> GET unknown
(nil)

# SET with EX (expiry in seconds)
127.0.0.1:7379> SET session abc123 EX 10
OK
127.0.0.1:7379> TTL session
(integer) 9

# TTL on a key with no expiry → -1
127.0.0.1:7379> TTL name
(integer) -1

# TTL on a key that doesn't exist → -2
127.0.0.1:7379> TTL ghost
(integer) -2

# After the key expires, GET returns nil and TTL returns -2
127.0.0.1:7379> GET session
(nil)
127.0.0.1:7379> TTL session
(integer) -2

# Wrong number of arguments
127.0.0.1:7379> SET
(error) ERR wrong number of arguments for 'set' command
127.0.0.1:7379> GET
(error) ERR wrong number of arguments for 'get' command
127.0.0.1:7379> SET key value BADOPT
(error) ERR syntax error
```

**Using `telnet`** (raw RESP output):
```
SET city tokyo
+OK
GET city
$5
tokyo
GET unknown
$-1
SET session abc123 EX 10
+OK
TTL session
:9
TTL name
:-1
TTL ghost
:-2
```

The RESP types used in responses:
- `+OK` → RESP Simple String — SET always responds with this on success
- `$N\r\n<value>` → RESP Bulk String — GET returns the stored value
- `$-1` → RESP Nil Bulk String — GET returns this when the key doesn't exist or has expired
- `:N` → RESP Integer — TTL returns the remaining seconds (or -1 / -2 sentinel values)
- `-ERR ...` → RESP Error — returned on bad arguments or syntax errors

---

### Benchmarking

Use `redis-benchmark` to compare performance between real Redis and our server.

**Benchmark real Redis (baseline):**
```bash
redis-benchmark -n 100000 -t ping_inline -c 50 -P 1 -h localhost -p 6379
redis-benchmark -n 100000 -t ping_mbulk -c 50 -P 1 -h localhost -p 6379
```

**Benchmark our server (from WSL):**
```bash
redis-benchmark -n 100000 -t ping_inline -c 50 -P 1 -h 172.17.32.1 -p 7379
redis-benchmark -n 100000 -t ping_mbulk -c 50 -P 1 -h 172.17.32.1 -p 7379
```

**Flags explained:**
| Flag | Meaning |
|------|---------|
| `-n 100000` | Total number of requests to send |
| `-t ping_inline` | Test inline PING (plain text, like telnet) |
| `-t ping_mbulk` | Test PING via RESP protocol (bulk string) |
| `-c 50` | Use 50 concurrent clients — the server now handles this |
| `-P 1` | Disable pipelining (send 1 command, wait for response) |
| `-h` | Host address |
| `-p` | Port number |

> **Note:** Unlike Phase 1, you can now use `-c 50` (or higher) because the Netty server handles all clients concurrently in a single event loop thread.

---

## Code Walkthrough

### `Main.java` — Entry Point

```java
public class Main {

    private static void setupFlags(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--host": Config.HOST = args[i + 1]; break;
                case "--port": Config.PORT = Integer.parseInt(args[i + 1]); break;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        setupFlags(args);
        System.out.println("starting a simple redis-compatible server");
        NettyTCPServer.run();
    }
}
```

**What it does:**

- `setupFlags()` iterates over the command-line arguments looking for `--host` and `--port` flags. If found, it overwrites the defaults in `Config`. This is a simple hand-rolled argument parser — no external library needed.
- `main()` calls `setupFlags()` first, then hands off control to `NettyTCPServer.run()` which blocks until the server is shut down.
- The `throws Exception` on `main` is intentional — if the server socket fails to bind (e.g., port already in use), the exception propagates and the JVM prints the error and exits.

---

### `Config.java` — Global Configuration

```java
public class Config {
    public static String HOST = "0.0.0.0";
    public static int PORT = 7379;
}
```

**What it does:**

- Holds two `public static` mutable fields that act as global configuration for the entire application.
- `HOST = "0.0.0.0"` means "bind to all available network interfaces" — the server will accept connections from any IP address on the machine, not just localhost.
- `PORT = 7379` is the default port. Redis uses `6379`; this project uses `7379` to avoid conflicts with a running Redis instance.
- Because these are plain `static` fields (not `final`), `Main.setupFlags()` can overwrite them before the server starts.

---

### `NettyTCPServer.java` — The Netty Server

This is the heart of Phase 3. It sets up a Netty `ServerBootstrap` with a **single event loop thread** — exactly like Redis — and a pipeline of handlers per connection.

```java
public static void run() throws InterruptedException {
    boolean useEpoll = Epoll.isAvailable();

    // One thread handles everything: accept + all I/O — same as Redis
    EventLoopGroup group = useEpoll
            ? new EpollEventLoopGroup(1)
            : new NioEventLoopGroup(1);

    ServerBootstrap bootstrap = new ServerBootstrap();
    bootstrap
        .group(group)   // single group, single thread
        .channel(useEpoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
        .childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.pipeline().addLast("decoder", new RESPCommandDecoder());
                ch.pipeline().addLast("handler", new CommandHandler());
            }
        })
        .option(ChannelOption.SO_BACKLOG, 20000)
        .childOption(ChannelOption.TCP_NODELAY, true);

    bootstrap.bind(Config.HOST, Config.PORT).sync()
             .channel().closeFuture().sync();
}
```

**Breaking it down piece by piece:**

#### `Epoll.isAvailable()` — Transport Detection

```java
boolean useEpoll = Epoll.isAvailable();
```

- Checks at runtime whether the native epoll `.so` library is available (Linux/WSL only).
- If `true`: uses `EpollEventLoopGroup` + `EpollServerSocketChannel` — native epoll, edge-triggered, zero-copy.
- If `false`: falls back to `NioEventLoopGroup` + `NioServerSocketChannel` — Java NIO Selector, works on all platforms.
- This makes the same JAR work on both Windows (NIO) and Linux/WSL (native epoll) without any code changes.

#### Single Event Loop Group — 1 Thread for Everything

```java
EventLoopGroup group = new EpollEventLoopGroup(1);  // exactly 1 thread
bootstrap.group(group);                              // used for both accept and I/O
```

- A single `EventLoopGroup` with **1 thread** is passed as both the boss and worker group.
- This one thread does everything Redis's main thread does:
  - Calls `epoll_wait()` in a loop
  - When the server socket is ready → accepts the new client and registers it with epoll
  - When a client socket is ready → reads bytes, runs them through the pipeline, writes the response
- There is **no context switching, no synchronization, no locks** — because only one thread ever touches the data.
- On Linux, this thread calls `epoll_wait()` directly via the native JNI `.so` — the same syscall Redis uses.

#### `SO_BACKLOG` and `TCP_NODELAY`

```java
.option(ChannelOption.SO_BACKLOG, 20000)       // server socket option
.childOption(ChannelOption.TCP_NODELAY, true)  // per-client socket option
```

- `SO_BACKLOG = 20000`: The OS-level queue for incoming connections not yet `accept()`-ed. Set high to handle connection bursts.
- `TCP_NODELAY = true`: Disables Nagle's algorithm. Nagle buffers small writes and batches them to reduce packet count — useful for bulk transfers but adds latency for request/response protocols like Redis. Disabling it ensures each response is sent immediately.

#### The Pipeline

```java
ch.pipeline().addLast("decoder", new RESPCommandDecoder());
ch.pipeline().addLast("handler", new CommandHandler());
```

- Every accepted client connection gets its own **pipeline** — a chain of handlers that process inbound and outbound data in order.
- **Inbound** (client → server): bytes flow through `RESPCommandDecoder` first, then `CommandHandler`.
- **Outbound** (server → client): responses written in `CommandHandler` flow directly out to the network.
- A new `RESPCommandDecoder` instance is created per connection (it's stateful — it holds a partial-read buffer). `CommandHandler` is stateless and shared.

---

### `RESPCommandDecoder.java` — Netty Pipeline Stage 1

Converts raw bytes arriving from the network into `RedisCmd` objects.

```java
public class RESPCommandDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 1) return;
        byte firstByte = in.getByte(in.readerIndex());
        if (firstByte == '*') {
            decodeRESPArray(in, out);
        } else {
            decodeInline(in, out);
        }
    }
}
```

**Why `ByteToMessageDecoder`?**

TCP is a stream protocol — there is no concept of "message boundaries". A single `redis-cli PING` command might arrive as:
- One read: `*1\r\n$4\r\nPING\r\n` (complete)
- Two reads: `*1\r\n$4\r\n` then `PING\r\n` (fragmented)
- Or even split across three or more reads

`ByteToMessageDecoder` solves this automatically. It maintains a **cumulation buffer** per connection. Every time data arrives, Netty appends it to this buffer and calls `decode()`. If `decode()` doesn't consume all the bytes (because a full command hasn't arrived yet), the remaining bytes stay in the buffer and `decode()` is called again when more data arrives.

**RESP array decoding:**

```java
private void decodeRESPArray(ByteBuf in, List<Object> out) {
    in.markReaderIndex();  // save position — reset here if we don't have a full frame

    in.readByte();         // consume '*'
    int count = readInteger(in);  // e.g. *2 → count = 2
    if (count < 0) { in.resetReaderIndex(); return; }  // not enough data yet

    List<String> tokens = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
        in.readByte();             // consume '$'
        int len = readInteger(in); // e.g. $4 → len = 4
        if (in.readableBytes() < len + 2) { in.resetReaderIndex(); return; }
        String token = in.readCharSequence(len, UTF_8).toString();
        in.skipBytes(2);           // skip \r\n after the bulk string data
        tokens.add(token);
    }

    out.add(new RedisCmd(tokens.get(0).toUpperCase(), /* rest as args */));
}
```

- `markReaderIndex()` / `resetReaderIndex()`: If at any point there aren't enough bytes to complete the frame, the reader position is reset to the start of the command. Netty will call `decode()` again when more bytes arrive.
- `readCharSequence(len, UTF_8)`: Reads exactly `len` bytes as a string — safe for binary data and strings containing `\r\n`.

**Inline decoding (telnet support):**

```java
private void decodeInline(ByteBuf in, List<Object> out) {
    int lineEnd = findLineEnd(in);
    if (lineEnd < 0) return;  // no \n found yet — wait for more data

    String line = in.readCharSequence(lineEnd - in.readerIndex(), UTF_8).toString().trim();
    // skip \r\n, split by whitespace, build RedisCmd
}
```

- Scans for a `\n` byte. If not found, returns without consuming anything — waits for more data.
- Once a full line is available, splits it by whitespace to get command + args.

**Note on `@Sharable`:**

`ByteToMessageDecoder` holds a per-connection cumulation buffer as instance state, so it **cannot** be marked `@Sharable`. A new `RESPCommandDecoder` instance is created for each connection by the `ChannelInitializer` in `NettyTCPServer`.

---

### `CommandHandler.java` — Netty Pipeline Stage 2

Receives fully-decoded `RedisCmd` objects and dispatches them to `Eval`.

```java
@ChannelHandler.Sharable
public class CommandHandler extends SimpleChannelInboundHandler<RedisCmd> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RedisCmd cmd) {
        System.out.println("command: " + cmd.getCmd());
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
```

**What it does:**

- `SimpleChannelInboundHandler<RedisCmd>`: A Netty base class that only fires `channelRead0()` when the inbound message is of type `RedisCmd`. It also automatically releases the message's reference count after `channelRead0()` returns (Netty uses reference-counted buffers).
- `channelRead0()`: The hot path. Called once per fully-decoded command. Delegates to `Eval.evalAndRespond()`.
- `channelActive()` / `channelInactive()`: Lifecycle hooks — called when a client connects or disconnects. Used for logging.
- `exceptionCaught()`: Called when an unhandled exception occurs in the pipeline. Logs the error and closes the channel.
- `@Sharable`: Safe to mark because this handler holds no per-connection state — a single instance is shared across all connections.

---

### `Store.java` — The In-Memory Key-Value Store

The store is the heart of the database — it holds all key-value pairs in a `HashMap` and wraps each value in an `Obj` that carries an optional expiry timestamp.

```java
public class Store {

    private static final Map<String, Obj> store = new HashMap<>();

    public static class Obj {
        public final Object value;
        public final long expiresAt; // -1 = no expiry, else Unix ms timestamp
    }

    public static Obj newObj(Object value, long durationMs) { ... }
    public static void put(String key, Obj obj) { ... }
    public static Obj get(String key) { ... }
}
```

#### `Obj` — The Value Wrapper

```java
public static class Obj {
    public final Object value;
    public final long expiresAt; // -1 = no expiry
}
```

- Every stored value is wrapped in an `Obj` rather than stored raw. This lets us attach metadata (currently just `expiresAt`) to any value without changing the map's type.
- `value` is `Object` — the store is type-agnostic. Right now all values are `String`, but future commands (e.g., `LPUSH`, `HSET`) can store lists, maps, etc. without changing the store's API.
- `expiresAt = -1` means "this key lives forever". Otherwise it is an absolute Unix timestamp in **milliseconds** (not seconds — millisecond precision lets TTL be accurate to within 1ms).

#### `newObj()` — The Factory

```java
public static Obj newObj(Object value, long durationMs) {
    long expiresAt = -1;
    if (durationMs > 0) {
        expiresAt = System.currentTimeMillis() + durationMs;
    }
    return new Obj(value, expiresAt);
}
```

- Takes a **duration** (how long from now), not an absolute timestamp. The factory converts it to an absolute timestamp by adding `System.currentTimeMillis()`.
- `durationMs <= 0` means no expiry — `expiresAt` stays `-1`.
- Called by `evalSET()` with `durationMs = exDurationSec * 1000` when `EX` is provided, or `durationMs = -1` when no expiry is set.

#### `put()` and `get()`

```java
public static void put(String key, Obj obj) {
    store.put(key, obj);
}

public static Obj get(String key) {
    return store.get(key);
}
```

- `put()` is a direct `HashMap.put()` — no expiry logic here. The `Obj` already has the expiry baked in.
- `get()` is also a direct `HashMap.get()` — it returns the `Obj` even if it has expired. The **expiry check is done by the caller** (`evalGET` and `evalTTL` in `Eval.java`).

> **Why check expiry in the caller rather than in `get()`?**
> Different commands need different behaviour on expiry. `GET` returns nil. `TTL` returns `-2`. `PERSIST` would remove the expiry. Keeping `get()` dumb and letting each command decide what to do with an expired key is cleaner and more extensible.

---

### `Eval.java` — The Command Evaluator

Evaluates `RedisCmd` objects and writes RESP responses back through the Netty pipeline.

#### Static Pre-computed Responses

```java
private static final ByteBuf PONG_RESPONSE = staticBuf("+PONG\r\n");
private static final ByteBuf OK_RESPONSE   = staticBuf("+OK\r\n");
private static final ByteBuf NIL_RESPONSE  = staticBuf("$-1\r\n");
private static final ByteBuf TTL_NO_KEY    = staticBuf(":-2\r\n");
private static final ByteBuf TTL_NO_EXPIRY = staticBuf(":-1\r\n");
// ... error strings ...

private static ByteBuf staticBuf(String s) {
    return Unpooled.unreleasableBuffer(
            Unpooled.directBuffer().writeBytes(s.getBytes(StandardCharsets.UTF_8)));
}
```

Every constant response (OK, NIL, TTL sentinels, all error strings) is pre-allocated once at class load time as an unreleasable off-heap `ByteBuf`. On the hot path, `ctx.writeAndFlush(OK_RESPONSE.duplicate())` sends a view of the same buffer — zero allocation, zero GC. See `docs/L05/README.md` for the full explanation of this pattern.

#### `evalAndRespond()` — The Dispatcher

```java
public static void evalAndRespond(RedisCmd cmd, ChannelHandlerContext ctx) {
    switch (cmd.getCmd()) {
        case "PING": evalPING(cmd.getArgs(), ctx); break;
        case "SET":  evalSET(cmd.getArgs(), ctx);  break;
        case "GET":  evalGET(cmd.getArgs(), ctx);  break;
        case "TTL":  evalTTL(cmd.getArgs(), ctx);  break;
        default:     evalPING(cmd.getArgs(), ctx); break;
    }
}
```

- Routes each command to its handler by name. `cmd.getCmd()` is always uppercase (normalised in `RESPCommandDecoder`), so the switch cases are simple string literals.
- Unknown commands fall through to `evalPING`.

#### `evalPING()` — PING

```java
private static void evalPING(String[] args, ChannelHandlerContext ctx) {
    if (args.length >= 2) {
        ctx.writeAndFlush(ERR_PING_ARGS.duplicate());
        return;
    }
    if (args.length == 0) {
        ctx.writeAndFlush(PONG_RESPONSE.duplicate());
    } else {
        // Dynamic bulk string: $<len>\r\n<arg>\r\n
        byte[] argBytes = args[0].getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = ctx.alloc().buffer(argBytes.length + 16);
        buf.writeByte('$');
        writeAsciiLong(buf, argBytes.length);
        // ... \r\n + data + \r\n
        ctx.writeAndFlush(buf);
    }
}
```

- **0 args** → static `+PONG\r\n` — zero allocation.
- **1 arg** → dynamic bulk string built into a pooled buffer — zero GC.
- **2+ args** → static error — zero allocation.

#### `evalSET()` — SET key value [EX seconds]

```java
private static void evalSET(String[] args, ChannelHandlerContext ctx) {
    if (args.length <= 1) {
        ctx.writeAndFlush(ERR_SET_ARGS.duplicate());
        return;
    }

    String key   = args[0];
    String value = args[1];
    long exDurationMs = -1;

    for (int i = 2; i < args.length; i++) {
        switch (args[i].toUpperCase()) {
            case "EX":
                i++;
                if (i == args.length) { ctx.writeAndFlush(ERR_SYNTAX.duplicate()); return; }
                try {
                    exDurationMs = Long.parseLong(args[i]) * 1000;
                } catch (NumberFormatException e) {
                    ctx.writeAndFlush(ERR_NOT_INT.duplicate()); return;
                }
                break;
            default:
                ctx.writeAndFlush(ERR_SYNTAX.duplicate()); return;
        }
    }

    Store.put(key, Store.newObj(value, exDurationMs));
    ctx.writeAndFlush(OK_RESPONSE.duplicate());
}
```

- Requires at least 2 args (`key` and `value`). Anything less → error.
- Iterates over `args[2:]` looking for option flags. Currently only `EX` is supported.
- `EX` consumes the next token as the expiry in seconds, converts to milliseconds, and passes it to `Store.newObj()`.
- Any unrecognised option → `-ERR syntax error` (matches Redis behaviour exactly).
- On success → static `+OK\r\n` — zero allocation.

#### `evalGET()` — GET key

```java
private static void evalGET(String[] args, ChannelHandlerContext ctx) {
    if (args.length != 1) { ctx.writeAndFlush(ERR_GET_ARGS.duplicate()); return; }

    Store.Obj obj = Store.get(args[0]);

    if (obj == null) {
        ctx.writeAndFlush(NIL_RESPONSE.duplicate());  // key doesn't exist
        return;
    }
    if (obj.expiresAt != -1 && obj.expiresAt <= System.currentTimeMillis()) {
        ctx.writeAndFlush(NIL_RESPONSE.duplicate());  // key has expired
        return;
    }

    // Return value as RESP bulk string
    byte[] valBytes = obj.value.toString().getBytes(StandardCharsets.UTF_8);
    ByteBuf buf = ctx.alloc().buffer(valBytes.length + 16);
    buf.writeByte('$');
    writeAsciiLong(buf, valBytes.length);
    buf.writeByte('\r'); buf.writeByte('\n');
    buf.writeBytes(valBytes);
    buf.writeByte('\r'); buf.writeByte('\n');
    ctx.writeAndFlush(buf);
}
```

- Calls `Store.get()` which returns the raw `Obj` (no expiry check inside the store).
- Checks expiry inline: if `expiresAt != -1` and the timestamp is in the past → nil.
- On a live key → writes the value as a RESP Bulk String into a pooled buffer.
- `$-1\r\n` (nil) is a pre-computed static buffer — zero allocation on the miss path.

#### `evalTTL()` — TTL key

```java
private static void evalTTL(String[] args, ChannelHandlerContext ctx) {
    if (args.length != 1) { ctx.writeAndFlush(ERR_TTL_ARGS.duplicate()); return; }

    Store.Obj obj = Store.get(args[0]);

    if (obj == null)           { ctx.writeAndFlush(TTL_NO_KEY.duplicate());    return; } // :-2
    if (obj.expiresAt == -1)   { ctx.writeAndFlush(TTL_NO_EXPIRY.duplicate()); return; } // :-1

    long durationMs = obj.expiresAt - System.currentTimeMillis();
    if (durationMs < 0)        { ctx.writeAndFlush(TTL_NO_KEY.duplicate());    return; } // :-2

    // Return remaining seconds as RESP integer
    long ttlSec = durationMs / 1000;
    ByteBuf buf = ctx.alloc().buffer(24);
    buf.writeByte(':');
    writeAsciiLong(buf, ttlSec);
    buf.writeByte('\r'); buf.writeByte('\n');
    ctx.writeAndFlush(buf);
}
```

- Three sentinel cases, all served by pre-computed static buffers:
  - Key doesn't exist → `:-2\r\n`
  - Key exists, no expiry → `:-1\r\n`
  - Key exists but already expired → `:-2\r\n`
- For a live key with an expiry: computes `(expiresAt - now) / 1000` to get remaining seconds, writes it as a RESP Integer into a pooled buffer.
- Integer division truncates — a key with 9.9 seconds left reports `9`, matching Redis behaviour.

#### `writeAsciiLong()` — Zero-Allocation Integer Serialisation

```java
private static void writeAsciiLong(ByteBuf buf, long value) {
    if (value == 0) { buf.writeByte('0'); return; }
    byte[] digits = new byte[20];
    int pos = 0;
    while (value > 0) {
        digits[pos++] = (byte) ('0' + (value % 10));
        value /= 10;
    }
    for (int i = pos - 1; i >= 0; i--) buf.writeByte(digits[i]);
}
```

- Converts a `long` to ASCII digits directly into the `ByteBuf` without calling `Long.toString()` (which allocates a `String`) or `String.getBytes()` (which allocates a `byte[]`).
- Extracts digits right-to-left using `value % 10`, stores them in a 20-byte stack array, then writes them left-to-right.
- The `digits[]` array lives on the thread stack — zero heap allocation.

**Why `ChannelHandlerContext` instead of a raw socket?**

In the NIO servers (`SyncTCPServer`, `AsyncTCPServer`), `Eval` wrote directly to a `SocketChannel`. In the Netty server, writing goes through the **pipeline** via `ChannelHandlerContext`. This allows any outbound handlers added to the pipeline (e.g., a future encoder or compressor) to process the response before it hits the wire.

---

### `RESPDecoder.java` — The RESP Protocol Parser

RESP (**RE**dis **S**erialization **P**rotocol) is the wire protocol Redis uses to communicate between clients and the server. Every Redis command you type in `redis-cli` is encoded as RESP before being sent over TCP.

#### RESP Data Types

| Prefix | Type          | Example                          |
|--------|---------------|----------------------------------|
| `+`    | Simple String | `+OK\r\n`                        |
| `-`    | Error         | `-ERR unknown command\r\n`       |
| `:`    | Integer       | `:1000\r\n`                      |
| `$`    | Bulk String   | `$5\r\nhello\r\n`                |
| `*`    | Array         | `*2\r\n$3\r\nGET\r\n$3\r\nfoo\r\n` |

All RESP messages are terminated with `\r\n` (carriage return + newline).

#### `DecodeResult` — The Return Type

```java
public record DecodeResult(Object value, int delta) {}
```

- A Java `record` (immutable data class) that holds two things:
  - `value` — the decoded Java object (a `String`, `Long`, `List`, etc.)
  - `delta` — the number of bytes consumed from the input buffer to produce this value. This is critical for parsing arrays, where you need to know where one element ends and the next begins.

#### `readLength()` — Parsing a Length Prefix

```java
// Parses "5\r\n" → returns [5, 3]  (length=5, consumed 3 bytes)
private static int[] readLength(byte[] data) {
    int pos = 0, length = 0;
    for (; pos < data.length; pos++) {
        byte b = data[pos];
        if (b < '0' || b > '9') {
            return new int[] { length, pos + 2 }; // +2 to skip \r\n
        }
        length = length * 10 + (b - '0');
    }
    return new int[] { 0, 0 };
}
```

- Reads ASCII digit bytes one by one, building up the integer value using `length = length * 10 + (b - '0')` (standard ASCII-to-int conversion).
- Stops when it hits a non-digit byte (the `\r` of `\r\n`).
- Returns `pos + 2` as the delta to skip past the `\r\n` separator.

#### `readSimpleString()` — Parsing `+OK\r\n`

```java
private static DecodeResult readSimpleString(byte[] data) {
    int pos = 1; // skip the leading '+'
    while (data[pos] != '\r') pos++;
    return new DecodeResult(new String(data, 1, pos - 1), pos + 2);
}
```

- Skips the `+` prefix, then scans forward until `\r`.
- Extracts the string between `+` and `\r` using `new String(data, 1, pos - 1)`.
- `delta = pos + 2` skips past the `\r\n`.

#### `readError()` — Parsing `-ERR message\r\n`

```java
private static DecodeResult readError(byte[] data) {
    int pos = 1; // skip '-'
    while (data[pos] != '\r') pos++;
    return new DecodeResult(new String(data, 1, pos - 1), pos + 2);
}
```

- Identical structure to `readSimpleString()` — the only difference is the leading `-` prefix. The error message text is returned as a plain `String`.

#### `readInt64()` — Parsing `:1000\r\n`

```java
private static DecodeResult readInt64(byte[] data) {
    int pos = 1; // skip ':'
    long value = 0;
    while (data[pos] != '\r') {
        value = value * 10 + (data[pos] - '0');
        pos++;
    }
    return new DecodeResult(value, pos + 2);
}
```

- Skips the `:` prefix, then reads ASCII digits into a `long` value.
- Returns the `long` as the decoded value.

#### `readBulkString()` — Parsing `$5\r\nhello\r\n`

```java
private static DecodeResult readBulkString(byte[] data) {
    int pos = 1; // skip '$'
    int[] lenResult = readLength(data, pos);
    int len = lenResult[0];   // e.g. 5
    int delta = lenResult[1]; // bytes consumed by "5\r\n" = 3
    pos += delta;
    String str = new String(data, pos, len); // read exactly 'len' bytes
    return new DecodeResult(str, pos + len + 2); // +2 for trailing \r\n
}
```

- Bulk strings carry an explicit byte length prefix (`$5` means "the next 5 bytes are the string").
- First calls `readLength()` to get the length and how many bytes the length prefix consumed.
- Then reads exactly `len` bytes as the string content.
- `delta = pos + len + 2` accounts for the string content itself plus the trailing `\r\n`.
- This is safer than simple-string parsing because it handles strings containing `\r\n` inside them.

#### `readArray()` — Parsing `*2\r\n$3\r\nGET\r\n$3\r\nfoo\r\n`

```java
private static DecodeResult readArray(byte[] data) {
    int pos = 1; // skip '*'
    int[] lenResult = readLength(data, pos);
    int count = lenResult[0]; // number of elements, e.g. 2
    int delta = lenResult[1];
    pos += delta;

    List<Object> elems = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
        byte[] slice = new byte[data.length - pos];
        System.arraycopy(data, pos, slice, 0, slice.length);
        DecodeResult result = decodeOne(slice); // recursively decode each element
        elems.add(result.value());
        pos += result.delta(); // advance by however many bytes this element consumed
    }
    return new DecodeResult(elems, pos);
}
```

- Arrays are the most important RESP type — every Redis command sent by a client is encoded as an array of bulk strings. For example, `GET foo` becomes `*2\r\n$3\r\nGET\r\n$3\r\nfoo\r\n`.
- Reads the element count from the `*N` prefix.
- Then loops `N` times, each iteration:
  1. Slices the remaining bytes starting at `pos`.
  2. Calls `decodeOne()` **recursively** to parse the next element (which could itself be any RESP type).
  3. Advances `pos` by `result.delta()` — the number of bytes that element consumed.
- This recursive design means arrays can contain nested arrays (RESP supports this).

#### `decodeOne()` — The Dispatcher

```java
public static DecodeResult decodeOne(byte[] data) {
    if (data.length == 0) throw new IllegalArgumentException("no data");
    return switch ((char) data[0]) {
        case '+' -> readSimpleString(data);
        case '-' -> readError(data);
        case ':' -> readInt64(data);
        case '$' -> readBulkString(data);
        case '*' -> readArray(data);
        default  -> new DecodeResult(null, 0);
    };
}
```

- The main dispatch function. Looks at the **first byte** of the data to determine the RESP type, then delegates to the appropriate reader.
- Uses Java's enhanced `switch` expression (Java 14+) for clean, concise dispatch.

#### `decode()` — The Public API

```java
public static Object decode(byte[] data) {
    if (data.length == 0) throw new IllegalArgumentException("no data");
    return decodeOne(data).value();
}
```

- The public-facing method. Calls `decodeOne()` and unwraps just the `value`, discarding the `delta` (since the caller doesn't need byte-offset tracking at the top level).

#### `decodeArrayString()` — Decoding Commands into String Tokens

```java
public static String[] decodeArrayString(byte[] data) {
    Object value = decode(data);
    List<?> list = (List<?>) value;
    String[] tokens = new String[list.size()];
    for (int i = 0; i < list.size(); i++) {
        tokens[i] = list.get(i).toString();
    }
    return tokens;
}
```

- A convenience method that decodes RESP data and casts the result into a `String[]`.
- Used by `AsyncTCPServer` and `SyncTCPServer` to turn the raw RESP array (e.g., `*1\r\n$4\r\nPING\r\n`) into `["PING"]`.
- In the Netty server, this is handled directly inside `RESPCommandDecoder` using `ByteBuf` instead.

---

### `RESPEncoder.java` — The RESP Protocol Encoder

While `RESPDecoder` handles **incoming** data (client → server), `RESPEncoder` handles **outgoing** responses (server → client).

```java
public class RESPEncoder {

    public static final byte[] RESP_NIL = "$-1\r\n".getBytes();

    public static byte[] encode(String value, boolean isSimple) {
        if (isSimple) {
            return ("+" + value + "\r\n").getBytes();
        }
        return ("$" + value.length() + "\r\n" + value + "\r\n").getBytes();
    }

    public static byte[] encode(long value) {
        return (":" + value + "\r\n").getBytes();
    }
}
```

**What it does:**

- `encode("PONG", true)` → `+PONG\r\n` (Simple String — used for fixed responses like PONG, OK)
- `encode("hello", false)` → `$5\r\nhello\r\n` (Bulk String — used for variable-length data)
- `encode(9L)` → `:9\r\n` (Integer — used for TTL remaining seconds, counts, etc.)
- `RESP_NIL` → `$-1\r\n` (Nil Bulk String — returned when a key does not exist)

The `isSimple` flag on the string overload determines which RESP type to use:
- **Simple String** (`+`): No length prefix, terminated by `\r\n`. Cannot contain `\r\n` in the value itself. Used for status replies like `OK` and `PONG`.
- **Bulk String** (`$`): Has an explicit length prefix, so it can safely contain any bytes including `\r\n`. Used for all variable-length data values.
- **Integer** (`:`): A signed 64-bit integer. Used for TTL, counts, boolean-style responses (0/1), and any numeric result.
- **Nil Bulk String** (`$-1`): The special sentinel that means "no value" — returned by `GET` when the key doesn't exist or has expired.

> **Note:** In `Eval.java`, responses are written directly as pre-computed `ByteBuf`s rather than going through `RESPEncoder`. `RESPEncoder` is kept as a utility for the older `SyncTCPServer` and `AsyncTCPServer` code paths that work with raw `byte[]` arrays.

---

### `RedisCmd.java` — The Command Object

```java
public class RedisCmd {
    private final String cmd;
    private final String[] args;

    public RedisCmd(String cmd, String[] args) { ... }
    public String getCmd() { return cmd; }
    public String[] getArgs() { return args; }
}
```

**What it does:**

- A simple data object that represents a parsed Redis command.
- `cmd` is always **UPPERCASE** (e.g., `"PING"`, `"GET"`, `"SET"`) — normalized during parsing so the evaluator can use simple string matching.
- `args` contains everything after the command name. For `PING hello`, `cmd = "PING"` and `args = ["hello"]`.

---

## Key Concepts

### Why Netty?

Netty is a battle-tested asynchronous networking framework used by gRPC, Cassandra, RocketMQ, and many others. It provides:

1. **Native epoll transport** (`netty-transport-native-epoll`): On Linux/WSL, Netty uses a pre-built JNI `.so` that calls `epoll_create1`, `epoll_ctl`, `epoll_wait` directly — the same syscalls Redis uses internally. This gives **edge-triggered epoll** (`EPOLLET`), which is faster than the level-triggered mode used by Java NIO's `Selector`.

2. **Pooled off-heap `ByteBuf` allocator**: Instead of allocating a new `byte[]` or `ByteBuffer` for every read/write (which creates GC pressure), Netty maintains a pool of pre-allocated off-heap memory regions. Buffers are checked out and returned to the pool — zero GC on the hot path.

3. **Automatic TCP fragmentation handling**: `ByteToMessageDecoder` buffers partial reads and retries — you never have to worry about a command arriving in multiple TCP segments.

4. **Pipeline architecture**: Each connection has a chain of handlers. Adding features (compression, TLS, rate limiting) is as simple as adding a new handler to the pipeline.

### Level-Triggered vs Edge-Triggered Epoll

Java NIO's `Selector` uses **level-triggered** epoll: the kernel notifies you repeatedly as long as a file descriptor has data available. If you don't read all the data in one call, you get notified again on the next `select()`.

Netty's native epoll uses **edge-triggered** epoll (`EPOLLET`): the kernel notifies you **only once** when the state changes (e.g., new data arrives). You must read until `EAGAIN` (no more data). This reduces the number of kernel-to-userspace transitions at high connection counts, which is why it's faster.

### Why Single-Threaded?

Real Redis is also single-threaded for command execution. The key insight is that for an in-memory database, the bottleneck is almost never CPU — it's I/O wait. A single thread with epoll can handle tens of thousands of concurrent connections because it never blocks waiting for I/O; it only runs when there's actual work to do.

### Why Port 7379?

Redis uses port `6379` by default. This project uses `7379` so you can run both side-by-side without conflicts.

### What is the OS Backlog?

When you bind a server socket with `SO_BACKLOG = 20000`, the OS maintains a queue of up to 20,000 fully-established TCP connections that your application hasn't called `accept()` on yet. This handles connection bursts — if 1,000 clients connect simultaneously, the OS queues them and Netty's boss thread drains the queue as fast as it can.

### Why RESP?

RESP is the protocol Redis clients use to talk to the server. By implementing a RESP decoder, this server can understand real Redis commands sent by any standard Redis client (`redis-cli`, Jedis, Lettuce, etc.) — making it a drop-in compatible server.

---

## What Happens When You Connect

Here's the full flow when you run `redis-cli -p 7379` and type `PING`:

```
redis-cli                       NettyTCPServer
  |                                  |
  |--- TCP SYN ─────────────────────>|  (OS accepts, queues in backlog)
  |<── TCP SYN-ACK ──────────────────|
  |--- TCP ACK ─────────────────────>|
  |                                  |  Single thread: accept() + register with epoll
  |                                  |  "client connected" printed
  |                                  |
  |--- "*1\r\n$4\r\nPING\r\n" ──────>|  epoll_wait() returns (data ready)
  |                                  |  Single thread reads bytes into ByteBuf
  |                                  |  RESPCommandDecoder.decode() → RedisCmd("PING", [])
  |                                  |  CommandHandler.channelRead0() called
  |                                  |  Eval.evalAndRespond() → "+PONG\r\n"
  |                                  |  ctx.writeAndFlush(Unpooled.wrappedBuffer(...))
  |<── "+PONG\r\n" ──────────────────|
  |                                  |
  |--- [connection close] ──────────>|  epoll notifies: channel closed
  |                                  |  CommandHandler.channelInactive() called
  |                                  |  "client disconnected" printed
  |                                  |  Channel deregistered from epoll
```
