import socket
import time
import threading
import random
import statistics
import sys
import argparse

def worker(num_requests, port, results):
    latencies = []
    
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.connect(('127.0.0.1', port))
        
        for _ in range(num_requests):
            is_set = random.random() < 0.2  # 20% writes, 80% reads
            key = f"key_{random.randint(1, 1000)}"
            
            if is_set:
                cmd = f"SET {key} 60000 val_{key}\n"
            else:
                cmd = f"GET {key}\n"
                
            start = time.perf_counter()
            s.sendall(cmd.encode('utf-8'))
            resp = s.recv(1024)
            end = time.perf_counter()
            
            latencies.append((end - start) * 1000) # ms
            
        s.close()
    except Exception as e:
        print(f"Error in worker: {e}")
        
    results.extend(latencies)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--clients", type=int, default=250)
    parser.add_argument("--requests", type=int, default=100) # per client
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()
    
    clients = args.clients
    requests_per_client = args.requests
    port = args.port
    
    print(f"Starting benchmark with {clients} concurrent clients, each doing {requests_per_client} requests...")
    
    threads = []
    all_latencies = []
    
    start_time = time.time()
    
    for _ in range(clients):
        t = threading.Thread(target=worker, args=(requests_per_client, port, all_latencies))
        threads.append(t)
        t.start()
        
    for t in threads:
        t.join()
        
    end_time = time.time()
    
    total_time = end_time - start_time
    total_requests = len(all_latencies)
    
    if total_requests == 0:
        print("No successful requests.")
        return
        
    avg_latency = statistics.mean(all_latencies)
    p95_latency = statistics.quantiles(all_latencies, n=100)[94] if len(all_latencies) > 1 else avg_latency
    p99_latency = statistics.quantiles(all_latencies, n=100)[98] if len(all_latencies) > 1 else avg_latency
    throughput = total_requests / total_time
    
    print("\n--- Benchmark Results ---")
    print(f"Total Requests: {total_requests}")
    print(f"Total Time: {total_time:.2f} seconds")
    print(f"Throughput: {throughput:.2f} requests/sec")
    print(f"Average Latency: {avg_latency:.2f} ms")
    print(f"P95 Latency: {p95_latency:.2f} ms")
    print(f"P99 Latency: {p99_latency:.2f} ms")

if __name__ == "__main__":
    main()
