#!/usr/bin/env python3
"""Reproducible persistent-connection TCP benchmark for FlashKV.

Measured latency is client-observed sendall() through one complete newline response.
It includes client scheduling, TCP buffering, server queueing, execution, and reply.
"""
import argparse
import json
import math
import random
import socket
import statistics
import threading
import time
from collections import Counter, defaultdict


def percentile(values, pct):
    if not values:
        return None
    at = (len(values) - 1) * pct / 100
    lo, hi = math.floor(at), math.ceil(at)
    return values[lo] if lo == hi else values[lo] + (values[hi] - values[lo]) * (at - lo)


def latency_summary(values):
    if not values:
        return {"count": 0}
    ordered = sorted(values)
    return {
        "count": len(ordered), "mean_ms": statistics.mean(ordered) / 1e6,
        "min_ms": ordered[0] / 1e6, "max_ms": ordered[-1] / 1e6,
        "p50_ms": percentile(ordered, 50) / 1e6, "p90_ms": percentile(ordered, 90) / 1e6,
        "p95_ms": percentile(ordered, 95) / 1e6, "p99_ms": percentile(ordered, 99) / 1e6,
        "p999_ms": percentile(ordered, 99.9) / 1e6,
    }


def print_latency(name, summary):
    if not summary["count"]:
        print(f"  {name:<7} no successful operations")
        return
    print(f"  {name:<7} count={summary['count']:<8} mean={summary['mean_ms']:.3f}ms "
          f"min={summary['min_ms']:.3f}ms max={summary['max_ms']:.3f}ms "
          f"p50={summary['p50_ms']:.3f}ms p90={summary['p90_ms']:.3f}ms "
          f"p95={summary['p95_ms']:.3f}ms p99={summary['p99_ms']:.3f}ms "
          f"p99.9={summary['p999_ms']:.3f}ms")


def key_for(rng, args, prefix="key"):
    if args.key_distribution == "uniform":
        index = rng.randrange(args.keyspace)
    else:
        # Zipf-like hot set without introducing a third-party dependency.
        index = min(int(args.keyspace * (rng.random() ** 5)), args.keyspace - 1)
    return f"{prefix}-{index}"


def read_response(reader):
    line = reader.readline()
    if not line:
        raise ConnectionError("incomplete response: peer closed before newline")
    return line.rstrip(b"\r\n")


def execute(sock, reader, command):
    sock.sendall(command.encode("utf-8"))
    return read_response(reader)


class PhaseCoordinator:
    """A failure-safe two-phase barrier with a dynamically known participant set."""
    def __init__(self, expected):
        self.expected = expected
        self.connection_attempts = 0
        self.connected = 0
        self.warmup_finished = 0
        self.warmup_ready = 0
        self.condition = threading.Condition()
        self.start_warmup = threading.Event()
        self.start_measurement = threading.Event()

    def connection_complete(self, usable):
        with self.condition:
            self.connection_attempts += 1
            self.connected += int(usable)
            self.condition.notify_all()

    def warmup_complete(self, ready):
        with self.condition:
            self.warmup_finished += 1
            self.warmup_ready += int(ready)
            self.condition.notify_all()

    def wait_for_connections(self, timeout):
        with self.condition:
            return self.condition.wait_for(lambda: self.connection_attempts == self.expected, timeout)

    def wait_for_warmup(self, timeout):
        with self.condition:
            return self.condition.wait_for(lambda: self.warmup_finished == self.connected, timeout)


def command_for(rng, args):
    operation = "GET" if rng.random() < args.get_ratio else "SET"
    if operation == "SET":
        key = key_for(rng, args)
        return operation, f"SET {key} value-{key} {args.ttl_ms}\n"
    is_hit = args.prepopulate_keys > 0 and rng.random() < args.get_hit_ratio
    key = key_for(rng, args) if is_hit else key_for(rng, args, "miss")
    return operation, f"GET {key}\n"


def warm_up(sock, reader, rng, args):
    deadline = time.perf_counter() + args.warmup
    while time.perf_counter() < deadline:
        _, command = command_for(rng, args)
        response = execute(sock, reader, command)
        if response.startswith(b"ERROR"):
            raise ConnectionError(response.decode("utf-8", "replace"))


