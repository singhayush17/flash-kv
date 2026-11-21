# Executed benchmark results

These are local, client-observed measurements, not portable performance claims. Every latency begins immediately before `sendall()` and ends after the complete newline-delimited response; it includes Python client scheduling, localhost TCP, server admission/queueing, command execution, and the response.

## Environment and methodology

| Item | Value |
| --- | --- |
| Date | 2026-11-19 |
| Host | Apple Silicon MacBook Air, 10 logical CPUs, 16 GiB RAM |
| OS | macOS / Darwin 24.6.0, arm64 |
| JVM | Homebrew OpenJDK 17.0.17; default JVM flags |
| Server | localhost; blocking TCP; 5,000 ms read timeout; cache capacity 100,000 |
| Keyspace | 10,000 keys; 60-second TTL |
| Repetitions | Three per scenario |
| Warm-up / measurement | One second / two seconds per repetition |

Worker/queue settings were selected to admit each tier without silently queuing an unbounded number of clients: 32/64 for 1–32 clients, 128/256 for 64–128 clients, and 256/512 for 250 clients.

For `GET` and mixed workloads, all 10,000 measured `key-<n>` keys were populated before **each** repetition. GETs selected that namespace, so their reported hit ratio is actual server responses—not an assumed ratio. The 100% SET workload deliberately used no initial population and therefore has no GET hit ratio. `mixed-hot` uses the benchmark’s Zipf-like hot-key distribution; all other workloads use uniform keys.

The benchmark’s phase coordinator waits for every client to finish a `STATS` handshake, releases successful clients to warm up, waits for every successful client to complete warm-up, then starts the measurement timer and releases all measured clients. Failed and rejected handshakes are recorded but do not deadlock either phase.

## Results

Every individual repetition had its listed client count successfully complete the handshake; rejected connections, failed connections, warm-up errors, and failed measured operations were all zero. The table reports the arithmetic mean of the three actual repetitions. `Ops/run` and throughput are means; hit ratio is based on actual GET responses. Latency columns are milliseconds.

