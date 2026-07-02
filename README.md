# Redis Internals

Building a simplified Redis from scratch in Java to understand its internals — one layer at a time.

---



## Project Overview

This project is a ground-up implementation of a Redis-compatible in-memory database server in Java. The goal is to understand how Redis works internally — starting from raw TCP socket handling, the RESP wire protocol, and building up toward a full key-value store.

The current implementation (`jdis/`) is **Phase 3**: a high-performance, fully concurrent TCP server built on **Netty** with native **epoll** (Linux/WSL) that:
- Listens on a configurable host/port (default `0.0.0.0:7379`)
- Accepts and handles **thousands of concurrent clients** in a single thread using epoll I/O multiplexing
- Uses Netty's **native epoll transport** on Linux/WSL (edge-triggered, zero-copy, pooled off-heap buffers)
- Parses incoming data as RESP protocol (from `redis-cli`) or inline commands (from `telnet`)
- Handles TCP fragmentation automatically — partial reads are buffered and retried
- Evaluates commands and responds with proper RESP-encoded responses
- Supports `PING`, `SET` (with optional `EX` expiry), `GET`, `TTL`, `DEL`, `EXPIRE`, `INCR`, `INFO`, `CLIENT`, `LATENCY`, and `BGREWRITEAOF` with full Redis-compatible behavior
- Implements **Redis Object type/encoding** — values are tagged with type (STRING) and encoding (INT, EMBSTR, RAW) for type-safe operations
- Includes a RESP protocol decoder and encoder
- Maintains an in-memory key-value store with optional per-key TTL (time-to-live)
- **Evicts keys** when the store reaches its configured capacity using a pluggable eviction strategy (supports: `simple-first` and `allkeys-random`)
- Tracks **keyspace statistics** (key count per logical database) for monitoring via the `INFO` command
- Supports **command pipelining** — multiple commands sent in a single TCP segment are decoded, evaluated, and their responses flushed in a single write syscall

### Evolution of the server

| Phase | Server | Concurrency model |
|-------|--------|-------------------|
| 1 | `SyncTCPServer` | Single client at a time (blocking I/O) |
| 2 | `AsyncTCPServer` | Many clients, NIO `Selector` (level-triggered epoll) |
| 3 | `NettyTCPServer` ← **current** | Many clients, **1 thread**, Netty native epoll (edge-triggered, pooled buffers) — same model as Redis |

---

## Project Structure

```
jdis/
├── pom.xml                          # Maven build file (includes Netty dependencies)
├── bin/
│   ├── jdis.jar                  # Pre-built executable JAR
│   └── libs/                        # Netty and dependency JARs (auto-copied by Maven)
└── src/
    └── main/
        └── java/
            └── com/jdis/
                ├── Main.java                        # Entry point — parses CLI args, starts server
                ├── config/
                │   └── Config.java                  # Global config (HOST, PORT, KEYS_LIMIT, EVICTION_STRATEGY, AOF_FILE)
                 ├── core/
                 │   ├── Store.java                    # In-memory key-value store (Obj, put, get, del)
                 │   ├── KeyspaceStat.java             # Keyspace statistics tracker (key counts per DB)
                 │   ├── EvictionManager.java          # Eviction logic — simple-first + allkeys-random
                 │   ├── ExpiryManager.java            # Active expiry cron — background key deletion
                 │   ├── ObjTypeEncoding.java           # Redis Object type/encoding constants + utilities
                 │   ├── AOF.java                      # AOF persistence — dumps store to disk as RESP commands
                 │   ├── RESPDecoder.java              # RESP protocol parser (decode)
                 │   ├── RESPEncoder.java              # RESP protocol encoder (encode responses + encodeStringArray)
                 │   ├── RedisCmd.java                 # Command object (cmd + args)
                 │   └── Eval.java                    # Command evaluator — PING, SET, GET, TTL, DEL, EXPIRE, INCR, INFO, CLIENT, LATENCY, BGREWRITEAOF
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
# Navigate to the jdis directory
cd jdis

# Build the project (compiles, packages JAR, copies Netty libs to bin/libs/)
mvn package

# Run the JAR
java -jar bin/jdis.jar
```

---

### Option 2 — Run with custom host/port

```bash
java -jar bin/jdis.jar --host 127.0.0.1 --port 6379
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
cd jdis && mvn package -q && java -jar bin/jdis.jar
```

You should see output like:
```
starting a simple redis-compatible server
starting Netty TCP server on 0.0.0.0:7379 [transport: native epoll]
ready to accept connections on 0.0.0.0:7379
```

> When running on Windows (not WSL), the transport line will say `[transport: NIO]` — Netty automatically falls back to Java NIO since native epoll is Linux-only.

---

### Try DEL, EXPIRE

**Using `redis-cli`** (human-friendly output):
```
# DEL a single key
127.0.0.1:7379> SET name harsh
OK
127.0.0.1:7379> DEL name
(integer) 1
127.0.0.1:7379> GET name
(nil)

# DEL multiple keys at once — returns count of keys actually deleted
127.0.0.1:7379> SET a 1
OK
127.0.0.1:7379> SET b 2
OK
127.0.0.1:7379> DEL a b ghost
(integer) 2

# DEL a key that doesn't exist → 0
127.0.0.1:7379> DEL nonexistent
(integer) 0

# EXPIRE — set a TTL on an already-existing key (no EX needed at SET time)
127.0.0.1:7379> SET city tokyo
OK
127.0.0.1:7379> TTL city
(integer) -1
127.0.0.1:7379> EXPIRE city 10
(integer) 1
127.0.0.1:7379> TTL city
(integer) 9

# EXPIRE on a key that doesn't exist → 0
127.0.0.1:7379> EXPIRE ghost 30
(integer) 0

# EXPIRE wrong args
127.0.0.1:7379> EXPIRE city
(error) ERR wrong number of arguments for 'expire' command
127.0.0.1:7379> EXPIRE city notanumber
(error) ERR value is not an integer or out of range
```

**Using `telnet`** (raw RESP output):
```
DEL name
:1
DEL a b ghost
:2
EXPIRE city 10
:1
EXPIRE ghost 30
:0
```

The RESP types used:
- `:N` → RESP Integer — `DEL` returns the count of deleted keys; `EXPIRE` returns `1` (set) or `0` (key not found)

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

### Benchmark DEL and EXPIRE