def benchmark_client(client_id, args, phases, results):
    rng = random.Random(args.seed + client_id)
    result = {"connection": "failed", "warmup_errors": 0, "latencies": defaultdict(list), "operations": Counter(), "errors": Counter()}
    connection_reported = False
    try:
        with socket.create_connection((args.host, args.port), args.connect_timeout) as sock:
            sock.settimeout(args.socket_timeout)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            reader = sock.makefile("rb")
            handshake = execute(sock, reader, "STATS\n")
            if handshake == b"ERROR server busy":
                result["connection"] = "rejected"
                phases.connection_complete(False)
                connection_reported = True
                return
            if handshake.startswith(b"ERROR"):
                result["errors"]["handshake"] += 1
                phases.connection_complete(False)
                connection_reported = True
                return
            result["connection"] = "successful"
            phases.connection_complete(True)
            connection_reported = True
            phases.start_warmup.wait()
            try:
                warm_up(sock, reader, rng, args)
            except (OSError, ConnectionError):
                result["warmup_errors"] += 1
                phases.warmup_complete(False)
                return
            phases.warmup_complete(True)
            phases.start_measurement.wait()
            deadline = time.perf_counter() + args.duration if args.duration else None
            completed = 0
            while (deadline is None or time.perf_counter() < deadline) and (args.requests == 0 or completed < args.requests):
                operation, command = command_for(rng, args)
                started = time.perf_counter_ns()
                try:
                    answer = execute(sock, reader, command)
                    elapsed = time.perf_counter_ns() - started
                    if answer.startswith(b"ERROR"):
                        result["errors"][operation] += 1
                    else:
                        result["latencies"][operation].append(elapsed)
                        result["operations"][operation] += 1
                        if operation == "GET":
                            result["operations"]["GET_MISS" if answer == b"NULL" else "GET_HIT"] += 1
                except (OSError, ConnectionError) as exc:
                    result["errors"][operation] += 1
                    result["errors"][type(exc).__name__] += 1
                    break
                completed += 1
    except (OSError, ConnectionError) as exc:
        result["errors"][type(exc).__name__] += 1
    finally:
        if not connection_reported:
            phases.connection_complete(False)
        results[client_id] = result


def prepopulate(args):
    attempted = args.prepopulate_keys
    succeeded = 0
    if not attempted:
        return {"attempted": 0, "successful": 0, "failed": 0}
    with socket.create_connection((args.host, args.port), args.connect_timeout) as sock:
        sock.settimeout(args.socket_timeout)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        reader = sock.makefile("rb")
        for index in range(attempted):
            answer = execute(sock, reader, f"SET key-{index} value-key-{index} {args.ttl_ms}\n")
            if answer != b"OK":
                raise RuntimeError(f"prepopulation failed at key-{index}: {answer.decode('utf-8', 'replace')}")
            succeeded += 1
    return {"attempted": attempted, "successful": succeeded, "failed": attempted - succeeded}


def run(args, number):
    setup = prepopulate(args)
    phases, results = PhaseCoordinator(args.clients), {}
    threads = [threading.Thread(target=benchmark_client, args=(client, args, phases, results), daemon=True)
               for client in range(args.clients)]
    for thread in threads:
        thread.start()
    phase_timeout = args.connect_timeout + args.socket_timeout + 10
    if not phases.wait_for_connections(phase_timeout):
        raise RuntimeError("clients did not finish connection setup before the phase timeout")
    phases.start_warmup.set()
    if not phases.wait_for_warmup(args.warmup + args.socket_timeout + 10):
        raise RuntimeError("clients did not finish warm-up before the phase timeout")
    started = time.perf_counter_ns()
    phases.start_measurement.set()
    for thread in threads:
        thread.join(args.socket_timeout + (args.duration or 0) + 10)
    if any(thread.is_alive() for thread in threads):
        raise RuntimeError("a benchmark client did not finish")

    elapsed = (time.perf_counter_ns() - started) / 1e9
    latencies, operations, errors, connections = defaultdict(list), Counter(), Counter(), Counter()
    warmup_errors = 0
    for result in results.values():
        connections[result["connection"]] += 1
        warmup_errors += result["warmup_errors"]
        for operation, values in result["latencies"].items():
            latencies[operation].extend(values)
        operations.update(result["operations"])
        errors.update(result["errors"])
    successful = operations["GET"] + operations["SET"]
    failed = errors["GET"] + errors["SET"]
    hits, misses = operations["GET_HIT"], operations["GET_MISS"]
    summaries = {"overall": latency_summary(latencies["GET"] + latencies["SET"]),
                 "GET": latency_summary(latencies["GET"]), "SET": latency_summary(latencies["SET"])}
    hit_ratio = 100 * hits / (hits + misses) if hits + misses else None
    report = {
        "run": number, "elapsed_seconds": elapsed, "setup": setup, "connections": dict(connections),
        "warmup_errors": warmup_errors, "successful_operations": successful, "failed_operations": failed,
        "error_rate_percent": 100 * failed / max(1, successful + failed),
        "throughput_ops_per_second": successful / elapsed, "get_throughput_ops_per_second": operations["GET"] / elapsed,
        "set_throughput_ops_per_second": operations["SET"] / elapsed, "get_hits": hits, "get_misses": misses,
        "get_hit_ratio_percent": hit_ratio, "latency_ms": summaries, "errors": dict(errors),
    }
    print(f"\n=== Run {number} ===\nsetup: attempted={setup['attempted']} successful={setup['successful']} failed={setup['failed']}")
    print(f"connections: successful={connections['successful']} rejected={connections['rejected']} failed={connections['failed']} warmup_errors={warmup_errors}")
    print(f"operations: successful={successful} failed={failed} error_rate={report['error_rate_percent']:.3f}%")
    print(f"throughput: overall={report['throughput_ops_per_second']:.2f} ops/s GET={report['get_throughput_ops_per_second']:.2f} ops/s SET={report['set_throughput_ops_per_second']:.2f} ops/s")
    hit_ratio_text = "n/a" if hit_ratio is None else f"{hit_ratio:.3f}%"
    print(f"GET outcomes: hits={hits} misses={misses} hit_ratio={hit_ratio_text}")
    print_latency("overall", summaries["overall"])
    print_latency("GET", summaries["GET"])
    print_latency("SET", summaries["SET"])
    if errors:
        print("errors: " + ", ".join(f"{kind}={count}" for kind, count in sorted(errors.items())))
    return report


