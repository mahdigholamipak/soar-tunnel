import sys
import time
import socket
import struct
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor

SOCKS_HOST = "127.0.0.1"
SOCKS_PORT = 10851

TEST_TARGETS = [
    ("Google", "http://connectivitycheck.gstatic.com/generate_204"),
    ("Cloudflare", "http://cp.cloudflare.com/generate_204"),
    ("FastPing", "http://1.1.1.1/cdn-cgi/trace"),
]

DOWNLOAD_TARGET = "http://speed.cloudflare.com/__down?bytes=1048576" # 1MB download

def socks5_connect(dest_host, dest_port, socks_host=None, socks_port=None, timeout=12):
    if socks_host is None: socks_host = SOCKS_HOST
    if socks_port is None: socks_port = SOCKS_PORT
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    s.connect((socks_host, socks_port))
    
    # 1. Greeting: No Auth (0x00)
    s.sendall(b"\x05\x01\x00")
    resp = s.recv(2)
    if len(resp) < 2 or resp[0] != 0x05 or resp[1] != 0x00:
        s.close()
        raise Exception(f"SOCKS5 auth negotiation failed: {resp}")
        
    # 2. Connect request: Command 0x01 (CONNECT), RSVP 0x00, Atyp 0x03 (Domain)
    domain_bytes = dest_host.encode("utf-8")
    req = struct.pack("!BBBB", 0x05, 0x01, 0x00, 0x03) + struct.pack("!B", len(domain_bytes)) + domain_bytes + struct.pack("!H", dest_port)
    s.sendall(req)
    
    reply = s.recv(4)
    if len(reply) < 4 or reply[1] != 0x00:
        s.close()
        raise Exception(f"SOCKS5 connect rejected with status: {reply[1] if len(reply)>1 else 'empty'}")
        
    # Read remainder of bind addr
    atyp = reply[3]
    if atyp == 0x01: # IPv4
        s.recv(4 + 2)
    elif atyp == 0x03: # Domain
        l = s.recv(1)[0]
        s.recv(l + 2)
    elif atyp == 0x04: # IPv6
        s.recv(16 + 2)
        
    return s

def test_handshake_latency(target_host="cp.cloudflare.com", target_port=80):
    t0 = time.time()
    try:
        s = socks5_connect(target_host, target_port)
        handshake_ms = (time.time() - t0) * 1000.0
        
        # Send HTTP GET
        req = f"GET /generate_204 HTTP/1.1\r\nHost: {target_host}\r\nConnection: close\r\nUser-Agent: SoarBench/1.4\r\n\r\n"
        s.sendall(req.encode())
        
        resp = s.recv(1024)
        total_ms = (time.time() - t0) * 1000.0
        s.close()
        
        status = "OK" if b"204" in resp or b"200" in resp else "STATUS?"
        return True, handshake_ms, total_ms, status
    except Exception as e:
        return False, 0.0, 0.0, str(e)

def test_stream_download(size_bytes=1048576):
    t0 = time.time()
    try:
        s = socks5_connect("speed.cloudflare.com", 80)
        req = f"GET /__down?bytes={size_bytes} HTTP/1.1\r\nHost: speed.cloudflare.com\r\nConnection: close\r\nUser-Agent: SoarBench/1.4\r\n\r\n"
        s.sendall(req.encode())
        
        received = 0
        header_passed = False
        while True:
            chunk = s.recv(16384)
            if not chunk:
                break
            if not header_passed:
                idx = chunk.find(b"\r\n\r\n")
                if idx != -1:
                    header_passed = True
                    received += len(chunk) - (idx + 4)
                else:
                    continue
            else:
                received += len(chunk)
                
        elapsed = time.time() - t0
        s.close()
        speed_kb = (received / 1024.0) / elapsed if elapsed > 0 else 0
        return True, received, elapsed, speed_kb
    except Exception as e:
        return False, 0, 0.0, str(e)

def run_benchmark():
    print(f"=== SOAR DNS TUNNEL v1.4.0 BENCHMARK SUITE ===")
    print(f"Testing SOCKS5 endpoint: {SOCKS_HOST}:{SOCKS_PORT}\n")
    
    # 1. Connectivity & Handshake Latency Tests
    print("--- Phase 1: Handshake & Round-Trip Latency ---")
    results = []
    for name, _ in TEST_TARGETS:
        host = "connectivitycheck.gstatic.com" if name == "Google" else ("cp.cloudflare.com" if name == "Cloudflare" else "1.1.1.1")
        success, hs_ms, tot_ms, status = test_handshake_latency(host)
        if success:
            print(f"  [+] {name:12}: Handshake={hs_ms:6.1f}ms | TTFB={tot_ms:6.1f}ms | Status={status}")
            results.append((hs_ms, tot_ms))
        else:
            print(f"  [-] {name:12}: Failed ({status})")
            
    # 2. Single-Stream Throughput Test
    print("\n--- Phase 2: Single-Stream Throughput (1 MB Payload) ---")
    ok, rec, el, spd = test_stream_download(1048576)
    if ok:
        print(f"  [+] Transferred: {rec/1024.0:.1f} KB in {el:.2f}s -> Throughput: {spd:.1f} KB/s ({spd*8.0/1024.0:.2f} Mbps)")
    else:
        print(f"  [-] Download failed: {spd}")
        
    # 3. 4-Client Concurrent Stress Test
    print("\n--- Phase 3: 4 Concurrent Streams Stress Test ---")
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=4) as executor:
        futures = [executor.submit(test_stream_download, 524288) for _ in range(4)]
        concurrent_results = [f.result() for f in futures]
    
    tot_rec = sum(r[1] for r in concurrent_results if r[0])
    wall_time = time.time() - t0
    agg_speed = (tot_rec / 1024.0) / wall_time if wall_time > 0 else 0
    success_count = sum(1 for r in concurrent_results if r[0])
    
    print(f"  [+] Concurrent Success: {success_count}/4 streams completed")
    print(f"  [+] Total Data: {tot_rec/1024.0:.1f} KB in {wall_time:.2f}s")
    print(f"  [+] Aggregate Throughput: {agg_speed:.1f} KB/s ({agg_speed*8.0/1024.0:.2f} Mbps)")
    print("\n===============================================")

if __name__ == "__main__":
    if len(sys.argv) > 1:
        SOCKS_PORT = int(sys.argv[1])
    run_benchmark()
