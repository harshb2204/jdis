# Redis Internals

Building a simplified Redis from scratch in Java(yeah its not optimal, ik) to understand its internals.



---

## Project Overview

This project is a ground-up implementation of a Redis-compatible in-memory database server in Java. The goal is to understand how Redis works internally  starting from raw TCP socket handling, the RESP wire protocol, and building up toward a full key-value store.

The current implementation (`jdis/`) is : a high-performance, fully concurrent TCP server built on **Netty** with native **epoll** (Linux/WSL) that:
- Listens on a configurable host/port (default `0.0.0.0:7379`)
- Accepts and handles **thousands of concurrent clients** in a single thread using epoll I/O multiplexing
- Uses Netty's **native epoll transport** on Linux/WSL (edge-triggered, zero-copy, pooled off-heap buffers)
- Parses incoming data as RESP protocol (from `redis-cli`) or inline commands (from `telnet`)
- Handles TCP fragmentation automatically partial reads are buffered and retried
- Evaluates commands and responds with proper RESP-encoded responses
- Supports `PING`, `SET` (with optional `EX` expiry), `GET`, `TTL`, `DEL`, `EXPIRE`, `INCR`, `INFO`, `CLIENT`, `LATENCY`, and `BGREWRITEAOF` with full Redis-compatible behavior
- Implements **Redis Object type/encoding** values are tagged with type (STRING) and encoding (INT, EMBSTR, RAW) for type-safe operations
- Includes a RESP protocol decoder and encoder
- Maintains an in-memory key-value store with optional per-key TTL (time-to-live)
- **Evicts keys** when the store reaches its configured capacity using a pluggable eviction strategy (supports: `simple-first`, `allkeys-random`, and `allkeys-lru`)
- Tracks **keyspace statistics** (key count per logical database) for monitoring via the `INFO` command
- Supports **command pipelining** multiple commands sent in a single TCP segment are decoded, evaluated, and their responses flushed in a single write syscall
- Persists data to disk via **AOF** (`BGREWRITEAOF` command)

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
│   ├── jdis.jar                     # Pre-built executable JAR
│   └── libs/                        # Netty and dependency JARs (auto-copied by Maven)
└── src/
    └── main/
        └── java/
            └── com/jdis/
                ├── Main.java                        # Entry point — parses CLI args, starts server
                ├── config/
                │   └── Config.java                  # Global config (HOST, PORT, KEYS_LIMIT, EVICTION_STRATEGY, AOF_FILE)
                ├── core/
                │   ├── Store.java                   # In-memory key-value store (Obj, put, get, del)
                │   ├── KeyspaceStat.java             # Keyspace statistics tracker (key counts per DB)
                │   ├── EvictionManager.java          # Eviction logic — simple-first, allkeys-random, allkeys-lru
                │   ├── EvictionPool.java             # Fixed-size sorted pool of LRU eviction candidates
                │   ├── ExpiryManager.java            # Active expiry cron — background key deletion
                │   ├── LRUClock.java                 # 24-bit LRU clock + idle time computation
                │   ├── ObjTypeEncoding.java          # Redis Object type/encoding constants + utilities
                │   ├── AOF.java                      # AOF persistence — dumps store to disk as RESP commands
                │   ├── RESPDecoder.java              # RESP protocol parser (decode)
                │   ├── RESPEncoder.java              # RESP protocol encoder (encode responses + encodeStringArray)
                │   ├── RedisCmd.java                 # Command object (cmd + args)
                │   └── Eval.java                     # Command evaluator — PING, SET, GET, TTL, DEL, EXPIRE, INCR, INFO, CLIENT, LATENCY, BGREWRITEAOF
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

> When running on Windows (not WSL), the transport line will say `[transport: NIO]` Netty automatically falls back to Java NIO since native epoll is Linux-only.

---

## Supported Commands

### PING

```
127.0.0.1:7379> PING
PONG
127.0.0.1:7379> PING hello
"hello"
127.0.0.1:7379> PING hello world
(error) ERR wrong number of arguments for 'ping' command
```

