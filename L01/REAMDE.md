# Redis, and what makes it special?

Redis is an open-sourced in-memory **data structure** store, that can be used as
- database
- message broker
- cache
- streaming engine

### Some datastructures that Redis provides

hash, list, set, sorted set, bitmap,  
hyperloglog, geospatial indexes, streams

### These datastructures can be used to build a variety of applications
- Realtime chat
- Auth session store
- Message Buffers
- Media streaming
- Gaming Leaderboards
- Realtime Analytics

## What makes Redis special?

### Every operation on Redis is **Atomic**
- putting a key
- adding to the list
- set union / intersection
- incrementing the value

> **Note:** When a command is executing, Redis does not context switch and start executing another command.

### Data is stored in-memory
Hence the most common use of Redis is for **"caching"**.

### But Redis also provides configurable persistence
- periodically dumping data to disk
- write-ahead log of all commands
- no persistence at all

### Other key features
Transactions, Pub/Sub, TTL on keys, and LRU eviction

## Concurrent Programming Models (Single Process)
Concurrent Programming is all about doing more than one thing at the same time.

### Multi-threading
Each incoming request over the network is accepted by the server and executed in a separate thread.

e.g.: 
R1 &rarr; INCR K &rarr; T1
R2 &rarr; INCR K &rarr; T2

### How to ensure data correctness?
If K = 10, and two threads executing K++ then possible final values of K are 11 and 12.
We have to make other threads wait while one thread is executing the critical section.

**Hence we have to safeguard:**
1. Mutex
2. Semaphores
(Pessimistic locking)

ACQ LOCK
——— K++
——— REL LOCK

# I/O Multiplexing (Apparent Concurrency)
* This is how event loops are implemented.

IO system calls are blocking.
e.g.: reading from a socket
`read()` blocks until the other person sends data.

Hence, we cannot just invoke `read` on a socket unless we know that the other party will be sending the data.

So, handling each connection on a separate thread is popular; while one thread is **blocked**, the other thread executes.

But we need to use mutex, semaphores to protect the critical section.

**Can we do something that:**
- Does not keep us waiting on the I/O?
- Can "notify" us when there is some movement?

This is the world of **I/O Multiplexing**.

**Core Idea:**
Use I/O monitoring calls to monitor the sockets and fire "read" on the ones that have some data.
![](/diagrams/singlethread.png)

---

* There is no separate process
* There is no separate thread

### What Redis exploits?
- **Network I/O is slow** &rarr; waiting to receive commands
- **In-memory ops is fast** &rarr; upon receiving commands, Redis can very quickly execute them

This is why Redis made this conscious decision of keeping itself:
1. **Single Threaded** &rarr; No need of mutex, semaphores and waiting
2. **Doing I/O Multiplexing** &rarr; handling multiple TCP connections concurrently