```bash
# Benchmark DEL (from WSL)
redis-benchmark -n 100000 -t del -c 50 -P 1 -h 172.17.32.1 -p 7379

# Benchmark SET+EXPIRE pipeline
redis-benchmark -n 100000 -c 50 -P 1 -h 172.17.32.1 -p 7379 \
  -e --dbnum 0 --command "SET foo bar" --command "EXPIRE foo 60"
```

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
    public static int KEYS_LIMIT = 5;
    public static String EVICTION_STRATEGY = "simple-first";
}
```

**What it does:**

- Holds `public static` mutable fields that act as global configuration for the entire application.
- `HOST = "0.0.0.0"` means "bind to all available network interfaces" — the server will accept connections from any IP address on the machine, not just localhost.
- `PORT = 7379` is the default port. Redis uses `6379`; this project uses `7379` to avoid conflicts with a running Redis instance.
- `KEYS_LIMIT = 5` — the maximum number of keys allowed in the store before eviction kicks in. When `Store.put()` is called and the store already has `KEYS_LIMIT` keys, one key is evicted to make room. Set low (5) for easy testing; in production Redis this would be governed by `maxmemory`.
- `EVICTION_STRATEGY = "simple-first"` — which eviction algorithm to use. Currently only `simple-first` is supported (evicts an arbitrary key). This is the extension point for future strategies like LRU, LFU, random, etc.
- Because these are plain `static` fields (not `final`), `Main.setupFlags()` can overwrite them before the server starts.

---

### `NettyTCPServer.java` — The Netty Server

This is the heart of Phase 3. It sets up a Netty `ServerBootstrap` with a **single event loop thread** — exactly like Redis — and a pipeline of handlers per connection.

```java
public static void run() throws InterruptedException {
    boolean useEpoll = Epoll.isAvailable();

    // One thread handles everything: accept + all I/O + expiry cron — same as Redis
    EventLoopGroup group = useEpoll
            ? new EpollEventLoopGroup(1)
            : new NioEventLoopGroup(1);

    // Schedule the expiry cron on the event loop thread — not a separate thread
    group.scheduleAtFixedRate(
        ExpiryManager::deleteExpiredKeys,
        1, 1, TimeUnit.SECONDS
    );

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
        // Need at least 1 byte to determine the format
        if (in.readableBytes() < 1) return;

        // Peek at the first byte without consuming it
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
    // Mark the reader index so we can reset if we don't have a full frame yet
    in.markReaderIndex();

    // Read '*'
    in.readByte();

    // Read the array element count
    int count = readInteger(in);
    if (count < 0) {
        in.resetReaderIndex();
        return; // not enough data yet
    }

    List<String> tokens = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
        // Expect '$'
        if (in.readableBytes() < 1) {
            in.resetReaderIndex();
            return;
        }
        byte marker = in.readByte();
        if (marker != '$') {
            in.resetReaderIndex();
            return;
        }

        // Read bulk string length
        int len = readInteger(in);
        if (len < 0) {
            in.resetReaderIndex();
            return;
        }

        // Need len bytes + \r\n
        if (in.readableBytes() < len + 2) {
            in.resetReaderIndex();
            return;
        }

        String token = in.readCharSequence(len, StandardCharsets.UTF_8).toString();
        in.skipBytes(2); // skip \r\n
        tokens.add(token);
    }

    if (tokens.isEmpty()) return;

    String cmd   = tokens.get(0).toUpperCase();
    String[] args = tokens.subList(1, tokens.size()).toArray(new String[0]);
    out.add(new RedisCmd(cmd, args));
}
```

- `markReaderIndex()` / `resetReaderIndex()`: If at any point there aren't enough bytes to complete the frame, the reader position is reset to the start of the command. Netty will call `decode()` again when more bytes arrive.
- **Marker byte validation**: Before reading each bulk string, the code checks that the next byte is `$`. If not (malformed input), it resets and waits.
- **Three-level guard**: Each element requires three checks before proceeding: (1) at least 1 byte available for the `$` marker, (2) the length integer is fully available, (3) `len + 2` bytes available for the data + `\r\n`. Failing at any point resets to the start.
- `readCharSequence(len, UTF_8)`: Reads exactly `len` bytes as a string — safe for binary data and strings containing `\r\n`.

**Inline decoding (telnet support):**

```java
private void decodeInline(ByteBuf in, List<Object> out) {
    // Find the end of the line
    int lineEnd = findLineEnd(in);
    if (lineEnd < 0) return; // not a full line yet

    int lineLen = lineEnd - in.readerIndex();
    String line = in.readCharSequence(lineLen, StandardCharsets.UTF_8).toString().trim();

    // Skip \r\n or \n
    if (in.readableBytes() > 0 && in.getByte(in.readerIndex()) == '\r') in.readByte();
    if (in.readableBytes() > 0 && in.getByte(in.readerIndex()) == '\n') in.readByte();

    if (line.isEmpty()) return;

    String[] parts = line.split("\\s+");
    String cmd    = parts[0].toUpperCase();
    String[] args = new String[parts.length - 1];
    System.arraycopy(parts, 1, args, 0, args.length);
    out.add(new RedisCmd(cmd, args));
}
```

- Scans for a `\n` byte using `findLineEnd()`. If not found, returns without consuming anything — waits for more data.
- Reads the line up to (but not including) the `\n`, then manually skips `\r` and `\n` bytes.
- Splits the trimmed line by whitespace (`\\s+`) to get command + args.
- Uses `System.arraycopy` to efficiently extract args into a separate array.

**Helper methods:**

```java
private int readInteger(ByteBuf in) {
    int startIndex = in.readerIndex();
    int value = 0;
    while (in.readableBytes() > 0) {
        byte b = in.readByte();
        if (b == '\r') {
            if (in.readableBytes() < 1) {
                in.readerIndex(startIndex);
                return -1;
            }
            in.readByte(); // consume \n
            return value;
        }
        if (b >= '0' && b <= '9') {
            value = value * 10 + (b - '0');
        }
    }
    in.readerIndex(startIndex);
    return -1; // not enough data
}

private int findLineEnd(ByteBuf in) {
    int i = in.readerIndex();
    int end = in.writerIndex();
    while (i < end) {
        if (in.getByte(i) == '\n') return i;
        i++;
    }
    return -1;
}
```

- `readInteger()` reads ASCII digit bytes one by one, building the integer value. If it runs out of data before finding `\r\n`, it resets the reader index and returns `-1` to signal "not enough data".
- `findLineEnd()` scans the buffer for the next `\n` byte without consuming anything — used by `decodeInline()` to check if a full line is available.

**Note on `@Sharable`:**

`ByteToMessageDecoder` holds a per-connection cumulation buffer as instance state, so it **cannot** be marked `@Sharable`. A new `RESPCommandDecoder` instance is created for each connection by the `ChannelInitializer` in `NettyTCPServer`.

---

### `CommandHandler.java` — Netty Pipeline Stage 2

Receives fully-decoded `RedisCmd` objects, dispatches them to `Eval`, and implements **pipelining** by batching all responses and flushing them in a single write syscall.

```java
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
```

**What it does:**

- `SimpleChannelInboundHandler<RedisCmd>`: A Netty base class that only fires `channelRead0()` when the inbound message is of type `RedisCmd`. It also automatically releases the message's reference count after `channelRead0()` returns (Netty uses reference-counted buffers).
- **`channelRead0()`**: The hot path. Called once per fully-decoded command. Logs the command with the client's remote address and full arguments using `java.util.logging.Logger`, then delegates to `Eval.evalAndRespond()`. Note that `Eval` calls `ctx.write()` (without flush) — the response is buffered in Netty's outbound queue.
- **`channelReadComplete()`**: The pipelining flush point. Called **once** after all messages from a single `epoll_wait()` read event have been processed through `channelRead0()`. Calls `ctx.flush()` to send all buffered responses in a single write syscall. If a client pipelined 10 commands, `channelRead0()` fires 10 times (10× `ctx.write()`), then `channelReadComplete()` fires once (1× `ctx.flush()`) — resulting in 1 syscall instead of 10.
- `channelActive()` / `channelInactive()`: Lifecycle hooks — called when a client connects or disconnects. Used for connection logging.
- `exceptionCaught()`: Called when an unhandled exception occurs in the pipeline. Logs the error and closes the channel.
- `@Sharable`: Safe to mark because this handler holds no per-connection state — a single instance is shared across all connections.

**Pipelining mechanics:**

The split between `ctx.write()` and `ctx.flush()` is the key to pipelining:

| Method | What happens | Syscalls |
|--------|-------------|----------|
| `ctx.write(buf)` | Buffers the response in Netty's outbound queue | 0 |
| `ctx.flush()` | Flushes all queued writes to the network | 1 |

When a client sends 3 pipelined commands in one TCP segment:
1. `RESPCommandDecoder.decode()` is called in a loop → produces 3 `RedisCmd` objects
2. `channelRead0()` fires 3 times → each calls `Eval.evalAndRespond()` → 3× `ctx.write()` (buffered)
3. `channelReadComplete()` fires once → `ctx.flush()` → all 3 responses sent in 1 write syscall

This is the Netty equivalent of collecting all responses into a buffer and writing them all at once.

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

### `ExpiryManager.java` — Active Expiry

Redis uses a **two-pronged approach** to expiry. This class implements the second prong — the active cron.

#### The Two Strategies

| Strategy | When it runs | How it works | Limitation |
|----------|-------------|--------------|------------|
| **Lazy expiry** | On every `GET` / `TTL` read | `Store.get()` checks `expiresAt` and deletes if expired | Memory not freed until the key is touched again |
| **Active expiry** | Every 1 second (event loop) | `ExpiryManager` samples keys and deletes expired ones | Probabilistic — not every expired key is found immediately |

Together they guarantee: expired keys are **never returned to clients** (lazy), and **memory is eventually reclaimed** even for keys that are never read again (active).

#### Threading Model — Faithful to Redis

This is the key architectural decision. `ExpiryManager` contains **no threads of its own**. It is a pure logic class — `deleteExpiredKeys()` is just a method.

The scheduling is done in `NettyTCPServer` using `group.scheduleAtFixedRate()`:

```java
group.scheduleAtFixedRate(
    ExpiryManager::deleteExpiredKeys,
    1, 1, TimeUnit.SECONDS
);
```

`scheduleAtFixedRate()` on a Netty `EventLoopGroup` queues the task onto the **I/O thread's own task queue**. Netty drains this queue between `epoll_wait()` wakeups — so the cron fires on the **same thread** as `channelRead0()`, `evalSET()`, `evalGET()`, etc.

This is exactly how Redis does it:

```
Redis event loop:              Our Netty event loop:
  loop:                          loop:
    serverCron()  ← expiry          [drain task queue]  ← expiry fires here
    epoll_wait()                    epoll_wait()
    handle events                   handle events (channelRead0, etc.)
