# Redis Internals

Building a simplified Redis from scratch in Java to understand its internals — one layer at a time.

---



## Project Overview

This project is a ground-up implementation of a Redis-compatible in-memory database server in Java. The goal is to understand how Redis works internally — starting from raw TCP socket handling, the RESP wire protocol, and building up toward a full key-value store.

The current implementation (`inmemdb/`) is **Phase 1**: a synchronous, single-threaded TCP server that:
- Listens on a configurable host/port (default `0.0.0.0:7379`)
- Accepts client connections one at a time (blocking I/O)
- Reads raw bytes from the client
- Echoes the command back to the client
- Includes a RESP protocol decoder (ready for command parsing)

---

## Project Structure

```
inmemdb/
├── pom.xml                          # Maven build file
├── bin/
│   └── inmemdb.jar                  # Pre-built executable JAR
└── src/
    └── main/
        └── java/
            └── com/inmemdb/
                ├── Main.java                    # Entry point — parses CLI args, starts server
                ├── config/
                │   └── Config.java              # Global config (HOST, PORT)
                ├── core/
                │   └── RESPDecoder.java         # RESP protocol parser
                └── server/
                    └── SyncTCPServer.java       # Synchronous TCP server
```

---

## How to Run

### Prerequisites

- Java 17 or higher (required for `record` types and switch expressions used in `RESPDecoder.java`)
- Maven 3.x (only needed if you want to build from source)

---

### Option 1 — Run the pre-built JAR directly

A pre-built JAR is already included in `inmemdb/bin/`:

```bash
# Run with default host (0.0.0.0) and port (7379)
java -jar inmemdb/bin/inmemdb.jar

# Run with custom host and port
java -jar inmemdb/bin/inmemdb.jar --host 127.0.0.1 --port 6379
```

---

### Option 2 — Build from source and run

```bash
# Navigate to the inmemdb directory
cd inmemdb

# Build the project (compiles and packages into bin/inmemdb.jar)
mvn package

# Run the JAR
java -jar bin/inmemdb.jar
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



You should see output like:
```
starting a simple redis-compatible server
ready to accept connections on 0.0.0.0:7379
client connected with address: /172.17.x.x:XXXXX, client count: 1
```

Type anything in the telnet session and the server will echo it back.

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
        SyncTCPServer.run();
    }
}
```

**What it does:**

- `setupFlags()` iterates over the command-line arguments looking for `--host` and `--port` flags. If found, it overwrites the defaults in `Config`. This is a simple hand-rolled argument parser — no external library needed.
- `main()` calls `setupFlags()` first, then hands off control to `SyncTCPServer.run()` which never returns (it's an infinite loop).
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

### `SyncTCPServer.java` — The TCP Server

This is the heart of the server. It implements a classic **synchronous, single-threaded** TCP server loop.

```java
public static void run() throws IOException {
    ServerSocket serverSocket = new ServerSocket(Config.PORT, 50,
            java.net.InetAddress.getByName(Config.HOST));

    System.out.println("ready to accept connections on " + Config.HOST + ":" + Config.PORT);

    int clientCount = 0;

    while (true) {
        Socket client = serverSocket.accept();   // BLOCKS here until a client connects
        clientCount++;
        System.out.println("client connected: " + client.getRemoteSocketAddress()
                + ", client count: " + clientCount);

        while (true) {
            String command = readCommand(client); // BLOCKS here until data arrives
            if (command == null) {
                clientCount--;
                System.out.println("client disconnected: " + client.getRemoteSocketAddress()
                        + ", client count: " + clientCount);
                client.close();
                break;
            }
            respond(command, client);
        }
    }
}
```

**Breaking it down piece by piece:**

#### `ServerSocket` — The Listening Socket

```java
ServerSocket serverSocket = new ServerSocket(Config.PORT, 50,
        java.net.InetAddress.getByName(Config.HOST));
```

- `ServerSocket` is Java's abstraction over a TCP listening socket. It binds to a port and waits for incoming client connections.
- The **three arguments** are:
  1. `Config.PORT` — the port to bind to (`7379` by default).
  2. `50` — the **backlog**. This is the OS-level queue size for incoming connections that haven't been `accept()`-ed yet. If your code is busy processing a client and a second client connects, the OS holds it in this queue (up to 50 clients). This is why a second `telnet` session appears "connected" even before your code calls `accept()` — the OS accepted it at the kernel level.
  3. `InetAddress.getByName(Config.HOST)` — the network interface to bind to. `"0.0.0.0"` means all interfaces.

#### The Outer Loop — Accepting Clients

```java
while (true) {
    Socket client = serverSocket.accept(); // blocking
    ...
}
```

- `serverSocket.accept()` is a **blocking call** — the thread sleeps here until a client connects. When one does, it returns a `Socket` object representing that specific client connection.
- This is a **synchronous** server: it handles one client at a time. While talking to Client 1, Client 2 sits in the OS backlog queue. Client 2 only gets served after Client 1 disconnects.
- This is the key limitation of this phase — it's the simplest possible design, and the starting point before introducing I/O multiplexing (like `select`/`epoll` in C, or Java NIO's `Selector`).

