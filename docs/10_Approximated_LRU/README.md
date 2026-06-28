# Internals of approximated LRU

Redis does not use exact LRU for eviction because it is space inefficient. It uses **24 LRU bits**.
Hence, it approximates the choice, efficiently.

## Naive implementation

1. store 'time' (last accessed at) for each object
2. keep them ordered by LAT and keep evicting efficiently
    * Doubly linked list is commonly used for this

## Disadvantages

1. DLL requires extra memory (next/prev pointers & key ptr)
2. Whenever LAT changes, it requires a lot of shuffling
    * reduces DB throughput
3. Storing LAT itself takes up 32bits per object

Overall the naive approach is extremely inefficient for Redis.
Hence Redis approximates....

## Key Decision 1: Store last Access Time in 24 bits (not 32 bits)

This would help us save 8bit per object, and for an in-mem DB this is huge saving.

But how would we store time then?

`last_accessed_at = time.now() & 0x00FFFFFF`
*(where `time.now()` is in seconds)*

We will just be using 24 LSBs.
We are losing on exact time but that is okay.
24 bits will help us cover a span of 194 days.



## Key Decision 2: Idle time

LRU is all about evicting the key that is least recently used
i.e. the key that has the most idle time.
Our clock is 24 bits hence it repeats.
![](/diagrams/approxlru1.png)

Say, we have a clock that is 5 bits long (for simplicity)

![](/diagrams/approxlru2.png)

- Say key `K1` was accessed at `t=16`
- key `K2` was accessed at `t=24`
- current time = `t=27` .... LRU kicks in

LRU sees `K1` and `K2`, computes idle time:
- for `K1 = 27 - 16 = 11`
- for `K2 = 27 - 24 = 3`

`K1` has higher idle time
hence least recently used
hence evicted

But after 5 seconds clock would be circling back to 0 & start over.

at `t=4`, `K3` was accessed
and `K2` has `LAT = 24`

say, at current time `t=6`
The LRU kicks in. how would we evict the keys?

if we just take min of LAT, we would evict `K3` that has just been accessed.

![](/diagrams/approxlru3.png)

How would we know that the clock started over? ...

- For `K3` the `LAT=4` is proper `idle time = 6 - 4 = 2`
- For `K2` the idle time is actually, `(MAX - LAT) + CLOCK = (31 - 24) + 6 = 7 + 6 = 13`

and thus we evict the one with longer idle time
and hence `K2` gets evicted.

Hence,
- `idle time = clock - lat` (if `clock > lat`)
- `idle time = (max - lat) + clock` (if `clock < lat`)

worst case... for the entire duration the obj was never accessed ... pretty rare in real work -> `194 days`

Thus we get our LRU working with just 24 bits

## The approximated LRU algorithm

Sample `N (<= 5)` keys from the dataset and populate it in the eviction pool.
Delete `M` keys from the pool.

Size of the pool is fixed `= 16`
- Keys from the sample are added only when they are better than the existing pool.
- The pool is kept sorted by idle time, hence insertion can happen in the middle.

During eviction, the best key is deleted from the pool and the keys are continuously evicted until we meet the target memory consumption.