```

**Why this matters:**

Because the cron and all command handlers run on the same single thread, the store `HashMap` is only ever touched by one thread at a time — zero concurrency, zero races, no `ConcurrentHashMap` needed. This is the same reason Redis doesn't need locks on its hash table.

#### The Sampling Algorithm

The algorithm mirrors what Redis does:

```
loop:
  1. Walk the store, pick up to 20 keys that have an expiry set
  2. Delete any of those that have already expired
  3. expiredFraction = deletedCount / 20

  if expiredFraction >= 0.25:
      go back to step 1  ← store is "dirty", keep cleaning immediately
  else:
      return             ← store looks clean, wait for next scheduled tick
```

**Why sample instead of scanning everything?**

Scanning the entire store on every tick is O(n) — for a store with 10 million keys, that's 10 million comparisons every second, which would consume significant CPU. Redis's insight is:

- If a random sample of 20 keys has **< 25% expired**, the overall store is probably clean enough — stop.
- If **≥ 25%** of the sample is expired, the store is "hot with expiry" — loop immediately without waiting for the next tick.

This keeps CPU usage near zero when there are few expired keys, and ramps up automatically when there are many.

```java
public class ExpiryManager {

    static final int   SAMPLE_SIZE      = 20;
    static final float EXPIRY_THRESHOLD = 0.25f;

    // No threads here — scheduled on the Netty event loop in NettyTCPServer
    public static void deleteExpiredKeys() {
        while (true) {
            float fraction = expireSample();
            if (fraction < EXPIRY_THRESHOLD) break;
            // ≥25% expired → loop again immediately
        }
    }

    private static float expireSample() {
        // Step 1: snapshot all entries into an array so we can index randomly
        Object[] entries = Store.store.entrySet().toArray();  // O(n), shallow copy
        if (entries.length == 0) return 0f;

        long now = System.currentTimeMillis();
        List<String> toDelete = new ArrayList<>();
        int sampledWithExpiry = 0, expiredCount = 0;
        int attempts = 0, maxAttempts = entries.length * 2;

        // Step 2: pick random indices until we have SAMPLE_SIZE keys with expiry
        while (sampledWithExpiry < SAMPLE_SIZE && attempts < maxAttempts) {
            int idx = ThreadLocalRandom.current().nextInt(entries.length);
            Map.Entry<String, Store.Obj> entry = (Map.Entry<String, Store.Obj>) entries[idx];
            attempts++;

            if (entry.getValue().expiresAt == -1) continue;  // no expiry — skip
            sampledWithExpiry++;

            if (entry.getValue().expiresAt <= now) {
                toDelete.add(entry.getKey());
                expiredCount++;
            }
        }

        // Step 3: delete expired keys (two-pass to avoid ConcurrentModificationException)
        for (String key : toDelete) Store.store.remove(key);
        return (float) expiredCount / SAMPLE_SIZE;
    }
}
```

**Key design decisions:**

- **No thread in ExpiryManager** — the class is pure logic. Threading is the caller's responsibility (`NettyTCPServer`). This keeps concerns separated and makes the expiry logic independently testable.
- **True random sampling via `toArray()` + `ThreadLocalRandom`** — `entrySet().toArray()` snapshots all entries into an `Object[]` (O(n), shallow — only references copied, no values). Then `ThreadLocalRandom.current().nextInt(entries.length)` picks uniformly random indices. This gives true random sampling, unlike the original hash-order iteration.
- **`maxAttempts` guard** — if most keys have no expiry, random picks will keep landing on non-expiring keys. `maxAttempts = entries.length * 2` caps the loop so we don't spin forever on a store where almost nothing has a TTL.
- **Two-pass deletion** — keys to delete are collected into `toDelete` first, then removed. Removing entries while iterating throws `ConcurrentModificationException` — the two-pass approach avoids this.
- **Skips non-expiring keys** — only keys with `expiresAt != -1` count against `sampledWithExpiry`. This ensures the sample is representative of keys that *could* expire, not diluted by the majority of keys with no TTL.
- **`Store.store` package-private access** — `ExpiryManager` is in the same package (`com.jdis.core`) as `Store`, so it can access the `store` map directly. This avoids adding a public API to `Store` just for the cron.

#### Why not just shuffle?

You might think: collect all expiring keys into a list, `Collections.shuffle()`, take the first 20. That works but it's O(n) to collect *and* O(n) to shuffle — worse than `toArray()` + random index picks, which is O(n) to snapshot and O(SAMPLE_SIZE) to sample.

#### What Redis actually does (the ideal)

Redis maintains a **separate `expires` dict** alongside the main key-value dict. Every key that has a TTL is also stored in `expires`. Sampling then picks a random bucket from `expires` directly — O(1), no full scan needed. The Java equivalent would be a separate `List<String> expiringKeys` that is kept in sync with the store on every `SET ... EX` and `EXPIRE` call.

---

### `EvictionManager.java` — Key Eviction

When the store reaches its maximum capacity (`Config.KEYS_LIMIT`), we need to remove existing keys to make room for new ones. This is **eviction** — fundamentally different from expiry:

| Mechanism | Trigger | Purpose |
|-----------|---------|---------|
| **Expiry** | A key's TTL has elapsed | Remove stale data that the user explicitly marked as temporary |
| **Eviction** | Store is full (`store.size() >= KEYS_LIMIT`) | Free memory so new keys can be stored — even if existing keys haven't expired |

#### The Eviction Strategy — `simple-first`

```java
public class EvictionManager {

    /**
     * Evicts the first key found while iterating the store map.
     * Since HashMap iteration order is not guaranteed, this effectively
     * removes an arbitrary key.
     */
    private static void evictFirst() {
        for (String key : Store.store.keySet()) {
            Store.store.remove(key);
            return;
        }
    }

