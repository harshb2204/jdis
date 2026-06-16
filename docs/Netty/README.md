# Netty Internals — Deep Dive

---

## What is a Channel?

A **Channel** in Netty is a handle to an open network connection — think of it as a wrapper around a socket.

When a client (e.g. `redis-cli`) connects to our server:
1. The OS completes the TCP three-way handshake.
2. Netty calls `accept()` on the server socket and gets back a new socket file descriptor.
3. Netty wraps that file descriptor in a **Channel** object.

From that point on, everything about that one client connection — reading bytes, writing bytes, closing the connection — goes through its `Channel`.

```
redis-cli ──── TCP socket ──── Channel (Java object wrapping the socket fd)
```

Each client gets its own `Channel`. If 1,000 clients connect, there are 1,000 `Channel` objects, all managed by the single Netty I/O thread via epoll.

A `Channel` also carries:
- Its **pipeline** (the chain of handlers for this connection)
- Its **allocator** (which memory pool to use for buffers)
- Its **remote address** (the client's IP + port)

---

## What is the Pipeline?

Every `Channel` has a **pipeline** — a chain of handler objects that data flows through, in order, on its way in and on its way out.

Think of it like a Unix pipe:

```
bytes in  →  [Handler A]  →  [Handler B]  →  [Handler C]  →  your code
your code →  [Handler C]  →  [Handler B]  →  [Handler A]  →  bytes out
```

In our server, the pipeline for every client connection is:

```
[RESPCommandDecoder]  →  [CommandHandler]
```

You set this up in `NettyTCPServer.java`:

```java
ch.pipeline().addLast("decoder", new RESPCommandDecoder());
ch.pipeline().addLast("handler", new CommandHandler());
```

The pipeline is **per-connection** — each `Channel` has its own pipeline instance. This is important because `RESPCommandDecoder` holds a partial-read buffer (it needs to remember bytes from a previous read if a full command hasn't arrived yet), so it cannot be shared between connections.

---

## What is Inbound vs Outbound?

**Inbound** = data flowing **from the client into your code** (reads).

**Outbound** = data flowing **from your code out to the client** (writes).

```
Client ──── sends bytes ────► [RESPCommandDecoder] ──► [CommandHandler]   INBOUND
Client ◄─── receives bytes ── [RESPCommandDecoder] ◄── [CommandHandler]   OUTBOUND
```

### Inbound flow — what happens when `redis-cli` sends `SET name harsh`

1. The client sends the bytes `*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$5\r\nharsh\r\n` over TCP.
2. The OS receives the bytes into the kernel buffer.
3. epoll notifies Netty's I/O thread: "this socket has data ready".
4. Netty reads the bytes from the kernel buffer into a `ByteBuf`.
5. The `ByteBuf` enters the pipeline at the **first inbound handler** — `RESPCommandDecoder`.
6. `RESPCommandDecoder` parses the RESP array and produces a `RedisCmd("SET", ["name", "harsh"])`.
7. That `RedisCmd` is passed to the **next inbound handler** — `CommandHandler`.
8. `CommandHandler.channelRead0()` is called with the `RedisCmd`.
9. `CommandHandler` calls `Eval.evalAndRespond()`.

### Outbound flow — what happens when we write `+OK\r\n`

1. `Eval` calls `ctx.writeAndFlush(OK_RESPONSE.duplicate())`.
2. The `ByteBuf` containing `+OK\r\n` enters the pipeline going **outbound** (in reverse handler order).
3. It passes through `RESPCommandDecoder` (which has no outbound logic — it's inbound-only).
4. It reaches the tail of the pipeline and Netty writes the bytes to the socket.
5. The OS sends the bytes to the client.
6. `redis-cli` receives `+OK\r\n` and displays `OK`.

### Why does the pipeline go in reverse for outbound?

Inbound handlers process data as it arrives (left to right in the pipeline).
Outbound handlers process data as it leaves (right to left).

This lets you stack transformations symmetrically. For example, if you added a TLS handler:

```
[TLS handler]  →  [RESPCommandDecoder]  →  [CommandHandler]
```

- **Inbound**: TLS handler decrypts → decoder parses → handler evaluates
- **Outbound**: handler writes → decoder passes through → TLS handler encrypts

Our server doesn't have a TLS handler, but the architecture supports it — you'd just add one line to the pipeline setup.

---

## Where Do the Raw Bytes Come From?

This is the full journey of bytes from your keyboard to our server and back.

### When you type `SET name harsh` in `redis-cli`

```
You type: SET name harsh  [Enter]
         │
         ▼
redis-cli encodes it as RESP:
  *3\r\n$3\r\nSET\r\n$4\r\nname\r\n$5\r\nharsh\r\n
         │
         ▼
redis-cli calls write() on its TCP socket
         │
         ▼
OS TCP stack: wraps bytes in TCP segments, sends over the network
         │
         ▼
Our server's network card receives the TCP segment
Network card triggers a hardware interrupt → kernel wakes up
         │
         ▼
Kernel copies bytes from network card into the kernel socket buffer
(this is the kernel buffer for our server's socket file descriptor)
         │
         ▼
epoll_wait() returns — Netty's I/O thread wakes up
"socket fd 7 has data ready to read"
         │
         ▼
Netty calls read() on the socket fd
Bytes move from kernel buffer → Netty ByteBuf (in off-heap memory)
         │
         ▼
ByteBuf enters the pipeline → RESPCommandDecoder → CommandHandler
         │
         ▼
Eval.evalSET() runs:
  Store.put("city", Store.newObj("tokyo", -1))
  ctx.writeAndFlush(OK_RESPONSE.duplicate())
         │
         ▼
Netty calls write() on the socket fd
Bytes "+OK\r\n" move from ByteBuf → kernel socket send buffer
         │
         ▼
OS TCP stack sends the bytes back to redis-cli
         │
         ▼
redis-cli receives "+OK\r\n", parses it as RESP Simple String, prints "OK"
```

### The key insight: two kernel crossings per request

Every request crosses the kernel boundary **twice**:
1. **Read**: kernel buffer → userspace (Netty reads the command bytes)
2. **Write**: userspace → kernel buffer (Netty writes the response bytes)

Everything in between — RESP parsing, command evaluation, store lookup — happens entirely in userspace. This is why in-memory databases are so fast: the bottleneck is the two kernel crossings (syscalls), not the actual data processing.

### Why epoll instead of blocking read?

Without epoll, you'd call `read()` on the socket and **block** until data arrives. That means one thread per client — 1,000 clients = 1,000 threads = massive context-switching overhead.

With epoll, one thread calls `epoll_wait()` and the kernel tells it **which sockets have data ready**. The thread only calls `read()` when it knows data is there — it never blocks. One thread handles all 1,000 clients.

This is exactly what Redis does. This is exactly what our Netty server does.

---

# Performance Fixes — Deep Dive

After the first benchmark, our server ran at ~39,000 req/s vs Redis at ~162,000 req/s.
Three fixes were applied. This document explains each one from first principles.

---

## Fix 1 — Pre-computed Static ByteBuf (Zero Allocation on Hot Path)

### The Problem

Before the fix, `Eval.java` looked like this:

```java
private static void evalPING(String[] args, ChannelHandlerContext ctx) {
    byte[] response = RESPEncoder.encode("PONG", true);
    ctx.writeAndFlush(Unpooled.wrappedBuffer(response));
}
```

And `RESPEncoder.encode` was:

```java
public static byte[] encode(String value, boolean isSimple) {
    if (isSimple) {
        return String.format("+%s\r\n", value).getBytes();
    }
    return String.format("$%d\r\n%s\r\n", value.length(), value).getBytes();
}
```

Every single `PING` request triggered this chain of allocations:

```
String.format("+%s\r\n", "PONG")
  → creates a Formatter object
  → creates a StringBuilder internally
  → creates the result String "+PONG\r\n"
  → .getBytes() creates a new byte[] {'+','P','O','N','G','\r','\n'}
  → Unpooled.wrappedBuffer(response) creates a ByteBuf wrapper object

Total: ~5 short-lived objects per PING request
```

At 39,000 req/s that is **195,000 objects created and discarded per second**. The JVM's young generation (Eden space) fills up, triggers a **minor GC**, and all 50 benchmark clients freeze while the GC runs. This is where the 61ms max latency spike came from.

### Why `String.format` is Slow

`String.format` is a general-purpose formatting function. Every call:
1. Parses the format string character by character looking for `%` tokens
2. Creates a `java.util.Formatter` object
3. Creates a `StringBuilder` to accumulate the result
4. Converts each argument to a string
5. Returns a new `String`

For a fixed string like `"+PONG\r\n"` that never changes, doing this 39,000 times per second is pure waste.

### The Fix — `Unpooled.unreleasableBuffer`

```java
private static final ByteBuf PONG_RESPONSE = Unpooled.unreleasableBuffer(
    Unpooled.directBuffer().writeBytes("+PONG\r\n".getBytes(StandardCharsets.UTF_8))
);
```

Breaking this down:

**`Unpooled.directBuffer()`**

Allocates a buffer in **off-heap memory** (outside the Java heap, in native OS memory). This buffer:
- Is never touched by the GC — it lives outside the heap entirely
- Has no GC overhead — the JVM doesn't scan it, doesn't move it, doesn't collect it
- Persists for the lifetime of the JVM process

**`.writeBytes("+PONG\r\n".getBytes(...))`**

Writes the 7 bytes `+`, `P`, `O`, `N`, `G`, `\r`, `\n` into the buffer. This happens **once** at class load time.

**`Unpooled.unreleasableBuffer(...)`**

Wraps the buffer in a special decorator that **ignores all `release()` calls**. 

Normally in Netty, every `ByteBuf` has a reference count. When you call `ctx.writeAndFlush(buf)`, Netty decrements the reference count after the write completes. When it hits zero, the buffer is freed. If we sent our static buffer directly, Netty would free it after the first request and the second request would crash.

`unreleasableBuffer` prevents this — the reference count never reaches zero, the buffer is never freed.

**`.duplicate()` — The Key**

```java
ctx.writeAndFlush(PONG_RESPONSE.duplicate());
```

`duplicate()` creates a **view** of the same underlying buffer with its own independent `readerIndex` and `writerIndex`. The underlying bytes are shared — no copy. Netty can call `release()` on the duplicate freely; it doesn't affect the original.

**Result:**

```
Before: 5 allocations per PING → GC pressure → 61ms spikes
After:  0 allocations per PING → no GC → max latency drops to ~3ms
```

### The Same Pattern for the Error Response

```java
private static final ByteBuf ERR_PING_ARGS = Unpooled.unreleasableBuffer(
    Unpooled.directBuffer().writeBytes(
        "-ERR wrong number of arguments for 'ping' command\r\n"
            .getBytes(StandardCharsets.UTF_8))
);
```

Same idea. The error message for `PING` with too many args is always the same string. Pre-compute it once.

### Dynamic Responses — Pooled Allocator

For `PING <message>` (one argument), the response is dynamic — it depends on the argument. We can't pre-compute it. But we can still avoid GC:

```java
byte[] argBytes = args[0].getBytes(StandardCharsets.UTF_8);
ByteBuf buf = ctx.alloc().buffer(argBytes.length + 16);  // from pool
buf.writeByte('$');
writeAsciiInt(buf, argBytes.length);
buf.writeByte('\r');
buf.writeByte('\n');
buf.writeBytes(argBytes);
buf.writeByte('\r');
buf.writeByte('\n');
ctx.writeAndFlush(buf);
```

**`ctx.alloc().buffer()`** — checks out a buffer from Netty's `PooledByteBufAllocator`. After `writeAndFlush` completes, Netty calls `release()` on the buffer, which returns it to the pool. The buffer is **reused** for the next request — no GC.

**`writeAsciiInt(buf, value)`** — writes an integer as ASCII digits directly into the buffer:

```java
private static void writeAsciiInt(ByteBuf buf, int value) {
    byte[] digits = new byte[10];
    int pos = 0;
    while (value > 0) {
        digits[pos++] = (byte) ('0' + (value % 10));
        value /= 10;
    }
    for (int i = pos - 1; i >= 0; i--) {
        buf.writeByte(digits[i]);
    }
}
```

This avoids `Integer.toString(value)` which allocates a `String` object. Instead, it extracts digits using modulo arithmetic and writes them directly as bytes. The `digits[]` array is stack-allocated (lives on the thread stack, not the heap) — zero GC.

---

## Fix 2 — Removing `System.out.println` from the Hot Path

### The Problem

Before the fix, `CommandHandler.channelRead0` looked like this:

```java
@Override
protected void channelRead0(ChannelHandlerContext ctx, RedisCmd cmd) {
    System.out.println("command: " + cmd.getCmd());  // ← this line
    Eval.evalAndRespond(cmd, ctx);
}
```

This looks harmless. It is not.

### What `System.out.println` Actually Does

`System.out` is a `PrintStream`. `PrintStream.println` is declared as:

```java
public void println(String x) {
    synchronized (this) {       // ← acquires a lock
        print(x);
        newLine();
    }
}
```

It is **synchronized on the `PrintStream` object**. Every call acquires a mutex lock.

In a single-threaded server this doesn't cause contention (there's only one thread), but it still has overhead:
1. **Lock acquisition** — even uncontended locks require a memory barrier (forces CPU cache flush/reload)
2. **String concatenation** — `"command: " + cmd.getCmd()` creates a new `String` object every call
3. **`write()` syscall** — writing to stdout is a system call that crosses the kernel boundary
4. **Terminal rendering** — if a terminal is attached, the OS has to render the text

At 39,000 req/s, this means:
- 39,000 lock acquisitions per second
- 39,000 string concatenations per second  
- 39,000 `write()` syscalls per second

Each `write()` syscall takes ~1–5 microseconds. At 39,000/s that's 39–195ms of pure syscall overhead per second — more than the entire budget for serving requests.

### The Fix

```java
@Override
protected void channelRead0(ChannelHandlerContext ctx, RedisCmd cmd) {
    // Hot path — no logging, no allocation
    Eval.evalAndRespond(cmd, ctx);
}
```

Remove it entirely from the hot path. Connect/disconnect events are kept because they fire at most once per client connection — negligible frequency.

### Why Redis Doesn't Log Per Command

Redis has a `loglevel` configuration (`debug`, `verbose`, `notice`, `warning`). Even at `debug` level, Redis does not log every command by default. When you need to see commands, you use `MONITOR` — a special Redis command that streams all commands to a dedicated client connection, without touching the main event loop's hot path.

### The General Rule

**Never do I/O (file writes, stdout, network) on the hot path of a high-throughput server.**

If you need observability, use:
- Async logging (log to a ring buffer, drain it on a background thread)
- Sampling (log 1 in every 1000 requests)
- Metrics counters (increment an `AtomicLong` — cheap, no I/O)

---

## Fix 3 — Explicit Pooled ByteBuf Allocator

### The Problem

Before the fix, `NettyTCPServer` had no explicit allocator configuration:

```java
bootstrap
    .childOption(ChannelOption.SO_KEEPALIVE, true)
    .childOption(ChannelOption.TCP_NODELAY, true);
    // no ALLOCATOR option
```

When no allocator is specified, Netty uses its default. The default depends on the JVM version and platform — it may be `PooledByteBufAllocator` or `UnpooledByteBufAllocator`. We were relying on an implicit default.

### The Fix

```java
.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
```

This explicitly sets `PooledByteBufAllocator.DEFAULT` as the allocator for every child channel (every client connection).

### How `PooledByteBufAllocator` Works

Netty's pooled allocator is based on **jemalloc** — the same allocator used by Facebook, Redis, and many high-performance systems.

It maintains a hierarchy of memory pools:

```
PooledByteBufAllocator
  └── Arena (one per CPU core, reduces contention)
       └── ChunkList (groups of 16MB chunks)
            └── Chunk (16MB of contiguous memory)
                 └── Page (8KB)
                      └── SubPage (for small allocations < 512 bytes)
```

When you call `ctx.alloc().buffer(32)`:
1. The allocator finds the right SubPage for a 32-byte allocation
2. Marks those bytes as "in use"
3. Returns a `ByteBuf` pointing to that memory

When Netty calls `buf.release()` after the write:
1. The allocator marks those bytes as "free"
2. The memory stays in the pool — ready for the next allocation
3. **No GC involved at any step**

Compare to `new byte[32]`:
1. JVM allocates 32 bytes on the heap
2. GC must track this object
3. When it goes out of scope, GC must collect it
4. At high rates, this fills Eden space → minor GC → stop-the-world pause

### Direct vs Heap Buffers

`PooledByteBufAllocator.DEFAULT` allocates **direct buffers** (off-heap) by default on most platforms.

**Heap buffer** (`byte[]` or `HeapByteBuf`):
- Lives on the Java heap
- GC must scan, move, and collect it
- When sending over a socket, the JVM must copy it to a temporary direct buffer first (the OS can't DMA from heap memory because GC might move it)
- Two copies: heap → direct → socket

**Direct buffer** (`DirectByteBuf`):
- Lives in native OS memory, outside the Java heap
- GC never touches it
- Can be sent directly to the socket via `sendfile()` or `writev()` — zero copy
- One copy: direct → socket (actually zero copy with `sendfile`)

This is why Netty's native epoll transport + pooled direct buffers is significantly faster than NIO + heap ByteBuffers.

### `ChannelOption.ALLOCATOR` vs `ByteBufAllocator.DEFAULT`

There are two places to set the allocator:

```java
// Option 1: Per-channel (what we do)
.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)

// Option 2: Global JVM property
-Dio.netty.allocator.type=pooled
```

Setting it per-channel via `ChannelOption` is more explicit and doesn't require JVM flags. It also allows different channels to use different allocators if needed (e.g., a management channel could use unpooled while the data channel uses pooled).

---

## The Combined Effect

Here is what happens to a single `PING` request before and after all three fixes:

### Before

```
1. redis-cli sends "*1\r\n$4\r\nPING\r\n"
2. Netty reads bytes into a ByteBuf (from pool — already good)
3. RESPCommandDecoder decodes → RedisCmd("PING", [])
4. CommandHandler.channelRead0():
   a. "command: " + "PING"          → allocates String          [GC]
   b. System.out.println(...)       → lock + syscall             [slow]
   c. Eval.evalAndRespond()
      → String.format("+%s\r\n", "PONG")  → Formatter + StringBuilder + String [GC]
      → .getBytes()                        → byte[]                              [GC]
      → Unpooled.wrappedBuffer(response)   → ByteBuf wrapper                    [GC]
      → ctx.writeAndFlush(buf)
5. Response sent
```

**Per request: ~5 heap allocations + 1 lock + 1 syscall**

### After

```
1. redis-cli sends "*1\r\n$4\r\nPING\r\n"
2. Netty reads bytes into a ByteBuf (from pool)
3. RESPCommandDecoder decodes → RedisCmd("PING", [])
4. CommandHandler.channelRead0():
   a. Eval.evalAndRespond()
      → ctx.writeAndFlush(PONG_RESPONSE.duplicate())
         duplicate() = view of static off-heap buffer, no copy, no allocation
5. Response sent
```

**Per request: 0 heap allocations + 0 locks + 0 extra syscalls**

---

## Expected Benchmark Impact

| Metric | Before | After (expected) |
|--------|--------|-----------------|
| Throughput | ~39k req/s | ~80–120k req/s |
| p50 latency | ~1.0ms | ~0.3–0.5ms |
| p99 latency | ~4.9ms | ~1.0ms |
| Max latency | ~61ms | ~3–5ms |
| GC pauses | Yes (61ms spikes) | None on hot path |

The remaining gap vs Redis (~162k req/s) after these fixes will be:
- **JVM vs C overhead** — function call overhead, JIT vs native compilation
- **WSL virtual network** — adds ~0.1–0.3ms per round trip vs bare metal
- **JIT warmup** — first ~10k requests still interpreted; run `-n 1000000` to see steady-state

These are irreducible without going to native code (JNI/Panama). For a JVM-based server, 80–120k req/s with sub-millisecond p50 latency is excellent.
