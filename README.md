# FlashKV

FlashKV is a single-node, in-memory Java key-value cache with a line-oriented TCP protocol, TTL expiration, and shard-local LRU eviction. It is a learning project: it has no persistence, replication, authentication, or clustering.

## Architecture and concurrency

`CacheManager` hashes each key to one of up to four `CacheShard`s. A shard owns an access-order `LinkedHashMap` and a `ReentrantLock`; every map access, including access-order `get`, eviction, and expiry scan, happens under that lock. Capacity is partitioned across shards, so the sum of shard capacities is exactly the requested capacity. Eviction is therefore LRU **within a shard**, not globally across all keys.

Expiration is lazy on `GET` and also scanned once per second by a single scheduler. The scheduler takes one shard lock at a time, so it cannot hold a global cache lock. A zero TTL is expired immediately.

The TCP server uses blocking I/O and a bounded fixed-size `ThreadPoolExecutor`. This is a deliberate small-project trade-off: commands stay simple and synchronous while admission remains bounded. The acceptor closes a connection when the handler pool and its bounded queue are saturated; it never creates an unbounded task backlog. Each handler has a configurable socket read timeout, so an idle or slow peer cannot hold a worker forever. For a workload with hundreds of continuously active connections, configure enough workers for the expected active connections, then validate the latency/error trade-off. A selector-based server would scale idle connections with fewer threads, but adds substantially more protocol and output-buffer state; it is not needed for this simple request/response protocol yet.

On shutdown the server closes the listening socket and all tracked connections, interrupts handlers, and waits briefly for executor termination.

## Protocol

```
SET <key> <ttlMillis> <value>
GET <key>
DELETE <key>
STATS
QUIT
```

Responses are one newline-delimited line. `GET` returns `NULL` for a missing or expired key.

## Build, run, and test

```bash
mvn clean test package
./run.sh
```

`run.sh` accepts `PORT` (8080), `CAPACITY` (100), `WORKERS` (16), `QUEUE_CAPACITY` (32), and `READ_TIMEOUT_MS` (30000). The JAR accepts the same values positionally: `port capacity workers queueCapacity readTimeoutMillis`.

The automated suite covers TTL boundaries and cleanup, shard capacity under concurrent GET/SET/DELETE, LRU behavior, split TCP frames, persistent concurrent clients, client disconnects, idle read timeouts, executor saturation, rejected connections, and shutdown.

## Reproducible benchmark workflow

`benchmark.py` maintains one TCP connection per client and reads responses with buffered newline-delimited I/O. Its latency is client-observed round-trip latency, measured with `time.perf_counter_ns()` immediately before `sendall()` until the complete response line is read. It includes client scheduling, TCP buffering, queueing, server execution, and response transmission; it is not server-only latency.

Warm-up is excluded from results. Successful operations are reported separately from failures; `ERROR`, timeouts, connection failures, and incomplete responses are failures. It reports overall, GET, and SET throughput plus mean/min/max/P50/P90/P95/P99/P99.9 latency.

Start a server with a chosen worker configuration, allow the JVM to settle during the benchmark warm-up, then run three measured repetitions:

```bash
WORKERS=64 QUEUE_CAPACITY=128 CAPACITY=100000 ./run.sh
python3 benchmark.py --clients 32 --requests 10000 --warmup 15 --runs 3 --get-ratio 0.8 --key-distribution uniform
```

Run the matrix below for each worker setting being evaluated (for example 16, 64, and 256). Use `--duration 30 --requests 0` for duration-limited runs if preferred.

```bash
for clients in 1 8 32 64 128 250; do
  python3 benchmark.py --clients "$clients" --requests 10000 --warmup 15 --runs 3 --get-ratio 1.0 --key-distribution uniform
  python3 benchmark.py --clients "$clients" --requests 10000 --warmup 15 --runs 3 --get-ratio 0.0 --key-distribution uniform
  python3 benchmark.py --clients "$clients" --requests 10000 --warmup 15 --runs 3 --get-ratio 0.8 --key-distribution uniform
  python3 benchmark.py --clients "$clients" --requests 10000 --warmup 15 --runs 3 --get-ratio 0.8 --key-distribution zipf
done
```

Record the host CPU and memory, OS/kernel, Java version and JVM flags, cache capacity, worker/queue/timeout settings, client count, total successful and failed operations, warm-up and measurement durations, workload/key distribution, and the command line. Do not compare runs made under different conditions. See [benchmark-results.md](benchmark-results.md) for an executed smoke result; it is intentionally not presented as a general performance claim.

## Docker

```bash
docker build -t flash-kv .
docker run -p 8080:8080 flash-kv
```

## License

MIT
