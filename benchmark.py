#!/usr/bin/env python3
"""Persistent TCP benchmark for FlashKV.

Latency is client-observed round-trip time from sendall() through receipt of one
newline-delimited response. It includes client scheduling, TCP/socket buffering,
server queueing, command execution, and response transmission.
"""
import argparse
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
    position = (len(values) - 1) * pct / 100
    lo, hi = math.floor(position), math.ceil(position)
    return values[lo] if lo == hi else values[lo] + (values[hi] - values[lo]) * (position - lo)


def print_latency(name, values):
    if not values:
        print(f"  {name:<7} no successful operations")
        return
    values.sort()
    ms = lambda ns: ns / 1_000_000
    print(f"  {name:<7} count={len(values):<8} mean={ms(statistics.mean(values)):.3f}ms "
          f"min={ms(values[0]):.3f}ms max={ms(values[-1]):.3f}ms "
          f"p50={ms(percentile(values, 50)):.3f}ms p90={ms(percentile(values, 90)):.3f}ms "
          f"p95={ms(percentile(values, 95)):.3f}ms p99={ms(percentile(values, 99)):.3f}ms "
          f"p99.9={ms(percentile(values, 99.9)):.3f}ms")


def key_for(rng, args):
    if args.key_distribution == "uniform":
        return rng.randrange(args.keyspace)
    # Hot-set / Zipf-like distribution without a third-party dependency.
    return min(int(args.keyspace * (rng.random() ** 5)), args.keyspace - 1)


def response(reader):
    line = reader.readline()
    if not line:
        raise ConnectionError("incomplete response: peer closed before newline")
    return line.rstrip(b"\r\n")


def client(client_id, args, start, measure, results):
    rng, lats, ok, errors = random.Random(args.seed + client_id), defaultdict(list), Counter(), Counter()
    try:
        with socket.create_connection((args.host, args.port), args.connect_timeout) as sock:
            sock.settimeout(args.socket_timeout)
            reader = sock.makefile("rb")
            start.wait()
            until = time.perf_counter() + args.warmup
            while time.perf_counter() < until:
                sock.sendall(f"SET warm-{client_id}-{key_for(rng, args)} 60000 value\n".encode())
                response(reader)
            measure.wait()
            deadline = time.perf_counter() + args.duration if args.duration else None
            sent = 0
            while (deadline is None or time.perf_counter() < deadline) and (args.requests == 0 or sent < args.requests):
                op = "GET" if rng.random() < args.get_ratio else "SET"
                key = key_for(rng, args)
                command = f"GET key-{key}\n" if op == "GET" else f"SET key-{key} {args.ttl_ms} value-{key}\n"
                started = time.perf_counter_ns()
                try:
                    sock.sendall(command.encode())
                    answer = response(reader)
                    if answer.startswith(b"ERROR"):
                        errors[op] += 1
                    else:
                        lats[op].append(time.perf_counter_ns() - started)
                        ok[op] += 1
                except (OSError, ConnectionError) as exc:
                    errors[op] += 1
                    errors[type(exc).__name__] += 1
                    break
                sent += 1
    except (OSError, ConnectionError) as exc:
        errors["connection"] += 1
        errors[type(exc).__name__] += 1
    results[client_id] = lats, ok, errors


def run(args, number):
    results = {}
    start, measure = threading.Event(), threading.Event()
    threads = [threading.Thread(target=client, args=(i, args, start, measure, results), daemon=True)
               for i in range(args.clients)]
    for thread in threads: thread.start()
    start.set()
    # A failed connection does not hold up the other clients or deadlock a run.
    time.sleep(args.warmup)
    started = time.perf_counter_ns()
    measure.set()
    for thread in threads: thread.join()
    seconds = (time.perf_counter_ns() - started) / 1e9
    lats, ok, errors = defaultdict(list), Counter(), Counter()
    for client_lats, client_ok, client_errors in results.values():
        for op, values in client_lats.items(): lats[op].extend(values)
        ok.update(client_ok); errors.update(client_errors)
    success = sum(ok.values())
    failed = sum(errors[op] for op in ("GET", "SET", "connection"))
    print(f"\n=== Run {number} ===\nmeasurement={seconds:.3f}s successful={success} failed={failed} "
          f"error_rate={100 * failed / max(1, success + failed):.3f}%")
    print(f"throughput overall={success / seconds:.2f} ops/s GET={ok['GET'] / seconds:.2f} ops/s SET={ok['SET'] / seconds:.2f} ops/s")
    print_latency("overall", lats["GET"] + lats["SET"])
    print_latency("GET", lats["GET"])
    print_latency("SET", lats["SET"])
    if errors: print("errors: " + ", ".join(f"{k}={v}" for k, v in sorted(errors.items())))
    return success / seconds, failed


def main():
    parser = argparse.ArgumentParser(description="FlashKV persistent-connection TCP benchmark")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--clients", type=int, default=32)
    parser.add_argument("--requests", type=int, default=10000, help="per client; use 0 with --duration")
    parser.add_argument("--duration", type=float, default=0, help="measurement seconds; an additional stop condition")
    parser.add_argument("--warmup", type=float, default=10)
    parser.add_argument("--get-ratio", type=float, default=.8)
    parser.add_argument("--keyspace", type=int, default=100000)
    parser.add_argument("--key-distribution", choices=("uniform", "zipf"), default="uniform")
    parser.add_argument("--ttl-ms", type=int, default=60000)
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--connect-timeout", type=float, default=5)
    parser.add_argument("--socket-timeout", type=float, default=10)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()
    if args.clients <= 0 or args.requests < 0 or args.duration < 0 or args.warmup < 0 or args.keyspace <= 0 or args.runs <= 0 or not 0 <= args.get_ratio <= 1:
        parser.error("invalid benchmark argument")
    if args.requests == 0 and args.duration == 0: parser.error("provide --requests > 0 or --duration > 0")
    print("Client-observed latency: sendall() through one complete newline-delimited response.")
    print(f"host={args.host} port={args.port} clients={args.clients} requests/client={args.requests} duration={args.duration}s warmup={args.warmup}s "
          f"workload={args.get_ratio:.0%} GET distribution={args.key_distribution} runs={args.runs}")
    summary = [run(args, i) for i in range(1, args.runs + 1)]
    print("\n=== Run summary ===")
    for i, (throughput, failures) in enumerate(summary, 1): print(f"run={i} throughput={throughput:.2f} ops/s failures={failures}")


if __name__ == "__main__":
    main()
