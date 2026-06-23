# Persistence

Although Redis is an in-memory database, it also provides periodic persistence.

Two persistence formats:
1. RDB
2. AOF

## RDB persistence

RDB is a point-in-time snapshot of the Dataset.

- used for snapshots
- single file output
- highly compact

**How?** Redis creates a fork and this new process dumps and creates the RDB file.

No impact on Redis' performance $\rightarrow$ given enough CPU exists.

RDB creation should not be frequent operation.
Dumping rdb files again ad again becomes costly as data size would increase. 
For eg: Lets say u r creating a fresh rdb file every 5 minutes. You created a fresh file 4 minutes back, in that 4 minutes lot of updates in memory were accepted and after that the process crashed. So from the last flush till this time nothing is written on the disk. 
![](/diagrams/flush.png)

## AOF persistence

AOF logs every single write operation to a file and replays it upon bootup to recreate the dataset.

The commands are logged in RESP format itself making it super simple to read and replay.

**Note:** Periodically the entire AOF file is re-written for compaction.

```text
AOF File        Dataset      Rewritten AOF
SET K V1   \
SET K V2    |-> { K: V4 } -> BGREWRITEAOF -> SET K V4
SET K V3    |
SET K V4   /
```

- AOF are much more durable.
  - written every second (configurable).
  - Max data loss = 1 sec.
- APPEND ONLY FILE $\rightarrow$ write perf.

`redis-check-aof` to check if AOF is valid (not corrupted).

### How REWRITE happens in the background?

Background thread rewrites to a temp AOF followed by a rename.

```text
[temp.aof] --RENAME--> appendonly.aof
```

**Note:** AOF files are bigger than RDB.