#### The Inner Loop — Reading Commands

```java
while (true) {
    String command = readCommand(client);
    if (command == null) { // client disconnected
        client.close();
        break;
    }
    respond(command, client);
}
```

- Once a client is connected, the inner loop keeps reading commands from it until the client disconnects.
- `readCommand()` returns `null` when the client closes the connection (the stream returns `-1` bytes read).
- On disconnect, the client socket is closed and the outer loop resumes, waiting for the next client.

#### `readCommand()` — Reading Raw Bytes

```java
private static String readCommand(Socket client) throws IOException {
    InputStream in = client.getInputStream();
    byte[] buffer = new byte[BUFFER_SIZE]; // 512 bytes
    int bytesRead = in.read(buffer);       // blocking
    if (bytesRead == -1) return null;      // client disconnected
    return new String(buffer, 0, bytesRead).trim();
}
```

- Reads up to **512 bytes** at a time from the client's input stream. This is a fixed-size buffer — a real implementation would handle partial reads and reassemble multi-packet commands.
- `in.read()` is **blocking** — the thread waits here until data arrives or the connection closes.
- Returns `null` on disconnect (`bytesRead == -1`), which signals the inner loop to break.

#### `respond()` — Sending a Response

```java
private static void respond(String command, Socket client) throws IOException {
    OutputStream out = client.getOutputStream();
    out.write((command + "\n").getBytes());
    out.flush();
}
```

- Currently just **echoes** the received command back to the client with a newline appended.
- `out.flush()` ensures the bytes are actually sent over the network immediately, not held in a buffer.
- This is a placeholder — in future phases this will be replaced with actual command parsing and execution (SET, GET, etc.).

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

---

## Key Concepts

### Why Synchronous / Single-Threaded?

This is Phase 1 — the simplest possible server. It handles one client at a time. While it's talking to Client 1, Client 2 waits in the OS backlog queue. This is intentional: it establishes the baseline before introducing concurrency or I/O multiplexing.

Real Redis is also single-threaded for command execution, but uses **I/O multiplexing** (`epoll`/`kqueue`) to handle thousands of concurrent connections without blocking — that's the next phase.

### Why Port 7379?

Redis uses port `6379` by default. This project uses `7379` so you can run both side-by-side without conflicts.

### What is the OS Backlog?

When you create a `ServerSocket` with a backlog of `50`, the OS maintains a queue of up to 50 fully-established TCP connections that your application hasn't called `accept()` on yet. This is why a second `telnet` session appears "connected" immediately even if the server is busy with another client — the OS completed the TCP handshake and queued it.

### Why RESP?

RESP is the protocol Redis clients use to talk to the server. By implementing a RESP decoder, this server can eventually understand real Redis commands sent by any standard Redis client (`redis-cli`, Jedis, Lettuce, etc.) — making it a drop-in compatible server.

---

## What Happens When You Connect

Here's the full flow when you run `telnet localhost 7379` and type `hello`:

```
telnet                          SyncTCPServer
  |                                  |
  |--- TCP SYN ─────────────────────>|  (OS accepts, queues in backlog)
  |<── TCP SYN-ACK ──────────────────|
  |--- TCP ACK ─────────────────────>|
  |                                  |  serverSocket.accept() returns Socket
  |                                  |  "client connected" printed
  |                                  |
  |--- "hello\r\n" ─────────────────>|  in.read() unblocks
  |                                  |  readCommand() returns "hello"
  |                                  |  respond() called
  |<── "hello\n" ────────────────────|
  |                                  |
  |--- [Ctrl+]] / connection close ─>|  in.read() returns -1
  |                                  |  readCommand() returns null
  |                                  |  client.close() called
  |                                  |  "client disconnected" printed
  |                                  |  outer loop resumes → serverSocket.accept()
```