### SET, GET, TTL

```
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
127.0.0.1:7379> TTL city
(integer) -1

# TTL on a key that doesn't exist → -2
127.0.0.1:7379> TTL ghost
(integer) -2
```

### DEL, EXPIRE

```
# DEL a single key
127.0.0.1:7379> SET name harsh
OK
127.0.0.1:7379> DEL name
(integer) 1

# DEL multiple keys at once — returns count of keys actually deleted
127.0.0.1:7379> SET a 1
OK
127.0.0.1:7379> SET b 2
OK
127.0.0.1:7379> DEL a b ghost
(integer) 2

# EXPIRE — set a TTL on an already-existing key
127.0.0.1:7379> SET city tokyo
OK
127.0.0.1:7379> EXPIRE city 10
(integer) 1
127.0.0.1:7379> TTL city
(integer) 9

# EXPIRE on a key that doesn't exist → 0
127.0.0.1:7379> EXPIRE ghost 30
(integer) 0
```

### INCR

```
127.0.0.1:7379> SET counter 10
OK
127.0.0.1:7379> INCR counter
(integer) 11

# INCR on a non-existent key → creates it at 0, then increments to 1
127.0.0.1:7379> INCR newkey
(integer) 1

# INCR on a non-integer string → error
127.0.0.1:7379> SET name hello
OK
127.0.0.1:7379> INCR name
(error) ERR value is not an integer or out of range
```

### BGREWRITEAOF

```
127.0.0.1:7379> SET name harsh
OK
127.0.0.1:7379> SET city tokyo
OK
127.0.0.1:7379> BGREWRITEAOF
OK
```

This writes the current store to `./jdis-master.aof` as RESP-encoded `SET` commands.

### INFO, CLIENT, LATENCY

```
127.0.0.1:7379> SET a 1
OK
127.0.0.1:7379> SET b 2
OK
127.0.0.1:7379> INFO
# Keyspace
db0:keys=2,expires=0,avg_ttl=0

127.0.0.1:7379> CLIENT SETNAME myconn
OK
127.0.0.1:7379> LATENCY LATEST
(empty array)
```

---

## Benchmarking

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
| `-c 50` | Use 50 concurrent clients |
| `-P 1` | Disable pipelining (send 1 command, wait for response) |
| `-h` | Host address |
| `-p` | Port number |