| Clients | Workers / queue | Workload | Successful connections / run | Ops/run | Failed ops | Throughput ops/s | GET hits / misses | Hit ratio | Mean | P50 | P95 | P99 | P99.9 |
| ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 32 / 64 | 100% GET, populated uniform | 1 | 68,336 | 0 | 34,165 | 68,336 / 0 | 100.0% | 0.027 | 0.027 | 0.031 | 0.038 | 0.068 |
| 1 | 32 / 64 | 100% SET, uniform | 1 | 31,076 | 0 | 15,536 | 0 / 0 | n/a | 0.062 | 0.062 | 0.071 | 0.078 | 0.104 |
| 1 | 32 / 64 | 80/20, populated uniform | 1 | 54,741 | 0 | 27,368 | 43,858 / 0 | 100.0% | 0.034 | 0.027 | 0.066 | 0.073 | 0.102 |
| 1 | 32 / 64 | 80/20, populated hot | 1 | 54,606 | 0 | 27,300 | 43,712 / 0 | 100.0% | 0.035 | 0.027 | 0.066 | 0.074 | 0.140 |
| 8 | 32 / 64 | 100% GET, populated uniform | 8 | 62,545 | 0 | 31,263 | 62,545 / 0 | 100.0% | 0.253 | 0.228 | 0.504 | 0.674 | 0.979 |
| 8 | 32 / 64 | 100% SET, uniform | 8 | 53,187 | 0 | 26,584 | 0 / 0 | n/a | 0.298 | 0.272 | 0.556 | 0.722 | 0.946 |
| 8 | 32 / 64 | 80/20, populated uniform | 8 | 61,408 | 0 | 30,695 | 48,934 / 0 | 100.0% | 0.258 | 0.234 | 0.504 | 0.661 | 0.898 |
| 8 | 32 / 64 | 80/20, populated hot | 8 | 60,715 | 0 | 30,348 | 48,494 / 0 | 100.0% | 0.261 | 0.237 | 0.513 | 0.676 | 0.909 |
| 32 | 32 / 64 | 100% GET, populated uniform | 32 | 59,069 | 0 | 29,501 | 59,069 / 0 | 100.0% | 1.085 | 0.967 | 2.376 | 3.231 | 4.416 |
| 32 | 32 / 64 | 100% SET, uniform | 32 | 47,346 | 0 | 23,645 | 0 / 0 | n/a | 1.349 | 1.194 | 2.923 | 3.905 | 5.292 |
| 32 | 32 / 64 | 80/20, populated uniform | 32 | 59,377 | 0 | 29,656 | 47,541 / 0 | 100.0% | 1.075 | 0.968 | 2.310 | 3.087 | 4.105 |
| 32 | 32 / 64 | 80/20, populated hot | 32 | 58,773 | 0 | 29,356 | 46,941 / 0 | 100.0% | 1.086 | 0.974 | 2.346 | 3.143 | 4.202 |
| 64 | 128 / 256 | 100% GET, populated uniform | 64 | 58,454 | 0 | 29,154 | 58,454 / 0 | 100.0% | 2.189 | 2.016 | 4.814 | 6.471 | 8.884 |
| 64 | 128 / 256 | 100% SET, uniform | 64 | 46,396 | 0 | 23,125 | 0 / 0 | n/a | 2.757 | 2.448 | 6.143 | 8.293 | 11.024 |
| 64 | 128 / 256 | 80/20, populated uniform | 64 | 56,021 | 0 | 27,946 | 44,632 / 0 | 100.0% | 2.289 | 2.071 | 5.078 | 6.857 | 9.268 |
| 64 | 128 / 256 | 80/20, populated hot | 64 | 58,156 | 0 | 29,011 | 46,669 / 0 | 100.0% | 2.199 | 2.026 | 4.799 | 6.397 | 8.519 |
| 128 | 128 / 256 | 100% GET, populated uniform | 128 | 56,534 | 0 | 28,131 | 56,534 / 0 | 100.0% | 4.531 | 4.321 | 9.953 | 13.201 | 17.550 |
| 128 | 128 / 256 | 100% SET, uniform | 128 | 46,573 | 0 | 23,155 | 0 / 0 | n/a | 5.508 | 5.010 | 12.197 | 16.380 | 22.292 |
| 128 | 128 / 256 | 80/20, populated uniform | 128 | 54,428 | 0 | 27,053 | 43,464 / 0 | 100.0% | 4.706 | 4.443 | 10.372 | 13.944 | 20.929 |
| 128 | 128 / 256 | 80/20, populated hot | 128 | 53,004 | 0 | 26,382 | 42,511 / 0 | 100.0% | 4.841 | 4.537 | 10.620 | 14.264 | 19.071 |
| 250 | 256 / 512 | 100% GET, populated uniform | 250 | 46,210 | 0 | 22,900 | 46,210 / 0 | 100.0% | 10.847 | 10.497 | 23.899 | 31.415 | 42.167 |
| 250 | 256 / 512 | 100% SET, uniform | 250 | 39,635 | 0 | 19,370 | 0 / 0 | n/a | 12.798 | 11.667 | 28.986 | 42.414 | 74.107 |
| 250 | 256 / 512 | 80/20, populated uniform | 250 | 43,406 | 0 | 21,368 | 34,647 / 0 | 100.0% | 11.568 | 10.727 | 25.530 | 35.198 | 49.206 |
| 250 | 256 / 512 | 80/20, populated hot | 250 | 44,068 | 0 | 21,790 | 35,130 / 0 | 100.0% | 11.388 | 10.700 | 25.125 | 33.703 | 44.098 |

The hot-key mixed workload modestly improved throughput and tail latency at 64 and 250 clients on this machine, consistent with greater cache locality. It does not establish a general performance advantage. The main observed scaling limit is client-observed queueing and scheduling as active persistent connections rise; the 250-client run was intentionally configured with enough workers to avoid admission failures, not to claim that 256 workers is an optimal production setting.
