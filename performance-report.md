# Performance engineering report

## Scope and measurement discipline

All results below are localhost, client-observed round trips: writing one request through receiving its complete newline response. They include client scheduling and TCP overhead, so they are not server-only timings. The Python and Java clients must not be compared as if they were the same benchmark implementation.

The workload is 80% GET / 20% SET, a 10,000-key prepopulated cache, a 100% target GET-hit ratio, uniform keys, 32 persistent clients, 32 server workers, queue capacity 64, 100,000 cache capacity, 60-second TTL, and zero benchmark errors unless stated otherwise.

## Corrected baseline and JFR

The corrected Python baseline used a 10-second measurement after a three-second warm-up. It completed 272,472 operations (27,241 ops/s), 217,844 GET hits, zero misses/errors, 1.171 ms mean latency, and 3.694 ms P99.

JFR was recorded with:

```bash
java -XX:StartFlightRecording=filename=baseline.jfr,dumponexit=true,settings=profile \
  -jar target/flash-kv.jar 18084 100000 32 64 5000 4
```

The profile sampled 1,367 allocation events and four young GCs; the longest observed G1 pause was 2.64 ms. The dominant application allocation stacks were `String.split`/regex and `Arrays.copyOfRange` in `CommandParser`, plus `LinkedHashMap` iteration in `CacheManager.put` caused by scanning a complete shard on every write. It reported no `JavaMonitorEnter` contention events in this baseline sample.

## Changes evaluated

1. `TCP_NODELAY` is set on accepted server sockets (the benchmark clients already set it).
2. `CommandParser` now tokenizes directly rather than allocating regex matcher/arrays. Values with embedded spaces remain supported.
3. Expired-entry scanning now occurs only for a new key under shard capacity pressure; lazy GET expiration and the background expiry sweep are unchanged.
4. Shard count is configurable. Exact shard-local `LinkedHashMap(accessOrder=true)` LRU and lock-based safety are retained; no approximate eviction policy was introduced.
5. A Java client was added for lower-overhead, synchronized local measurements. It supports warm-up/setup barriers, prepopulation, actual hit/miss accounting, separate latency distributions, and optional expected-interval coordinated-omission correction.

## Shard sweep (Java client)

One five-second trial per row, following a two-second warm-up. This sweep isolates shard count; it is exploratory rather than a claim of statistical ranking.

| Shards | Throughput | Mean | P99 | P99.9 | Errors | GET hit ratio |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 4 | 103,302 ops/s | 0.309 ms | 0.423 ms | 0.516 ms | 0 | 100% |
| 8 | 101,846 ops/s | 0.313 ms | 0.425 ms | 0.622 ms | 0 | 100% |
| 16 | 102,260 ops/s | 0.312 ms | 0.539 ms | 0.835 ms | 0 | 100% |
| 32 | 97,657 ops/s | 0.326 ms | 0.427 ms | 0.514 ms | 0 | 100% |
| 64 | 94,661 ops/s | 0.337 ms | 0.585 ms | 1.218 ms | 0 | 100% |

Four shards were the best observed setting on this 10-logical-CPU host. More shards did not improve this workload and increased tail variability, so the default remains four.

## Repeated 4-shard result (Java client)

Three independent three-second measurements after one-second warm-ups produced:

| Run | Throughput | Mean | P99 | P99.9 | Successful ops | Errors | GET hits / misses |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 112,721 ops/s | 0.283 ms | 0.450 ms | 5.594 ms | 338,692 | 0 | 270,654 / 0 |
| 2 | 105,817 ops/s | 0.301 ms | 0.390 ms | 0.467 ms | 317,636 | 0 | 253,966 / 0 |
| 3 | 104,209 ops/s | 0.305 ms | 0.396 ms | 0.459 ms | 312,851 | 0 | 250,104 / 0 |

This demonstrates sub-1 ms P99 for the stated **Java-client, 32-client, cache-hit localhost workload** across three runs. It does not imply the same P99 for the Python client, remote clients, 250 clients, misses, or a different host.

During a separate five-second run of the same configuration, the macOS `ps` sample for the server process was 328.9% CPU and 211,104 KiB RSS (roughly 206 MiB). That run completed 111,519 ops/s at 0.435 ms P99 with zero errors. `%CPU` is summed across cores on macOS; it is a point-in-time sample, not an average or a host-wide utilization figure.

The optimized JFR sampled no regex split or per-write expiry-scan allocation paths. Four young GCs occurred, with the longest pause 0.989 ms. Remaining hot allocation samples are expected `String.substring` command tokens, `CacheEntry` allocations for SET, and character I/O buffers. A few monitor waits occurred around the JVM’s own `int[]` monitor internals; the cache’s `ReentrantLock` contention is not represented by `JavaMonitorEnter` events.

## Python-client comparison

After the server changes, three five-second Python-client runs completed 33,225, 30,868, and 31,319 ops/s, with P99 values of 2.966, 2.972, and 2.914 ms and no errors. They are consistent with an improvement over the earlier corrected Python 32-client results, but differ in duration/warm-up and therefore are not a strict before/after experiment. The Java client is the recommended tool for controlled local latency work.

## Remaining bottlenecks and trade-offs

The retained exact access-order LRU means every GET mutates `LinkedHashMap` order under its shard lock. That is correct and easy to reason about, but it will ultimately limit high-concurrency read scaling. The next architecture experiment, if profiling demands it, should be an explicitly labeled approximate-LRU cache behind `Cache`, with separate correctness and eviction-policy tests—not a silent replacement of exact LRU. NIO was not adopted because the measured hot paths were parser/allocation and expiry scanning, and the blocking server already meets the stated 32-client local P99 target.