    /**
     * Triggers eviction based on the configured eviction strategy.
     */
    public static void evict() {
        switch (Config.EVICTION_STRATEGY) {
            case "simple-first":
                evictFirst();
                break;
            default:
                evictFirst();
                break;
        }
    }
}
```

**How it works:**

- `evictFirst()` iterates the store's `keySet()`, grabs the **first key** it encounters, removes it from the store, and immediately returns. Since `HashMap` does not guarantee iteration order, the "first" key is effectively arbitrary — it depends on the internal hash table bucket layout.
- `evict()` is a strategy dispatcher. It reads `Config.EVICTION_STRATEGY` and routes to the appropriate eviction method. Currently only `"simple-first"` is supported; the `switch` makes it trivial to add more strategies later.

**Where it's called — `Store.put()`:**

```java
public static void put(String key, Obj obj) {
    if (store.size() >= Config.KEYS_LIMIT) {
        EvictionManager.evict();
    }
    store.put(key, obj);
}
```

Before every write, `put()` checks whether the store has reached its capacity. If so, it calls `EvictionManager.evict()` to remove one key, then proceeds with the insert. This ensures the store **never exceeds** `KEYS_LIMIT` keys.

**Key design decisions:**

- **Eviction happens synchronously inside `put()`** — no background thread, no queue. This is the same model Redis uses: eviction is triggered inline during a write command, blocking the response until space is freed.
- **`Store.store.keySet()` access** — `EvictionManager` is in the same package (`com.jdis.core`) as `Store`, so it can access the package-private `store` map directly.
- **Strategy pattern via config** — the `switch` on `Config.EVICTION_STRATEGY` makes it easy to plug in new strategies (e.g., `"allkeys-random"`, `"allkeys-lru"`) without changing the call site in `Store.put()`.

#### What Redis actually does

Redis supports 8 eviction policies (e.g., `volatile-lru`, `allkeys-lfu`, `volatile-ttl`). The `simple-first` strategy here is a simplified starting point — it's essentially `allkeys-random` with a sample size of 1.

---

### Approximated LRU Eviction (`allkeys-lru`)

![](/diagrams/approxlrujdis.png)


Redis does **not** use a true LRU implementation because maintaining a doubly-linked list with prev/next pointers per key is too expensive in memory and requires constant reshuffling on every access. Instead, Redis uses an **Approximated LRU** algorithm that achieves near-optimal eviction with only **24 bits of extra storage per object**.

Our implementation adds the `allkeys-lru` eviction strategy, which is now the **default** strategy.

#### How It Works — The 24-Bit LRU Clock

Every stored object carries a `lastAccessedAt` field — the lower 24 bits of the current Unix time in seconds, captured when the key is read or written:

```java
// LRUClock.java
public static int getCurrentClock() {
    return (int) (System.currentTimeMillis() / 1000) & 0x00FFFFFF;
}
```

- **24 bits** covers 2²⁴ seconds ≈ **194 days** before wrapping around
- Updated on every `Store.get()` (read) and `Store.put()` (write)
- Saves 40 bits per object compared to a full 64-bit timestamp

#### Idle Time Calculation (with Wraparound)

The idle time tells us how long a key has been untouched. Since the clock wraps around every ~194 days, we handle two cases:

```java
// LRUClock.java
public static int getIdleTime(int lastAccessedAt) {
    int current = getCurrentClock();
    if (current >= lastAccessedAt) {
        return current - lastAccessedAt;        // normal case
    }
    // Clock wrapped around
    return (0x00FFFFFF - lastAccessedAt) + current;
}
```

**Example** (using a 5-bit clock for simplicity, max = 31):
- Key `K2` accessed at `t=24`, current time `t=6` (clock wrapped)
- `idle = (31 - 24) + 6 = 13 seconds`

Without wraparound handling, we'd incorrectly compute `6 - 24 = negative`, which would make a stale key look recently accessed.

#### The Eviction Pool

Rather than scanning all keys on every eviction, we maintain a **fixed-size pool of 16 eviction candidates**, sorted by idle time (highest first = best candidates for eviction):

```java
// EvictionPool.java
static final int MAX_POOL_SIZE = 16;

public void push(String key, int lastAccessedAt) {
    if (keyset.containsKey(key)) return;  // no duplicates

    if (pool.size() < MAX_POOL_SIZE) {
        // Pool has room — add and re-sort
        pool.add(item);
        pool.sort(byIdleTimeDescending);
    } else if (idleTime(key) > idleTime(worst)) {
        // Key is a better candidate than the worst in pool — replace it
        pool.remove(worst);
        pool.add(item);
        pool.sort(byIdleTimeDescending);
    }
}
```

**Key properties:**
- Pool size is fixed at 16 — bounded memory regardless of store size
- Keys are only added if they're better candidates than existing entries
- Over multiple eviction passes, the pool accumulates increasingly accurate candidates
- A `keyset` HashMap prevents duplicate entries

#### The Algorithm — `evictAllkeysLRU()`

```java
// EvictionManager.java
static void evictAllkeysLRU() {
    // Step 1: Sample 5 random keys and push them into the eviction pool
    populateEvictionPool();

    // Step 2: Pop the best candidates (highest idle time) and delete them
    int evictCount = (int) (Config.EVICTION_RATIO * Config.KEYS_LIMIT);
    for (int i = 0; i < evictCount && ePool.size() > 0; i++) {
        PoolItem item = ePool.pop();
        Store.del(item.key);
    }
}
```

**Step-by-step:**
1. **Sample**: Take 5 keys from the store (HashMap iteration = pseudo-random) and push them into the eviction pool
2. **Pool filters**: Only keys with higher idle time than existing pool entries get in
3. **Evict**: Pop the top candidates (most idle) from the pool and delete them
4. **Repeat**: On the next eviction trigger, the pool already has good candidates from previous passes — it accumulates knowledge over time

#### Why This Works

The approximation is surprisingly effective:

| Approach | Memory per key | Accuracy | Throughput impact |
|----------|---------------|----------|-------------------|
| True LRU (DLL) | +16 bytes (prev/next pointers) | Perfect | High (constant reshuffling) |
| **Approx LRU** | +4 bytes (24-bit clock in 32-bit int) | Near-perfect | Negligible |

Redis benchmarks show that with a sample size of 5, the approximated LRU performs very close to a true LRU — evicting nearly the same keys in the same order.

#### Integration with Store

```java
// Store.java — LRU clock updated on every access
public static void put(String key, Obj obj) {
    if (store.size() >= Config.KEYS_LIMIT) {
        EvictionManager.evict();  // triggers allkeys-lru
    }
    obj.lastAccessedAt = LRUClock.getCurrentClock();  // mark as just accessed
    store.put(key, obj);
}

public static Obj get(String key) {
    Obj obj = store.get(key);
    // ... lazy expiry check ...
    if (obj != null) {
        obj.lastAccessedAt = LRUClock.getCurrentClock();  // mark as just accessed
    }
    return obj;
}
```

Every `GET` and `SET` updates the LRU clock, so frequently accessed keys always have recent timestamps and are protected from eviction.

#### Manual LRU Trigger — The `LRU` Command

For testing and debugging, an `LRU` command is available that manually triggers the approximated LRU eviction:

```
127.0.0.1:7379> LRU
OK
```

This runs `evictAllkeysLRU()` immediately, regardless of whether the store has reached capacity.

#### Files Added/Modified

| File | Change |
|------|--------|
| `LRUClock.java` | **New** — 24-bit clock + idle time computation with wraparound |
| `EvictionPool.java` | **New** — Fixed-size sorted pool of eviction candidates |
| `Store.java` | Added `lastAccessedAt` field to `Obj`, updated on get/put |
| `EvictionManager.java` | Added `allkeys-lru` strategy with pool-based eviction |
| `Config.java` | Default strategy changed from `allkeys-random` to `allkeys-lru` |
| `Eval.java` | Added `LRU` command for manual eviction trigger |

---

### Command Pipelining

Pipelining is a Redis performance optimization where a client sends **multiple commands at once** without waiting for individual responses. The server processes all commands in order and returns all responses in a **single network write** — dramatically reducing round-trip latency.

#### The Problem Without Pipelining

```
Client                Server
  |── PING ──────────>|
  |                   |  process PING
  |<── +PONG ─────── |
  |── SET k v ───────>|  ← wait for response before sending next
  |                   |  process SET
  |<── +OK ────────── |
  |── GET k ─────────>|
  |                   |  process GET
  |<── $1\r\nv ─────  |
