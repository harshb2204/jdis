# Pipelining

A typical Redis flow is to send request, wait for response. Once response is received then send another request.

![](/diagrams/reqresredis.png)

This classic flow has a major issue: **Round Trip Time**.

The RTT becomes significantly huge when there are large number of commands to be issued.

Hence, Redis provides **Command Pipelining**.

**Core Idea:** Send multiple commands in one request. Receive output of each command in one response.

![](/diagrams/pipelining.png)

**Note:** Pipelining $\neq$ Transaction.

It is just about clubbing multiple command in one REQ and receiving individual outputs clubbed in a single RES.

With pipelining, client sends multiple commands to server and server **cannot** respond until all are evaluated.

Hence the output of each command is buffered until all are evaluated and then server responds.

$\rightarrow$ *This would lead to a higher memory consumption.*

## Improving Throughput

Pipelining not only improves on RTT but it also increases throughput (*# commands per sec.*).

For Redis, evaluating a command:
- in-mem operation
- superfast

But waiting, receiving, sending is I/O hence slower.

With pipelining Redis server has to do fewer context switch to network I/O and focus more on executing commands.

$\rightarrow$ *This substantially betters the throughput!*

## Pipelining in Action

We can demonstrate pipelining using `printf` to format RESP (REdis Serialization Protocol) commands and pipe them to `nc` (netcat).

```bash
(printf '*1\r\n$4\r\nPING\r\n*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n*2\r\n$3\r\nGET\r\n$1\r\nk\r\n';) | nc localhost 6379
```

**Explanation:**
- The `printf` command crafts three Redis commands encoded in RESP format:
  1. `PING`
  2. `SET k v`
  3. `GET k`
- By piping this directly into `nc localhost 6379`, all three commands are sent to the Redis server in a single request.
- The Redis server buffers the output of all these commands and returns them together.
- The output shows:
  ```text
  +PONG
  +OK
  $1
  v
  ```
  Which are the sequential responses to the `PING`, `SET`, and `GET` commands.
