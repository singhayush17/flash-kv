# FlashKV

FlashKV is a single-node, in-memory Java key-value cache with a line-oriented TCP protocol, TTL expiration, and shard-local LRU eviction. It is a learning project: it has no persistence, replication, authentication, or clustering.

## Architecture and concurrency

`CacheManager` hashes each key to one of up to four `CacheShard`s. A shard owns an access-order `LinkedHashMap` and a `ReentrantLock`; every map access, including access-order `get`, eviction, and expiry scan, happens under that lock. Capacity is partitioned across shards, so the sum of shard capacities is exactly the requested capacity. Eviction is therefore LRU **within a shard**, not globally across all keys.

Expiration is lazy on `GET` and also scanned once per second by a single scheduler. The scheduler takes one shard lock at a time, so it cannot hold a global cache lock. A zero TTL is expired immediately.

The TCP server uses blocking I/O and a bounded fixed-size `ThreadPoolExecutor`. This is a deliberate small-project trade-off: commands stay simple and synchronous while admission remains bounded. The acceptor closes a connection when the handler pool and its bounded queue are saturated; it never creates an unbounded task backlog. Each handler has a configurable socket read timeout, so an idle or slow peer cannot hold a worker forever. For a workload with hundreds of continuously active connections, configure enough workers for the expected active connections, then validate the latency/error trade-off. A selector-based server would scale idle connections with fewer threads, but adds substantially more protocol and output-buffer state; it is not needed for this simple request/response protocol yet.

On shutdown the server closes the listening socket and all tracked connections, interrupts handlers, and waits briefly for executor termination.

## Protocol

```
SET <key> <value> <ttlMillis>
GET <key>
DELETE <key>
STATS
QUIT
```

Responses are one newline-delimited line. `GET` returns `NULL` for a missing or expired key.

For example:

```text
SET greeting hello 10000
GET greeting
```

## Build, run, and test

```bash
mvn clean test package
./run.sh
```

`run.sh` accepts `PORT` (8080), `CAPACITY` (100), `WORKERS` (16), `QUEUE_CAPACITY` (32), `READ_TIMEOUT_MS` (30000), and `SHARDS` (4). The JAR accepts the same values positionally: `port capacity workers queueCapacity readTimeoutMillis shards`.

The automated suite covers TTL boundaries and cleanup, shard capacity under concurrent GET/SET/DELETE, LRU behavior, split TCP frames, persistent concurrent clients, client disconnects, idle read timeouts, executor saturation, rejected connections, and shutdown.

## Reproducible benchmark workflow

`benchmark.py` maintains one TCP connection per client and reads responses with buffered newline-delimited I/O. Its latency is client-observed round-trip latency, measured with `time.perf_counter_ns()` immediately before `sendall()` until the complete response line is read. It includes client scheduling, TCP buffering, queueing, server execution, and response transmission; it is not server-only latency.

Before each run, the benchmark prepopulates `key-<n>` keys by default (`--prepopulate-keys`, defaulting to `--keyspace`). A GET selected as a hit reads that exact namespace; a requested miss reads a separate `miss-<n>` namespace. `--get-hit-ratio` therefore controls the target share of GETs aimed at populated keys, while the report records the actual `NULL` misses and non-`NULL` hits returned by FlashKV. Set `--prepopulate-keys 0` for a pure write workload.

Warm-up is excluded from results. A failure-safe two-phase coordinator waits for every connection attempt, warms every successfully handshaken client, then releases all ready clients at the same logical measurement start. Successful, rejected, and failed connections are separate counts; `ERROR`, timeouts, and incomplete responses are failed operations. It reports overall, GET, and SET throughput; GET hits/misses; and mean/min/max/P50/P90/P95/P99/P99.9 latency.

Start a server with a chosen worker configuration, allow the JVM to settle during the benchmark warm-up, then run three measured repetitions:

```bash
WORKERS=64 QUEUE_CAPACITY=128 CAPACITY=100000 ./run.sh
python3 benchmark.py --clients 32 --requests 10000 --warmup 15 --runs 3 \
  --keyspace 100000 --prepopulate-keys 100000 --get-ratio 0.8 --get-hit-ratio 1 \
  --key-distribution uniform --results-json results.json
```

Run the matrix below for each worker setting being evaluated (for example 16, 64, and 256). Use `--duration 30 --requests 0` for duration-limited runs if preferred.

```bash
for clients in 1 8 32 64 128 250; do
  python3 benchmark.py --clients "$clients" --requests 10000 --warmup 15 --runs 3 --get-ratio 1.0 --get-hit-ratio 1 --key-distribution uniform
  python3 benchmark.py --clients "$clients" --requests 10000 --warmup 15 --runs 3 --get-ratio 0.0 --prepopulate-keys 0 --key-distribution uniform
  python3 benchmark.py --clients "$clients" --requests 10000 --warmup 15 --runs 3 --get-ratio 0.8 --get-hit-ratio 1 --key-distribution uniform
  python3 benchmark.py --clients "$clients" --requests 10000 --warmup 15 --runs 3 --get-ratio 0.8 --get-hit-ratio 1 --key-distribution zipf
done
```

Record the host CPU and memory, OS/kernel, Java version and JVM flags, cache capacity, worker/queue/timeout settings, client count, total successful and failed operations, warm-up and measurement durations, workload/key distribution, and the command line. Do not compare runs made under different conditions. See [benchmark-results.md](benchmark-results.md) for an executed smoke result; it is intentionally not presented as a general performance claim.

### Java benchmark client

The Java client avoids Python thread scheduling in a local latency measurement. It uses persistent connections, setup and warm-up latches, cache prepopulation, actual GET hit/miss reporting, separate GET/SET percentiles, and an optional expected-interval coordinated-omission correction. Its latencies are still client-observed rather than server processing time.

```bash
java -cp target/flash-kv.jar io.github.whyush.flashkv.benchmark.JavaBenchmarkClient \
  --port 8080 --clients 32 --warmup-seconds 15 --duration-seconds 30 \
  --keyspace 100000 --prepopulate-keys 100000 --get-ratio 0.8 --get-hit-ratio 1 \
  --distribution uniform --expected-interval-us 0
```

Set `--expected-interval-us` to a non-zero expected request interval only for an open-loop/target-rate experiment; the client records synthetic delayed samples to expose coordinated omission. See [performance-report.md](performance-report.md) for the JFR observations, shard sweep, and executed comparisons.

## Docker

```bash
docker build -t flash-kv .
docker run -p 8080:8080 flash-kv
```

## License

MIT