```

Each command takes **1 full round-trip time (RTT)**. For 3 commands = 3 RTTs.

#### With Pipelining

```
Client                Server
  |── PING ──────────>|
  |── SET k v ───────>|  ← all sent in one TCP write, no waiting
  |── GET k ─────────>|
  |                   |  process PING → buffer "+PONG\r\n"
  |                   |  process SET  → buffer "+OK\r\n"
  |                   |  process GET  → buffer "$1\r\nv\r\n"
  |<── +PONG\r\n ──── |
  |    +OK\r\n        |  ← all responses in one TCP write
  |    $1\r\nv\r\n    |
```

All 3 commands take **1 RTT total**. 3× faster.

#### How Pipelining Works in Our Netty Server

The key insight: pipelining requires **no protocol changes**. The client simply concatenates multiple RESP commands in a single TCP segment. The server's job is to:
1. Decode all commands from the buffer
2. Evaluate each command
3. Send all responses in a single flush

In Netty, this is achieved with the `write()` / `flush()` split:

```java
// CommandHandler.java

@Override
protected void channelRead0(ChannelHandlerContext ctx, RedisCmd cmd) {
    Eval.evalAndRespond(cmd, ctx);  // calls ctx.write() — buffers, does NOT flush
}

@Override
public void channelReadComplete(ChannelHandlerContext ctx) {
    ctx.flush();  // flush ALL buffered responses in one syscall
}
```

**The flow when 3 pipelined commands arrive:**

```
1. TCP data arrives: "*1\r\n$4\r\nPING\r\n*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n*2\r\n$3\r\nGET\r\n$1\r\nk\r\n"

2. Netty calls RESPCommandDecoder.decode() in a loop:
   → decode() extracts PING command, adds to output list
   → decode() extracts SET k v command, adds to output list  
   → decode() extracts GET k command, adds to output list
   → no more complete frames → stop

3. Netty fires channelRead0() for each decoded message:
   → channelRead0(PING)   → evalPING()   → ctx.write("+PONG\r\n")     [buffered]
   → channelRead0(SET k v) → evalSET()   → ctx.write("+OK\r\n")       [buffered]
   → channelRead0(GET k)   → evalGET()   → ctx.write("$1\r\nv\r\n")   [buffered]

4. Netty fires channelReadComplete() ONCE:
   → ctx.flush()  → all 3 responses sent in 1 write() syscall
```

#### `ctx.write()` vs `ctx.writeAndFlush()`

| Method | What it does | Syscalls |
|--------|-------------|----------|
| `ctx.writeAndFlush(buf)` | Write buffer to channel AND flush to network immediately | 1 syscall per command |
| `ctx.write(buf)` | Write buffer to Netty's outbound queue (no syscall yet) | 0 |
| `ctx.flush()` | Flush all queued writes to network | 1 syscall total |

By using `ctx.write()` in `Eval` and `ctx.flush()` in `channelReadComplete()`, we ensure:
- **Single command** (no pipelining): `channelRead0()` → write, then `channelReadComplete()` → flush. Still 1 syscall — same as before.
- **3 pipelined commands**: 3× `channelRead0()` → 3 writes buffered, then 1× `channelReadComplete()` → 1 flush. Only 1 syscall instead of 3.

#### Pipelining Example (Testing with `printf` + `nc`)

```bash
# RESP-encoded: PING + SET k v + GET k — all in one TCP segment
$ (printf '*1\r\n$4\r\nPING\r\n*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n*2\r\n$3\r\nGET\r\n$1\r\nk\r\n') | nc localhost 7379

# Response (all at once):
+PONG
+OK
$1
v
```

#### Benchmarking Pipelining

```bash
# Without pipelining (1 command per round-trip)
redis-benchmark -n 100000 -t set -c 50 -P 1 -h localhost -p 7379

# With pipelining (16 commands per round-trip)
redis-benchmark -n 100000 -t set -c 50 -P 16 -h localhost -p 7379
```

The `-P 16` flag tells `redis-benchmark` to pipeline 16 commands per batch. You should see significantly higher throughput with pipelining enabled.



### `AOF.java` — Append-Only File Persistence

AOF (Append-Only File) is how Redis persists data to disk. Unlike RDB snapshots (which dump a binary point-in-time snapshot), AOF logs every write command in RESP format so the dataset can be reconstructed by replaying the file.

#### The Problem AOF Solves

Redis (and our jdis server) is an **in-memory** database. If the process crashes or the machine reboots, all data is lost. AOF solves this by writing commands to disk:

```
In-memory store:                 AOF file on disk:
  { name: "harsh" }               *3\r\n$3\r\nSET\r\n$4\r\nname\r\n$5\r\nharsh\r\n
  { city: "tokyo" }               *3\r\n$3\r\nSET\r\n$4\r\ncity\r\n$5\r\ntokyo\r\n
```

On startup, the server reads the AOF file and replays each command to rebuild the store — data is recovered.

#### How `BGREWRITEAOF` Works

The `BGREWRITEAOF` command triggers a **full rewrite** of the AOF file. Instead of appending incremental commands, it dumps the *current state* of every key as a single `SET` command. This is important for **compaction** — if a key was updated 1000 times, the rewritten AOF only contains the final value:

```text
Before rewrite:           After BGREWRITEAOF:
  SET counter 1             SET counter 1000
  SET counter 2
  SET counter 3
  ...
  SET counter 1000
```

#### The Code

```java
// AOF.java

public class AOF {

    /**
     * Writes a single key-value pair as a RESP-encoded SET command to the file.
     */
    private static void dumpKey(FileOutputStream fp, String key, Store.Obj obj) throws IOException {
        String[] tokens = {"SET", key, obj.value.toString()};
        byte[] encoded = RESPEncoder.encodeStringArray(tokens);
        fp.write(encoded);
    }

    /**
     * Rewrites the entire AOF file from scratch.
     */
    public static void dumpAllAOF() {
        try (FileOutputStream fp = new FileOutputStream(Config.AOF_FILE, false)) {
            for (Map.Entry<String, Store.Obj> entry : Store.store.entrySet()) {
                dumpKey(fp, entry.getKey(), entry.getValue());
            }
        } catch (IOException e) {
            logger.severe("error writing AOF file: " + e.getMessage());
        }
    }
}
```

**Step by step:**

1. **`dumpKey()`** — Takes a single key-value pair and serializes it as a RESP array: `*3\r\n$3\r\nSET\r\n$<keyLen>\r\n<key>\r\n$<valLen>\r\n<value>\r\n`. This is the exact same format a Redis client would send — meaning the AOF file can be replayed using `redis-cli --pipe`.

2. **`dumpAllAOF()`** — Opens (or creates) the AOF file, iterates every key in the store, and calls `dumpKey()` for each. Uses `try-with-resources` so the file is always closed properly, even if an exception occurs.

3. **`Config.AOF_FILE = "./jdis-master.aof"`** — The file path is configurable. By default, it writes to the current directory.

#### The RESP Encoding for AOF

The new `RESPEncoder.encodeStringArray()` method serializes a command (string array) into RESP format:

```java
// RESPEncoder.java

public static byte[] encodeStringArray(String[] values) {
    StringBuilder sb = new StringBuilder();
    sb.append('*').append(values.length).append("\r\n");
    for (String v : values) {
        sb.append('$').append(v.length()).append("\r\n");
        sb.append(v).append("\r\n");
    }
    return sb.toString().getBytes();
}
```

For `{"SET", "name", "harsh"}`, this produces:
```
*3\r\n          ← array of 3 elements
$3\r\nSET\r\n   ← bulk string "SET" (length 3)
$4\r\nname\r\n  ← bulk string "name" (length 4)
$5\r\nharsh\r\n ← bulk string "harsh" (length 5)
```

#### The `BGREWRITEAOF` Command in Eval

```java
// Eval.java

private static void evalBGREWRITEAOF(String[] args, ChannelHandlerContext ctx) {
    AOF.dumpAllAOF();
    ctx.write(OK_RESPONSE.duplicate());
}
```

Simple — calls the AOF dump and responds `+OK`. The command is registered in the dispatch switch:

```java
case "BGREWRITEAOF":
    evalBGREWRITEAOF(cmd.getArgs(), ctx);
    break;