def main():
    parser = argparse.ArgumentParser(description="FlashKV persistent-connection TCP benchmark")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--clients", type=int, default=32)
    parser.add_argument("--requests", type=int, default=10000, help="per client; use 0 with --duration")
    parser.add_argument("--duration", type=float, default=0, help="measurement seconds; an additional stop condition")
    parser.add_argument("--warmup", type=float, default=10)
    parser.add_argument("--get-ratio", type=float, default=.8)
    parser.add_argument("--get-hit-ratio", type=float, default=1.0, help="target fraction of GETs selecting prepopulated keys")
    parser.add_argument("--keyspace", type=int, default=100000)
    parser.add_argument("--prepopulate-keys", type=int, default=None, help="initial key count; defaults to --keyspace, zero disables")
    parser.add_argument("--key-distribution", choices=("uniform", "zipf"), default="uniform")
    parser.add_argument("--ttl-ms", type=int, default=60000)
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--connect-timeout", type=float, default=5)
    parser.add_argument("--socket-timeout", type=float, default=10)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--results-json", help="write machine-readable results after all runs")
    args = parser.parse_args()
    if args.prepopulate_keys is None:
        args.prepopulate_keys = args.keyspace
    if (args.clients <= 0 or args.requests < 0 or args.duration < 0 or args.warmup < 0 or args.keyspace <= 0 or
            args.prepopulate_keys < 0 or args.prepopulate_keys > args.keyspace or args.runs <= 0 or
            not 0 <= args.get_ratio <= 1 or not 0 <= args.get_hit_ratio <= 1):
        parser.error("invalid benchmark argument")
    if args.requests == 0 and args.duration == 0:
        parser.error("provide --requests > 0 or --duration > 0")
    print("Client-observed latency: sendall() through one complete newline-delimited response.")
    print(f"host={args.host} port={args.port} clients={args.clients} requests/client={args.requests} duration={args.duration}s warmup={args.warmup}s "
          f"workload={args.get_ratio:.0%} GET target_hit_ratio={args.get_hit_ratio:.0%} distribution={args.key_distribution} "
          f"keyspace={args.keyspace} prepopulate={args.prepopulate_keys} runs={args.runs}")
    reports = [run(args, number) for number in range(1, args.runs + 1)]
    print("\n=== Run summary ===")
    for report in reports:
        summary_hit_ratio = report["get_hit_ratio_percent"]
        summary_hit_text = "n/a" if summary_hit_ratio is None else f"{summary_hit_ratio:.3f}%"
        print(f"run={report['run']} connections={report['connections'].get('successful', 0)} rejected={report['connections'].get('rejected', 0)} "
              f"throughput={report['throughput_ops_per_second']:.2f} ops/s failures={report['failed_operations']} "
              f"hit_ratio={summary_hit_text}")
    if args.results_json:
        with open(args.results_json, "w", encoding="utf-8") as output:
            json.dump({"configuration": vars(args), "runs": reports}, output, indent=2)


if __name__ == "__main__":
    main()