**Benchmark with pipelining:**
```bash
# Without pipelining
redis-benchmark -n 100000 -t set -c 50 -P 1 -h localhost -p 7379

# With pipelining (16 commands per round-trip)
redis-benchmark -n 100000 -t set -c 50 -P 16 -h localhost -p 7379
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

- `setupFlags()` iterates over command-line arguments looking for `--host` and `--port` flags and overwrites the defaults in `Config`.
- `main()` calls `setupFlags()` first, then hands off control to `NettyTCPServer.run()` which blocks until the server is shut down.
- The `throws Exception` on `main` is intentional — if the server socket fails to bind (e.g., port already in use), the exception propagates and the JVM prints the error and exits.

---

### `Config.java` — Global Configuration

```java
public class Config {
    public static String HOST = "0.0.0.0";
    public static int PORT = 7379;
    public static int KEYS_LIMIT = 100;
    public static double EVICTION_RATIO = 0.40;
    public static String EVICTION_STRATEGY = "allkeys-lru";
    public static String AOF_FILE = "./jdis-master.aof";
}
```

- `HOST = "0.0.0.0"` — bind to all available network interfaces.
- `PORT = 7379` — uses 7379 to avoid conflicts with a running Redis instance (which uses 6379).
- `KEYS_LIMIT = 100` — maximum number of keys before eviction kicks in.
- `EVICTION_RATIO = 0.40` — when eviction triggers, free up 40% of capacity.
- `EVICTION_STRATEGY = "allkeys-lru"` — default eviction strategy.
- Because these are plain `static` fields (not `final`), `Main.setupFlags()` can overwrite them before the server starts.

---

### `NettyTCPServer.java` — The Netty Server

This is the heart of Phase 3. It sets up a Netty `ServerBootstrap` with a **single event loop thread** — exactly like Redis — and a pipeline of handlers per connection.

```java
public static void run() throws InterruptedException {
    boolean useEpoll = Epoll.isAvailable();

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
        .group(group)
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

- `Epoll.isAvailable()` — checks at runtime whether native epoll is available (Linux/WSL only). Falls back to NIO on Windows.
- A single `EventLoopGroup` with **1 thread** handles both accept and I/O — exactly like Redis's main thread.
- `scheduleAtFixedRate(ExpiryManager::deleteExpiredKeys, ...)` — queues the expiry cron onto the **same I/O thread**, so the store `HashMap` is only ever touched by one thread — zero races, no `ConcurrentHashMap` needed.
- `SO_BACKLOG = 20000` — OS queue for incoming connections not yet `accept()`-ed. Set high to handle connection bursts.
- `TCP_NODELAY = true` — disables Nagle's algorithm so each response is sent immediately.
- Each accepted connection gets its own pipeline: `RESPCommandDecoder` → `CommandHandler`.

---

### `RESPCommandDecoder.java` — Netty Pipeline Stage 1

Converts raw bytes arriving from the network into `RedisCmd` objects. Extends `ByteToMessageDecoder` which maintains a **cumulation buffer** per connection — if a command arrives in multiple TCP segments, it buffers the partial data and retries `decode()` when more bytes arrive.

```java
protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
    if (in.readableBytes() < 1) return;
    byte firstByte = in.getByte(in.readerIndex());
    if (firstByte == '*') {
        decodeRESPArray(in, out);   // redis-cli sends RESP arrays
    } else {
        decodeInline(in, out);      // telnet sends plain text
    }
}
```

**RESP array decoding** uses `markReaderIndex()` / `resetReaderIndex()` — if at any point there aren't enough bytes to complete the frame, the reader position is reset to the start of the command and Netty will call `decode()` again when more data arrives.

**Inline decoding** (telnet support) scans for a `\n` byte, reads the line, and splits by whitespace.

A new `RESPCommandDecoder` instance is created per connection (it's stateful — holds a partial-read buffer). It cannot be `@Sharable`.

---

### `CommandHandler.java` — Netty Pipeline Stage 2

Receives fully-decoded `RedisCmd` objects, dispatches them to `Eval`, and implements **pipelining** by batching all responses and flushing them in a single write syscall.

```java
@ChannelHandler.Sharable
public class CommandHandler extends SimpleChannelInboundHandler<RedisCmd> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RedisCmd cmd) {
        // ctx.write() — buffers response, does NOT flush yet
        Eval.evalAndRespond(cmd, ctx);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();  // flush ALL buffered responses in one syscall
    }
}
```

- `channelRead0()` fires once per decoded command. Delegates to `Eval.evalAndRespond()` which calls `ctx.write()` (no flush).
- `channelReadComplete()` fires **once** after all messages from a single `epoll_wait()` read event are processed. Calls `ctx.flush()` — all buffered responses sent in 1 syscall.
- If a client pipelined 10 commands: 10× `ctx.write()` then 1× `ctx.flush()` = 1 syscall instead of 10.
- `@Sharable` — safe because this handler holds no per-connection state.

---

### `Store.java` — The In-Memory Key-Value Store

```java
public class Store {

    static final Map<String, Obj> store = new HashMap<>();

    public static class Obj {
        public Object value;
        public byte typeEncoding;    // type (upper 4 bits) | encoding (lower 4 bits)
        public long expiresAt;       // -1 = no expiry, else Unix ms timestamp
        public int lastAccessedAt;   // 24-bit LRU clock value
    }

    public static void put(String key, Obj obj) {
        if (store.size() >= Config.KEYS_LIMIT) {
            EvictionManager.evict();
        }
        obj.lastAccessedAt = LRUClock.getCurrentClock();
        store.put(key, obj);
        KeyspaceStat.incrementStat(0, "keys");
    }

    public static Obj get(String key) {
        Obj obj = store.get(key);
        if (obj != null && obj.expiresAt != -1 && obj.expiresAt <= System.currentTimeMillis()) {
            store.remove(key);   // lazy expiry — delete on access
            KeyspaceStat.decrementStat(0, "keys");
            return null;
        }
        if (obj != null) obj.lastAccessedAt = LRUClock.getCurrentClock();
        return obj;
    }

    public static boolean del(String key) {
        boolean removed = store.remove(key) != null;
        if (removed) KeyspaceStat.decrementStat(0, "keys");
        return removed;
    }
}
```

- Every stored value is wrapped in an `Obj` that carries `typeEncoding`, `expiresAt`, and `lastAccessedAt` metadata.
- `put()` checks capacity and triggers eviction before inserting. Updates the LRU clock on every write.
- `get()` implements **lazy expiry** — expired keys are deleted from memory the moment they are first accessed after expiry.
- `del()` returns `true` if the key existed and was removed — used by `DEL` to count deleted keys.

---

### `Eval.java` — The Command Evaluator

Evaluates `RedisCmd` objects and writes RESP responses back through the Netty pipeline.

**Static pre-computed responses** — every constant response is pre-allocated once at class load time as an unreleasable off-heap `ByteBuf`. On the hot path, `ctx.write(OK_RESPONSE.duplicate())` sends a view of the same buffer — zero allocation, zero GC.

```java
private static final ByteBuf PONG_RESPONSE = staticBuf("+PONG\r\n");
private static final ByteBuf OK_RESPONSE   = staticBuf("+OK\r\n");
private static final ByteBuf NIL_RESPONSE  = staticBuf("$-1\r\n");
private static final ByteBuf TTL_NO_KEY    = staticBuf(":-2\r\n");
private static final ByteBuf TTL_NO_EXPIRY = staticBuf(":-1\r\n");
```

**Dispatcher:**

```java
public static void evalAndRespond(RedisCmd cmd, ChannelHandlerContext ctx) {
    switch (cmd.getCmd()) {
        case "PING":         evalPING(cmd.getArgs(), ctx);         break;
        case "SET":          evalSET(cmd.getArgs(), ctx);          break;
        case "GET":          evalGET(cmd.getArgs(), ctx);          break;
        case "TTL":          evalTTL(cmd.getArgs(), ctx);          break;
        case "DEL":          evalDEL(cmd.getArgs(), ctx);          break;
        case "EXPIRE":       evalEXPIRE(cmd.getArgs(), ctx);       break;
        case "INCR":         evalINCR(cmd.getArgs(), ctx);         break;
        case "INFO":         evalINFO(cmd.getArgs(), ctx);         break;
        case "CLIENT":       evalCLIENT(cmd.getArgs(), ctx);       break;
        case "LATENCY":      evalLATENCY(cmd.getArgs(), ctx);      break;
        case "BGREWRITEAOF": evalBGREWRITEAOF(cmd.getArgs(), ctx); break;
        case "LRU":          evalLRU(cmd.getArgs(), ctx);          break;
        default:             evalPING(cmd.getArgs(), ctx);         break;
    }
}
```

Dynamic responses (e.g., GET value, TTL remaining seconds) are written into pooled buffers via `ctx.alloc().buffer()` — returned to the pool after the write, zero GC.

`writeAsciiLong()` converts integers to ASCII digits directly into the `ByteBuf` without calling `Long.toString()` — zero allocation.

---

### `ExpiryManager.java` — Active Expiry

Implements the active expiry cron. Contains **no threads** — it is pure logic scheduled on the Netty event loop in `NettyTCPServer`:

```java
group.scheduleAtFixedRate(
    ExpiryManager::deleteExpiredKeys,
    1, 1, TimeUnit.SECONDS
);
```

The algorithm mirrors Redis:

```
loop:
  1. Walk the store, pick up to 20 keys that have an expiry set
  2. Delete any of those that have already expired
  3. expiredFraction = deletedCount / 20

  if expiredFraction >= 0.25:
      go back to step 1  ← store is "dirty", keep cleaning
  else:
      return             ← store looks clean, wait for next tick
```

- Uses `toArray()` + `ThreadLocalRandom` for true random sampling.
- `maxAttempts` guard prevents spinning on a store where almost nothing has a TTL.
- Two-pass deletion (collect keys first, then remove) avoids `ConcurrentModificationException`.

---

### `EvictionManager.java` — Key Eviction

Triggered by `Store.put()` when `store.size() >= Config.KEYS_LIMIT`. Supports three strategies:

| Strategy | Config Value | Behaviour |
|----------|-------------|-----------|
| `simple-first` | `"simple-first"` | Evicts 1 arbitrary key |
| `allkeys-random` | `"allkeys-random"` | Evicts `EVICTION_RATIO × KEYS_LIMIT` keys using iterator-based removal |
| `allkeys-lru` | `"allkeys-lru"` | Evicts least recently used keys using the eviction pool |

**`allkeys-random`** uses `Iterator.remove()` to safely modify the `HashMap` during iteration (calling `map.remove(key)` in a for-each loop throws `ConcurrentModificationException`).

**`allkeys-lru`** samples 5 random keys, pushes them into the eviction pool, then pops the best candidates (highest idle time) and deletes them.

---

### `LRUClock.java` — 24-bit LRU Clock

```java
public static int getCurrentClock() {
    return (int) (System.currentTimeMillis() / 1000) & 0x00FFFFFF;
}

public static int getIdleTime(int lastAccessedAt) {
    int current = getCurrentClock();
    if (current >= lastAccessedAt) {
        return current - lastAccessedAt;
    }
    // Clock wrapped around
    return (0x00FFFFFF - lastAccessedAt) + current;
}
```

- 24 bits covers 2²⁴ seconds ≈ **194 days** before wrapping.
- Handles clock wraparound correctly — a key accessed at `t=24` with current time `t=6` (wrapped) has idle time `(31 - 24) + 6 = 13` (using a 5-bit clock for illustration).

---

### `EvictionPool.java` — LRU Eviction Pool

A fixed-size pool of 16 eviction candidates, sorted by idle time (highest first):

```java
public void push(String key, int lastAccessedAt) {
    if (keyset.containsKey(key)) return;  // no duplicates

    if (pool.size() < MAX_POOL_SIZE) {
        pool.add(item);
        pool.sort(byIdleTimeDescending);
    } else if (idleTime > idleTime(worst)) {
        // Better candidate than the worst in pool — replace it
        pool.remove(worst);
        pool.add(item);
        pool.sort(byIdleTimeDescending);
    }
}
```

- Pool size is fixed at 16 — bounded memory regardless of store size.
- Over multiple eviction passes, the pool accumulates increasingly accurate candidates.
- A `keyset` HashMap prevents duplicate entries.

---

### `AOF.java` — Append-Only File Persistence

`BGREWRITEAOF` triggers a full rewrite of the AOF file — dumps the current state of every key as a single `SET` command:

```java
public static void dumpAllAOF() {
    try (FileOutputStream fp = new FileOutputStream(Config.AOF_FILE, false)) {
        for (Map.Entry<String, Store.Obj> entry : Store.store.entrySet()) {
            dumpKey(fp, entry.getKey(), entry.getValue());
        }
    } catch (IOException e) {
        logger.severe("error writing AOF file: " + e.getMessage());
    }
}
```

Each key is serialized as a RESP array: `*3\r\n$3\r\nSET\r\n$<keyLen>\r\n<key>\r\n$<valLen>\r\n<value>\r\n` — the exact same format a Redis client would send, meaning the AOF file can be replayed using `redis-cli --pipe`.

**Current limitations:**
1. Keys with TTL are dumped without their expiry — on replay all keys would be permanent.
2. Only supports string values.
3. Synchronous execution — blocks the event loop while writing.
4. No incremental AOF — only full rewrites are supported.

---

### `ObjTypeEncoding.java` — Redis Object Type and Encoding

Every stored value carries a `typeEncoding` byte — type (upper 4 bits) OR'd with encoding (lower 4 bits):

```java
public static final byte OBJ_TYPE_STRING  = (byte) (0 << 4);  // 0x00
public static final byte OBJ_ENCODING_RAW    = 0;  // long string (> 44 bytes)
public static final byte OBJ_ENCODING_INT    = 1;  // parseable 64-bit integer
public static final byte OBJ_ENCODING_EMBSTR = 8;  // short string (≤ 44 bytes)
```

`deduceTypeEncoding()` is called by `evalSET()` on every write — tries `Long.parseLong()` first (INT), then checks length ≤ 44 (EMBSTR), otherwise RAW.

`assertEncoding()` is used by `INCR` to validate that the value is INT-encoded before attempting arithmetic — returns an error string on failure, `null` on success.

---

### `RESPDecoder.java` — The RESP Protocol Parser

Used by `SyncTCPServer` and `AsyncTCPServer` (the older server phases). Parses raw `byte[]` into Java objects.

```java
public record DecodeResult(Object value, int delta) {}

public static DecodeResult decodeOne(byte[] data) {
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

- `DecodeResult` carries both the decoded value and `delta` — the number of bytes consumed. This is critical for parsing arrays where you need to know where one element ends and the next begins.
- `readArray()` calls `decodeOne()` recursively — supports nested arrays.
- In the Netty server, RESP parsing is handled directly inside `RESPCommandDecoder` using `ByteBuf` instead.

---

### `RESPEncoder.java` — The RESP Protocol Encoder

```java
public static byte[] encode(String value, boolean isSimple) {
    if (isSimple) return ("+" + value + "\r\n").getBytes();
    return ("$" + value.length() + "\r\n" + value + "\r\n").getBytes();
}

public static byte[] encode(long value) {
    return (":" + value + "\r\n").getBytes();
}

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

- Used by `SyncTCPServer`, `AsyncTCPServer`, and `AOF.java`.
- In `Eval.java`, responses are written directly as pre-computed `ByteBuf`s rather than going through `RESPEncoder` — for zero-allocation performance on the hot path.

---

### `KeyspaceStat.java` — Keyspace Statistics

Tracks key counts per logical database for the `INFO` command and `redis_exporter` compatibility:

```java
public static void incrementStat(int dbNum, String metric) {
    if (stats[dbNum] == null) stats[dbNum] = new HashMap<>();
    stats[dbNum].merge(metric, 1, Integer::sum);
}
```

- `incrementStat(0, "keys")` called by `Store.put()` on every insert.
- `decrementStat(0, "keys")` called by `Store.del()` and `EvictionManager` on every removal.
- `evalINFO()` reads these stats to produce the `db0:keys=N,expires=0,avg_ttl=0` output.

---

## Monitoring with Prometheus

With `INFO`, `CLIENT`, and `LATENCY` implemented, the server works with the standard Redis monitoring stack:

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

```bash
# 1. Start jdis
java -jar bin/jdis.jar

# 2. Start redis_exporter pointing at jdis
./redis_exporter -redis.addr redis://localhost:7379

# 3. Start Prometheus (configured to scrape redis_exporter on :9121)
./prometheus --web.enable-admin-api
```

**What gets exported:**
- `redis_db_keys{db="db0"}` → number of keys in db0 (from `INFO keyspace`)
- `redis_up` → 1 if the server is reachable, 0 otherwise

---

## What Happens When You Connect

Full flow when you run `redis-cli -p 7379` and type `PING`:

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
  |                                  |  Eval.evalAndRespond() → ctx.write("+PONG\r\n")
  |                                  |  channelReadComplete() → ctx.flush()
  |<── "+PONG\r\n" ──────────────────|
  |                                  |
  |--- [connection close] ──────────>|  epoll notifies: channel closed
  |                                  |  CommandHandler.channelInactive() called
  |                                  |  "client disconnected" printed
  |                                  |  Channel deregistered from epoll
```