```

#### Trying BGREWRITEAOF

```bash
# Start the server
java -jar bin/jdis.jar

# In another terminal, connect and add some data
redis-cli -p 7379
127.0.0.1:7379> SET name harsh
OK
127.0.0.1:7379> SET city tokyo
OK
127.0.0.1:7379> SET lang java
OK
127.0.0.1:7379> BGREWRITEAOF
OK
```

Now check the AOF file:
```bash
cat jdis-master.aof
# Output (raw RESP):
# *3
# $3
# SET
# $4
# name
# $5
# harsh
# *3
# $3
# SET
# $4
# city
# $5
# tokyo
# *3
# $3
# SET
# $4
# lang
# $4
# java
```

Each key is serialized as a complete `SET` command in RESP format — ready to be replayed to restore the dataset.



#### Current Limitations (TODOs)

1. **No expiration support** — keys with TTL are dumped without their expiry. On replay, all keys would be permanent. Fix: also emit `EXPIRE key <remainingSeconds>` after each SET.
2. **Only supports string values** — future data structures (lists, sets, hashes) would need their own serialization commands (`RPUSH`, `SADD`, `HSET`).
3. **Synchronous execution** — the current implementation blocks the event loop while writing. In production Redis, `BGREWRITEAOF` forks a child process. Fix: run `dumpAllAOF()` in a separate thread.
4. **No incremental AOF** — currently only full rewrites are supported. A complete implementation would also append every write command to the AOF in real-time, and periodically compact with a rewrite.

---

### `ObjTypeEncoding.java` — Redis Object Type and Encoding

In Redis, every stored value isn't just raw bytes — it's wrapped in a **Redis Object** that carries metadata about *what kind of value it is* and *how it's stored in memory*. This enables type-safe operations: `INCR` only works on integer-encoded strings, not on lists or raw strings like `"hello"`.

#### The Redis Object in C

```c
struct redisObject {
    unsigned type: 4;       // STRING, LIST, SET, HASH, etc.
    unsigned encoding: 4;   // INT, RAW, EMBSTR, ZIPLIST, etc.
    unsigned lru: 24;       // last access time (for eviction)
    int refcount;           // reference counting for memory management
    void *ptr;              // pointer to the actual data
};
```

The **type** (4 bits) + **encoding** (4 bits) are packed into a single byte. Our Java implementation does the same:

#### Type and Encoding Constants

```java
// ObjTypeEncoding.java

// Types (upper 4 bits)
public static final byte OBJ_TYPE_STRING = (byte) (0 << 4);  // 0x00

// Encodings (lower 4 bits)
public static final byte OBJ_ENCODING_RAW    = 0;  // long string (> 44 bytes)
public static final byte OBJ_ENCODING_INT    = 1;  // parseable 64-bit integer
public static final byte OBJ_ENCODING_EMBSTR = 8;  // short string (≤ 44 bytes)
```

The type and encoding are OR'd together into a single byte:
- `"42"` → `OBJ_TYPE_STRING | OBJ_ENCODING_INT` = `0x00 | 0x01` = `0x01`
- `"hello"` → `OBJ_TYPE_STRING | OBJ_ENCODING_EMBSTR` = `0x00 | 0x08` = `0x08`
- `"a very long string..."` → `OBJ_TYPE_STRING | OBJ_ENCODING_RAW` = `0x00 | 0x00` = `0x00`

#### `deduceTypeEncoding()` — Automatic Encoding Detection

```java
public static byte[] deduceTypeEncoding(String value) {
    byte oType = OBJ_TYPE_STRING;

    // Try to parse as integer
    try {
        Long.parseLong(value);
        return new byte[]{oType, OBJ_ENCODING_INT};
    } catch (NumberFormatException ignored) {}

    // Short string → embedded string encoding
    if (value.length() <= 44) {
        return new byte[]{oType, OBJ_ENCODING_EMBSTR};
    }

    // Long string → raw encoding
    return new byte[]{oType, OBJ_ENCODING_RAW};
}
```

This is called by `evalSET()` every time a value is stored. The encoding is determined **once at write time** and stored with the object — subsequent reads (like `INCR`) check the encoding to decide if the operation is valid.

**Why 44 bytes?** In Redis, strings ≤ 44 bytes use `EMBSTR` encoding where the string data is allocated in the same memory block as the `redisObject` struct itself (one `malloc` instead of two). This saves a pointer dereference and reduces memory fragmentation.

#### Type/Encoding Assertion Utilities

```java
public static String assertType(byte typeEncoding, byte expectedType) {
    if (getType(typeEncoding) != expectedType) {
        return "WRONGTYPE Operation against a key holding the wrong kind of value";
    }
    return null;  // OK
}

public static String assertEncoding(byte typeEncoding, byte expectedEncoding) {
    if (getEncoding(typeEncoding) != expectedEncoding) {
        return "ERR value is not an integer or out of range";
    }
    return null;  // OK
}
```

These return `null` on success or an error message string on failure. Used by `INCR` to validate that the value is a STRING type with INT encoding before attempting arithmetic.

#### Updated `Store.Obj`

```java
public static class Obj {
    public Object value;         // mutable so INCR can update it in-place
    public byte typeEncoding;    // type (upper 4 bits) | encoding (lower 4 bits)
    public long expiresAt;       // -1 = no expiry
}
```

The `value` field is now mutable (not `final`) — `INCR` needs to update the value in-place without creating a new `Obj`.

---

### `INCR` Command — Atomic Integer Increment

The `INCR` command atomically increments the integer value stored at a key by one. If the key doesn't exist, it's created with value `0` before incrementing.

#### Trying INCR

```
127.0.0.1:7379> SET counter 10
OK
127.0.0.1:7379> INCR counter
(integer) 11
127.0.0.1:7379> INCR counter
(integer) 12
127.0.0.1:7379> GET counter
"12"

# INCR on a non-existent key → creates it at 0, then increments to 1
127.0.0.1:7379> INCR newkey
(integer) 1
127.0.0.1:7379> GET newkey
"1"

# INCR on a non-integer string → error
127.0.0.1:7379> SET name hello
OK
127.0.0.1:7379> INCR name
(error) ERR value is not an integer or out of range
```

#### The Implementation

```java
private static void evalINCR(String[] args, ChannelHandlerContext ctx) {
    if (args.length != 1) {
        ctx.write(ERR_INCR_ARGS.duplicate());
        return;
    }

    String key = args[0];
    Store.Obj obj = Store.get(key);

    // If key doesn't exist, create it with value "0" and INT encoding
    if (obj == null) {
        obj = Store.newObj("0", -1, OBJ_TYPE_STRING, OBJ_ENCODING_INT);
        Store.put(key, obj);
    }

    // Assert that the object is a STRING type
    String typeErr = ObjTypeEncoding.assertType(obj.typeEncoding, OBJ_TYPE_STRING);
    if (typeErr != null) { writeError(ctx, typeErr); return; }

    // Assert that the encoding is INT
    String encErr = ObjTypeEncoding.assertEncoding(obj.typeEncoding, OBJ_ENCODING_INT);
    if (encErr != null) { writeError(ctx, encErr); return; }

    // Parse, increment, store back
    long i = Long.parseLong(obj.value.toString());
    i++;
    obj.value = String.valueOf(i);

    // Return the new value as a RESP integer
    ByteBuf buf = ctx.alloc().buffer(24);
    buf.writeByte(':');
    writeAsciiLong(buf, i);
    buf.writeByte('\r'); buf.writeByte('\n');
    ctx.write(buf);
}
```

**Step by step:**

1. **Key doesn't exist** → create a new `Obj` with value `"0"`, type `STRING`, encoding `INT`
2. **Type check** → must be a STRING (not a list, set, etc.)
3. **Encoding check** → must be INT encoding (the value is a parseable integer)
4. **Increment** → parse the string as `long`, add 1, store back as string
5. **Return** → the new value as a RESP integer (`:N\r\n`)

**Why store as String but return as Integer?**

In Redis, string values are always stored as strings internally (even integers like `"42"`). But `INCR` returns the result as a RESP **integer** (`:N\r\n`), not a bulk string (`$2\r\n12\r\n`). This matches Redis's behaviour — `INCR` is a numeric operation that returns a numeric response.

**Why mutate `obj.value` in-place?**

The object is already in the store's HashMap. By mutating `obj.value` directly (which is why `value` is not `final`), we avoid the overhead of creating a new `Obj` and calling `Store.put()` again. Since the server is single-threaded, there are no race conditions.

The logic packs type+encoding in a single byte, checks it before INCR, and mutates the value in-place.

---

### `Store.java` — Changes for DEL and EXPIRE

Two changes were made to `Store.java` to support the new commands.

#### 1. Lazy Expiry Moved into `get()`

```java
// BEFORE — no expiry check; callers (evalGET, evalTTL) had to check themselves
public static Obj get(String key) {
    return store.get(key);
}

