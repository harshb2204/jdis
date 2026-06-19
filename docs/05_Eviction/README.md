# Key Eviction

Redis is RAM-bound and we cannot have an unbounded-storage to accomodate any number of keys.

Hence, to accomodate newer data, we have to evict some old data.

## How Redis Evicts?

Redis has a `maxmemory` configuration that limits the max amount of data it could hold.

When the Redis data size hits the limit, it evicts some of the old data.

The data that would be evicted, depends on the eviction strategy configured, some of them are:

- `noeviction` : new values aren't saved when memory limit is hit
- `allkeys-lru` : removes least recently used keys
- `allkeys-lfu` : removes least frequently used keys
- `volatile-lru` : removes LRU keys with EXPIRE set
- `volatile-lfu` : removes LFU keys with EXPIRE set
- `allkeys-random` : picks some keys at random and evicts them
- `volatile-random` : picks some keys with EXPIRE set at random to evict
- `volatile-ttl` : picks key with shortest TTL and evicts it

## Approximated LRU [Redis 3.0]

Redis' LRU is not an exact algorithm, instead it is approximated.

**CORE Idea:** Sample some keys and from them evict the one that is least recently used.

### But why Redis does not use exact LRU?

Because it requires extra memory.

Imagine maintaining a DLL to maintain accesses. The additional memory overhead of maintaining pointers is an overkill. Redis would choose to use that mem for data & not pointers.

In approximated LRU, we can take 'n' samples each having 'k' keys and then evict the one which is least recently used.

*more samples, better accuracy to evict the actual LRU key*

## New LFU mode [Redis 4.0]

LFU is least Frequently Used hence Redis keeps track of freq of key accesses.

Everytime we get or update it does `freq++`

### When to use LFU?

Use it when key that are used often should be kept in memory.

*Even if there is a dip in access of a key, it should not be evicted.*

Storing a huge integer value is costly, hence instead of using regular int to store exact freq, it uses **Morris Counter**.

**Note:** frequency is not ever-increasing, it has a decay that with time reduces the value.

1. saturate counter at 1,000,000 requests
2. decay the value every 1 minute
