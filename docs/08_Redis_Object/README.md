# The Redis Object

Every single object we put in Redis is wrapped in a **Redis Object**
* so that we keep some meta info
* manage object state & expiration
* make common features agnostic

```c
struct redisObject {
    unsigned type: 4;
    unsigned encoding: 4;
    unsigned lru: LRU_BITS;
    int refcount;
    void *ptr;
}
```

1. Instead of assigning 4B to type & encoding, Redis saves memory by allocating a few bits
    * `type` -> 4 bits 
    * `encoding` -> 4 bits
    * Together they take 8 bits = 1B
2. `void *ptr` allows us to store/refer to any type of object
3. `refcount` -> # places where same obj is referenced
    * when `refcount = 0` -> free the object
4. `lru` takes up 24 bits -> 3 Bytes
    * to store information about time & freq of access

**Total size:** `4 + 4 + 24` (4B) + `32` (4B) + `32` (4B) -> **12B**

# Type and Encoding

Redis supports 7 object types. Each object can be encoded differently.

1. **string** -> `raw`, `int`, `embstr` (< 44 bytes)
2. **list** -> `ziplist`, `linked list`
3. **set** -> `intset`, `hashtable`
4. **sorted set** -> `ziplist`, `skiplist`
5. **hash** -> `ziplist`, `hashtable`
6. **module**
7. **stream**

`ziplist` is great at saving space hence used when data is small, but when it grows beyond a threshold it is converted to general type.