// AFTER — expired keys are deleted on access and null is returned
public static Obj get(String key) {
    Obj obj = store.get(key);
    if (obj != null && obj.expiresAt != -1 && obj.expiresAt <= System.currentTimeMillis()) {
        store.remove(key);   // ← delete from memory right now
        return null;
    }
    return obj;
}
```

Previously, the expiry check was duplicated in both `evalGET` and `evalTTL`. Now it lives in one place — `Store.get()` — and every caller automatically gets lazy expiry for free. Any future command that calls `Store.get()` (e.g., `APPEND`, `INCR`) will also correctly handle expired keys without any extra code.

The `store.remove(key)` inside `get()` is the "lazy" part — the key is deleted from memory the moment it is first accessed after expiry. This reclaims memory even without the background cron, as long as the key is eventually read.

#### 2. `expiresAt` Made Mutable

```java
// BEFORE
public final long expiresAt;

// AFTER
public long expiresAt;  // mutable so EXPIRE can update it
```

The `EXPIRE` command needs to set a TTL on a key that **already exists** in the store. Rather than creating a new `Obj` (which would require copying the value), we mutate `expiresAt` in-place on the existing object. This is safe because `expiresAt` is the only field that changes — `value` remains `final`.

#### 3. New `del()` Method

```java
public static boolean del(String key) {
    return store.remove(key) != null;
}
```

A clean, single-responsibility deletion method. Returns `true` if the key existed and was removed, `false` if it wasn't there. The `DEL` command uses this return value to count how many keys were actually deleted.

---

### `Eval.java` — DEL and EXPIRE Handlers

#### `evalDEL()` — DEL key [key ...]

```java
private static void evalDEL(String[] args, ChannelHandlerContext ctx) {
    int countDeleted = 0;
    for (String key : args) {
        if (Store.del(key)) {
            countDeleted++;
        }
    }
    // Write ":N\r\n" — RESP integer
    ByteBuf buf = ctx.alloc().buffer(24);
    buf.writeByte(':');
    writeAsciiLong(buf, countDeleted);
    buf.writeByte('\r'); buf.writeByte('\n');
    ctx.writeAndFlush(buf);
}
```

**Behaviour:**
- Accepts **one or more keys**: `DEL key1 key2 key3`
- Iterates all provided keys, calls `Store.del()` on each
- Returns a **RESP integer** — the count of keys that actually existed and were deleted
- Keys that didn't exist are silently skipped — not an error
- Example: `DEL a b ghost` where only `a` and `b` exist → returns `:2\r\n`

**Why a pooled buffer instead of a static one?**

The response is dynamic — the count depends on how many keys existed. We can't pre-compute it. So we use `ctx.alloc().buffer(24)` to get a pooled buffer, write the integer into it, and flush. After the write completes, Netty returns the buffer to the pool — zero GC.

#### `evalEXPIRE()` — EXPIRE key seconds

```java
private static void evalEXPIRE(String[] args, ChannelHandlerContext ctx) {
    String key = args[0];
    long exDurationSec = Long.parseLong(args[1]);

    Store.Obj obj = Store.get(key);

    if (obj == null) {
        // Key doesn't exist → return 0
        ctx.writeAndFlush(staticBuf(":0\r\n").duplicate());
        return;
    }

    // Mutate the expiry on the live object in-place
    obj.expiresAt = System.currentTimeMillis() + exDurationSec * 1000;

    // Success → return 1
    ctx.writeAndFlush(staticBuf(":1\r\n").duplicate());
}
```

**Behaviour:**
- Takes exactly `key` + `seconds`: `EXPIRE mykey 30`
- Calls `Store.get(key)` — which now includes lazy expiry, so if the key has already expired it returns `null` and we respond with `:0\r\n`
- If the key exists, **mutates `obj.expiresAt`** in-place to `now + seconds * 1000ms`
- Returns `:1\r\n` (timeout was set) or `:0\r\n` (key not found)
- Works on keys that were set without any TTL (e.g., plain `SET foo bar`) — you can add a TTL after the fact

**The `obj.expiresAt = ...` mutation:**

This is why `expiresAt` was changed from `final` to mutable. The `Obj` is retrieved from the store by reference — mutating `obj.expiresAt` directly updates the object that is already stored in the `HashMap`. No need to call `Store.put()` again.

---

### The Full Expiry Picture

```
Client writes:  SET foo bar EX 10
                     ↓
              Store.put("foo", Obj{value="bar", expiresAt=now+10000})

10 seconds later...

Path A — Lazy (client reads the key):
  GET foo
    → Store.get("foo")
    → expiresAt <= now → store.remove("foo") → return null
    → evalGET sends $-1\r\n (nil)

Path B — Active (background cron, runs every ~1s):
  ExpiryManager.expireSample()
    → iterates store, finds "foo" with expiresAt <= now
    → Store.store.remove("foo")
    → key is gone from memory

Path C — EXPIRE (client sets TTL on existing key):
  SET city tokyo          → Obj{value="tokyo", expiresAt=-1}
  EXPIRE city 30
    → Store.get("city")   → obj is live
    → obj.expiresAt = now + 30000
    → return :1\r\n

Path D — DEL (client explicitly deletes):
  DEL foo
    → Store.del("foo")    → store.remove("foo") → true
    → return :1\r\n
```

Both lazy and active expiry guarantee the same invariant: **an expired key is never returned to a client**. The difference is only in when the memory is reclaimed:
- **Lazy**: reclaimed on the next read of that key
- **Active**: reclaimed within ~1 second by the background cron, even if the key is never read again

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

### `KeyspaceStat.java` — Keyspace Statistics Tracking

Redis tracks how many keys are stored in each logical database. This information is exposed via the `INFO keyspace` command and consumed by monitoring tools like Prometheus's `redis_exporter`.

```java
public class KeyspaceStat {

    private static final int NUM_DBS = 4;

    @SuppressWarnings("unchecked")
    private static final Map<String, Integer>[] stats = new Map[NUM_DBS];

    public static void incrementStat(int dbNum, String metric) {
        if (stats[dbNum] == null) stats[dbNum] = new HashMap<>();
        stats[dbNum].merge(metric, 1, Integer::sum);
    }

    public static void decrementStat(int dbNum, String metric) {
        if (stats[dbNum] == null) stats[dbNum] = new HashMap<>();
        stats[dbNum].merge(metric, -1, Integer::sum);
    }

