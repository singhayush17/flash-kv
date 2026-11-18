# Executed benchmark report

This is a local smoke benchmark, not a portable performance claim. It exists to show the new benchmark produces measurements and that failures are accounted for. The full matrix in the README has not been run on this host.

## Environment

| Item | Value |
| --- | --- |
| Date | 2026-11-18 |
| Host | Apple Silicon MacBook Air, 10 logical CPUs, 16 GiB RAM |
| OS | macOS / Darwin 24.6.0, arm64 |
| JVM | Homebrew OpenJDK 17.0.17, default JVM flags |
| Cache capacity | 100,000 |
| Server | localhost, blocking TCP, 64 workers, queue capacity 128, read timeout 5,000 ms |
| Workload | 32 persistent clients; 80% GET / 20% SET; uniform 100,000-key distribution; TTL 60 s |
| Warm-up | 2 seconds per run |
| Measured work | 2,000 requests per client (64,000 per run) |

Command:

```bash
java -jar target/flash-kv.jar 18080 100000 64 128 5000
python3 benchmark.py --port 18080 --clients 32 --requests 2000 --warmup 2 --runs 3 --get-ratio 0.8 --key-distribution uniform
```

Latency is client-observed `sendall()` to complete newline response, so it includes local TCP/client overhead and queueing.

| Run | Successful | Failed | Overall throughput | Mean | P50 | P95 | P99 | P99.9 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 64,000 | 0 | 15,050.76 ops/s | 1.114 ms | 0.997 ms | 2.434 ms | 3.347 ms | 4.783 ms |
| 2 | 64,000 | 0 | 15,332.28 ops/s | 1.077 ms | 0.972 ms | 2.313 ms | 3.110 ms | 4.345 ms |
| 3 | 64,000 | 0 | 15,394.79 ops/s | 1.069 ms | 0.972 ms | 2.286 ms | 3.053 ms | 4.202 ms |

In run 3, GET throughput was 12,317.52 ops/s and SET throughput was 3,077.28 ops/s. GET P99 was 3.068 ms; SET P99 was 3.003 ms.

## Small worker sensitivity check

This separate, shorter check used 32 clients, 500 requests/client, one-second warm-up, one run, the same cache and workload. It is diagnostic only and is too short to rank configurations conclusively.

| Workers | Successful | Failed | Throughput | Mean | P99 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 16 | 16,000 | 0 | 28,743.26 ops/s | 0.550 ms | 1.530 ms |
| 64 | 16,000 | 0 | 28,451.32 ops/s | 1.102 ms | 3.227 ms |

The result reinforces that more workers are not automatically faster on a 10-logical-CPU host. Select worker count from repeated, sufficiently long runs at the target client level, while watching errors and tail latency.
