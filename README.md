# FlashKV

FlashKV is a **single-node, in-memory key–value cache** written in Java. It focuses on correctness, concurrency, and clarity while supporting TTL-based expiration, LRU eviction, and concurrent access over a simple TCP protocol.

This project is intentionally scoped as a systems-learning exercise and avoids persistence, clustering, or distributed coordination.

---

## Features

* **String key → String value**
* **TTL-based expiration**

    * Lazy expiration on access
    * Periodic background cleanup
* **LRU eviction**

    * Implemented per shard using `LinkedHashMap(accessOrder=true)`
* **Concurrent access**

    * Cache is sharded
    * Each shard protected by its own lock
* **TCP-based text protocol**
* **Lightweight metrics**

    * Hits, misses, evictions, expired entries
    * Connections and command count
* **Graceful shutdown**

---

## High-Level Architecture

```
Client
  |
  |  (TCP)
  v
TcpServer
  |
  v
CommandParser
  |
  v
CacheManager
  |
  +-- CacheShard[0..N]
         |
         +-- LinkedHashMap (LRU)
         +-- ReentrantLock
```

A background `ExpiryScheduler` periodically scans shards to remove expired entries.

---

## Cache Design

### Sharding

* The cache is divided into multiple shards to reduce lock contention
* Shard is selected using:

```java
(key.hashCode() & 0x7fffffff) % shardCount
```

* Each shard owns:

    * A fixed capacity
    * A `LinkedHashMap` for LRU ordering
    * A `ReentrantLock`

---

### Eviction

* Eviction is **per shard**, not global
* When a shard exceeds capacity, the **least recently used entry** is evicted
* Overwriting an existing key does **not** trigger eviction

---

### Expiration

* Each entry stores an absolute expiry timestamp
* Entries are removed when expired via two paths:

    * **Lazy expiration** on `GET`
    * **Background expiration** via a scheduled task
* All TTL-based removals contribute to a single `expired` metric

---

## TCP Protocol

### Supported Commands

```
SET <key> <ttlMillis> <value>
GET <key>
DELETE <key>
STATS
QUIT
```

### Example Session

```
SET foo 5000 hello
OK

GET foo
hello

GET missing
NULL

STATS
hits=1 misses=1 evictions=0 expired=0 connections=1 commands=5
```

---

## Metrics

FlashKV tracks basic operational metrics using `LongAdder` for low contention:

* `hits` – successful cache reads
* `misses` – failed cache reads
* `evictions` – LRU evictions due to capacity pressure
* `expired` – entries removed due to TTL expiry (all paths)
* `connections` – client connections accepted
* `commands` – total commands processed

Metrics can be queried at runtime using the `STATS` command.

---

## Building and Running

### Build

```bash
mvn clean package
```

### Run Locally

```bash
./run.sh
```

Environment variables:

* `PORT` (default: 8080)
* `CAPACITY` (default: 100)

---

## Docker

```bash
docker build -t flash-kv .
docker run -p 8080:8080 flash-kv
```

---

## Testing

The test suite validates:

* TTL expiration behavior
* LRU eviction semantics
* Overwrite behavior
* Background expiry without access
* Sharding transparency

Run tests using:

```bash
mvn test
```

---

## Non-Goals

This project intentionally does **not** include:

* Persistence or durability
* Clustering or replication
* Strong consistency across nodes
* Authentication or authorization
* Advanced eviction policies (LFU, ARC)

---

## Future Improvements

- Optimize TTL expiration by replacing periodic O(n) shard scans with a priority-queue–based scheduler.
- Introduce a timing-wheel–based expiration strategy for large key counts.
- Add configurable shard count based on CPU cores.


## License

MIT