    public static Map<String, Integer>[] getAllStats() { return stats; }
    public static int getNumDbs() { return NUM_DBS; }
}
```

**How it works:**

- Maintains an array of 4 `Map<String, Integer>` — one per logical database (one per DB, matching Redis's multi-DB model).
- Only `db0` is used in our single-database implementation.
- `incrementStat(0, "keys")` is called by `Store.put()` on every insert.
- `decrementStat(0, "keys")` is called by `Store.del()` and `EvictionManager` on every removal.
- Uses `Map.merge()` with `Integer::sum` for atomic increment/decrement — clean and concise.

**Why track stats separately from `store.size()`?**

You could just call `store.size()` in the INFO command. But the stat tracker:
1. Is extensible — can track `expires`, `avg_ttl`, and other metrics per-DB in the future
2. Matches Redis's architecture where stats are maintained as side-effect counters
3. Allows the eviction manager to use `Iterator.remove()` (which bypasses `Store.del()`) while still updating stats directly

---

### `EvictionManager.java` — The `allkeys-random` Strategy

The eviction manager now supports two strategies:

| Strategy | Config Value | Behaviour |
|----------|-------------|-----------|
| `simple-first` | `"simple-first"` | Evicts 1 arbitrary key |
| `allkeys-random` | `"allkeys-random"` | Evicts `EVICTION_RATIO × KEYS_LIMIT` keys (default: 40% of 100 = 40 keys) |

#### The `allkeys-random` Implementation

```java
private static void evictAllkeysRandom() {
    long evictCount = (long) (Config.EVICTION_RATIO * Config.KEYS_LIMIT);
    Iterator<String> it = Store.store.keySet().iterator();
    while (it.hasNext() && evictCount > 0) {
        it.next();
        it.remove();
        KeyspaceStat.decrementStat(0, "keys");
        evictCount--;
    }
}
```

**Key design decisions:**

- **Batch eviction** — removes 40 keys at once (configurable via `EVICTION_RATIO`). This prevents the "evict on every write" thrashing that occurs with `simple-first` when the store is near capacity.
- **Iterator-based removal** — uses `Iterator.remove()` instead of `Store.del()` to safely modify the `HashMap` during iteration. In Java, calling `map.remove(key)` while iterating with a for-each loop throws `ConcurrentModificationException`. The iterator's own `remove()` method is the safe way.
- **Pseudo-random** — `HashMap` iteration order depends on hash bucket positions. While not truly random, it's arbitrary and non-deterministic from the application's perspective — sufficient for the `allkeys-random` policy.
- **Stats kept in sync** — calls `KeyspaceStat.decrementStat()` for each evicted key since we bypass `Store.del()`.

**Why not use `Store.del()`?**

`Store.del()` calls `store.remove(key)` internally. If we called `Store.del()` while iterating `store.keySet()`, Java would throw `ConcurrentModificationException`. The iterator-based approach is the only safe way to remove elements during iteration in Java.

#### Updated `Config.java`

```java
public class Config {
    public static String HOST = "0.0.0.0";
    public static int PORT = 7379;
    public static int KEYS_LIMIT = 100;
    public static double EVICTION_RATIO = 0.40;          // evict 40% of capacity
    public static String EVICTION_STRATEGY = "allkeys-random";
    public static String AOF_FILE = "./jdis-master.aof";
}
```

- `KEYS_LIMIT = 100` — raised from 5 to a more realistic value
- `EVICTION_RATIO = 0.40` — when eviction triggers, free up 40% of capacity (40 keys)
- `EVICTION_STRATEGY = "allkeys-random"` — the default is now batch random eviction

---

### `INFO`, `CLIENT`, `LATENCY` Commands — Monitoring Compatibility

These three commands enable compatibility with Redis monitoring tools (e.g., `redis_exporter` for Prometheus).

#### `evalINFO()` — INFO [section]

```java
private static void evalINFO(String[] args, ChannelHandlerContext ctx) {
    StringBuilder sb = new StringBuilder();
    sb.append("# Keyspace\r\n");

    Map<String, Integer>[] allStats = KeyspaceStat.getAllStats();
    for (int i = 0; i < KeyspaceStat.getNumDbs(); i++) {
        if (allStats[i] != null) {
            int keys = allStats[i].getOrDefault("keys", 0);
            sb.append(String.format("db%d:keys=%d,expires=0,avg_ttl=0\r\n", i, keys));
        }
    }

    // Return as RESP bulk string
    byte[] infoBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
    // ... write $<len>\r\n<info>\r\n
}
```

**What it returns:**

```
127.0.0.1:7379> INFO
# Keyspace
db0:keys=42,expires=0,avg_ttl=0
```

- Returns a RESP bulk string containing keyspace statistics.
- Format matches Redis's `INFO keyspace` output exactly — `db<N>:keys=<count>,expires=<count>,avg_ttl=<ms>`.
- Only databases with data are listed (empty databases are omitted).
- `expires` and `avg_ttl` are hardcoded to 0 for now (future enhancement: track actual TTL stats).

**Why this format matters:** Monitoring tools like `redis_exporter` parse this exact format to extract metrics. By matching Redis's output format, our server works with the entire Redis monitoring ecosystem out of the box.

#### `evalCLIENT()` — CLIENT [subcommand]

```java
private static void evalCLIENT(String[] args, ChannelHandlerContext ctx) {
    ctx.write(OK_RESPONSE.duplicate());
}
```

- Always returns `+OK\r\n` regardless of the subcommand.
- Exists purely for compatibility — `redis_exporter` sends `CLIENT SETNAME` before querying, and older Redis clients send `CLIENT LIST` during connection setup.
- Without this stub, the server would return an error and the monitoring tool would disconnect.

#### `evalLATENCY()` — LATENCY [subcommand]

```java
private static void evalLATENCY(String[] args, ChannelHandlerContext ctx) {
    ByteBuf buf = ctx.alloc().buffer(8);
    buf.writeByte('*');  // RESP array
    buf.writeByte('0');  // empty array
    buf.writeByte('\r');
    buf.writeByte('\n');
    ctx.write(buf);
}
```

- Returns an empty RESP array (`*0\r\n`).
- `redis_exporter` queries `LATENCY LATEST` to check for latency events. An empty array means "no latency events recorded" — a valid response.
- Without this stub, monitoring tools would log errors on every scrape.

#### Trying the Monitoring Commands

```
127.0.0.1:7379> SET a 1
OK
127.0.0.1:7379> SET b 2
OK
127.0.0.1:7379> SET c 3
OK
127.0.0.1:7379> INFO
# Keyspace
db0:keys=3,expires=0,avg_ttl=0

127.0.0.1:7379> DEL b
(integer) 1
127.0.0.1:7379> INFO
# Keyspace
db0:keys=2,expires=0,avg_ttl=0

127.0.0.1:7379> CLIENT SETNAME myconn
OK
127.0.0.1:7379> LATENCY LATEST
(empty array)
```

---

### Monitoring Through Prometheus

With INFO, CLIENT, and LATENCY implemented, you can now monitor the server using the standard Redis monitoring stack:

```
┌─────────────┐     INFO      ┌──────────────────┐    scrape    ┌────────────┐
│  jdis       │◄──────────────│  redis_exporter   │◄─────────────│ Prometheus │
│  (port 7379)│               │  (port 9121)      │              │            │
└─────────────┘               └──────────────────┘              └────────────┘
                                                                       │
                                                                       ▼
                                                                ┌────────────┐
                                                                │  Grafana   │
                                                                └────────────┘
```

**Setup:**

```bash
# 1. Start jdis
java -jar bin/jdis.jar

# 2. Start redis_exporter pointing at jdis
./redis_exporter -redis.addr redis://localhost:7379

# 3. Start Prometheus (configured to scrape redis_exporter on :9121)
./prometheus --web.enable-admin-api

# 4. To reset Prometheus data (if needed):
curl -X POST -g 'http://localhost:9090/api/v1/admin/tsdb/delete_series?match[]={instance="localhost:9121"}'
```

**What gets exported:**

- `redis_db_keys{db="db0"}` → number of keys in db0 (from `INFO keyspace`)
- `redis_up` → 1 if the server is reachable, 0 otherwise
- Various connection and latency metrics (stubbed for now)

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
