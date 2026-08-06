#!/usr/bin/env python3
"""netshape — a whole-app network shaper for NewTube bad-network testing.

Why this exists
---------------
Two bench approaches were tried before and both are dead ends:

* ``adb emu network speed`` — does not shape, it *stalls*. Measured 0 API
  completions in 30 s at 4000/2000/1000 kbps. ``adb emu network delay`` only
  applies to NEW connections, so H2-multiplexed requests are unaffected.
* Bright Data residential/ISP/datacenter proxies — refuse ``CONNECT
  www.youtube.com:443`` and ``CONNECT *.googlevideo.com:443`` with
  ``x-brd-err-code: policy_20050`` (YouTube is KYC-gated on their compliance
  list). No credential or zone change fixes that.
* The in-app ``DebugMediaShaper`` shapes only the media leaf DataSource, so
  the interesting failure — API + thumbnails + media *contending for one
  link* — has never been reproducible.

netshape is an HTTP proxy that all app traffic is pointed at, with ONE shared
token bucket per direction. Contention is therefore real: a thumbnail burst
genuinely steals bytes from the media stream, exactly as on a phone.

Run it in WSL; the emulator reaches it at ``10.0.2.2:<port>`` (emulator ->
Windows loopback -> WSL localhost forwarding).

    python3 tools/netshape.py --port 18080 --control 18081 \
        --down-kbps 1500 --up-kbps 600 --rtt-ms 60

Control it live (no restart, so a profile can change mid-playback):

    curl -s 'localhost:18081/set?down=400&up=200&rtt=120'
    curl -s 'localhost:18081/set?blackout=1'     # enter a tunnel
    curl -s 'localhost:18081/set?blackout=0'     # leave it
    curl -s localhost:18081/stats                # per-host bytes + timeline
    curl -s localhost:18081/reset                # zero the counters

Blackout semantics matter: a real tunnel does not RST, it silently drops
packets, so the socket stalls until a timeout fires. Blackout therefore
*stalls* transfers and blackholes new connects rather than closing them --
that is precisely the "went into a tunnel and it never came back" case.
"""

from __future__ import annotations

import argparse
import asyncio
import collections
import re
import time
from urllib.parse import urlsplit, parse_qs

# Read granularity. Small enough that the shaper reacts quickly, large enough
# that a 10 Mbps link does not drown in syscalls.
CHUNK = 16 * 1024
# Bucket burst, in seconds of the configured rate. A real radio link has some
# buffer; zero burst produces unrealistically metronomic delivery.
BURST_SECONDS = 0.25
# Cap on a single sleep inside the bucket, so a rate change mid-wait is picked
# up promptly instead of after a multi-second sleep at the old rate.
MAX_SLEEP = 0.05


class Shape:
    """Live-mutable shaping parameters, shared by every connection."""

    def __init__(self, down_kbps: float, up_kbps: float, rtt_ms: float):
        self.down_kbps = down_kbps
        self.up_kbps = up_kbps
        self.rtt_ms = rtt_ms
        self.blackout = False
        # Set when traffic may flow; cleared for the duration of a blackout.
        self.flowing = asyncio.Event()
        self.flowing.set()

    def set_blackout(self, on: bool) -> None:
        self.blackout = on
        if on:
            self.flowing.clear()
        else:
            self.flowing.set()

    def describe(self) -> str:
        return (f"down={self.down_kbps}kbps up={self.up_kbps}kbps "
                f"rtt={self.rtt_ms}ms blackout={self.blackout}")


class TokenBucket:
    """One bucket shared across all connections in a direction.

    Sharing is the whole point: it is what makes thumbnail loads compete with
    the media stream for the same bytes, which a per-DataSource shaper cannot
    reproduce.
    """

    def __init__(self, shape: Shape, direction: str):
        self._shape = shape
        self._direction = direction
        self._tokens = 0.0
        self._ts = time.monotonic()
        self._lock = asyncio.Lock()

    def _rate_bytes(self) -> float:
        kbps = self._shape.down_kbps if self._direction == "down" else self._shape.up_kbps
        return kbps * 1000.0 / 8.0

    async def take(self, n: int) -> None:
        while n > 0:
            rate = self._rate_bytes()
            if rate <= 0:  # 0 == unlimited
                return
            async with self._lock:
                now = time.monotonic()
                capacity = rate * BURST_SECONDS
                self._tokens = min(capacity, self._tokens + (now - self._ts) * rate)
                self._ts = now
                grant = min(float(n), self._tokens)
                self._tokens -= grant
                n -= int(grant)
                wait = (n / rate) if n > 0 else 0.0
            if wait > 0:
                await asyncio.sleep(min(wait, MAX_SLEEP))


class Stats:
    def __init__(self):
        self.bytes_down: dict[str, int] = collections.defaultdict(int)
        self.bytes_up: dict[str, int] = collections.defaultdict(int)
        self.conns: dict[str, int] = collections.defaultdict(int)
        self.errors: dict[str, int] = collections.defaultdict(int)
        # second-resolution downlink timeline per host, for attributing a
        # stall to whoever was actually holding the link at that moment.
        self.timeline: dict[int, dict[str, int]] = collections.defaultdict(
            lambda: collections.defaultdict(int))
        self.t0 = time.monotonic()

    def add_down(self, host: str, n: int) -> None:
        self.bytes_down[host] += n
        self.timeline[int(time.monotonic() - self.t0)][host] += n

    def reset(self) -> None:
        self.bytes_down.clear()
        self.bytes_up.clear()
        self.conns.clear()
        self.errors.clear()
        self.timeline.clear()
        self.t0 = time.monotonic()


def bucket_host(host: str) -> str:
    """Collapse YouTube's per-edge CDN names so stats stay readable.

    ``rr3---sn-h0jeenl6.googlevideo.com`` and its dozen siblings are one
    logical peer; keeping them separate would bury the media row in noise.
    """
    if host.endswith(".googlevideo.com"):
        return "*.googlevideo.com"
    if re.match(r"^(i|s)\d?\.ytimg\.com$", host):
        return "*.ytimg.com"
    if host.endswith(".ggpht.com"):
        return "*.ggpht.com"
    return host


class Proxy:
    def __init__(self, shape: Shape, stats: Stats, verbose: bool):
        self.shape = shape
        self.stats = stats
        self.verbose = verbose
        self.down = TokenBucket(shape, "down")
        self.up = TokenBucket(shape, "up")

    async def _pump(self, reader, writer, bucket, host, is_down: bool) -> None:
        try:
            while True:
                # A blackout must stall, not fail: that is what a tunnel does.
                await self.shape.flowing.wait()
                data = await reader.read(CHUNK)
                if not data:
                    break
                await bucket.take(len(data))
                await self.shape.flowing.wait()
                writer.write(data)
                await writer.drain()
                if is_down:
                    self.stats.add_down(host, len(data))
                else:
                    self.stats.bytes_up[host] += len(data)
        except (ConnectionResetError, BrokenPipeError, asyncio.IncompleteReadError):
            pass
        except Exception:
            self.stats.errors[host] += 1
        finally:
            try:
                writer.close()
            except Exception:
                pass

    async def _latency(self) -> None:
        if self.shape.rtt_ms > 0:
            await asyncio.sleep(self.shape.rtt_ms / 1000.0)

    async def handle(self, creader, cwriter) -> None:
        try:
            header = await asyncio.wait_for(creader.readuntil(b"\r\n\r\n"), timeout=30)
        except Exception:
            cwriter.close()
            return

        first = header.split(b"\r\n", 1)[0].decode("latin-1", "replace")
        parts = first.split()
        if len(parts) < 3:
            cwriter.close()
            return
        method, target = parts[0], parts[1]

        if method.upper() == "CONNECT":
            host, _, port_s = target.rpartition(":")
            port = int(port_s or 443)
        else:
            u = urlsplit(target)
            host, port = u.hostname or "", u.port or 80
        if not host:
            cwriter.close()
            return

        key = bucket_host(host)
        self.stats.conns[key] += 1
        if self.verbose:
            print(f"[conn] {method} {host}:{port}", flush=True)

        # Blackholed connects are what a tunnel really does to a new socket:
        # the SYN goes nowhere and the client waits out its connect timeout.
        if self.shape.blackout:
            await self.shape.flowing.wait()

        await self._latency()  # one RTT to stand the connection up
        try:
            sreader, swriter = await asyncio.wait_for(
                asyncio.open_connection(host, port), timeout=30)
        except Exception:
            self.stats.errors[key] += 1
            try:
                cwriter.write(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
                await cwriter.drain()
            except Exception:
                pass
            cwriter.close()
            return

        if method.upper() == "CONNECT":
            cwriter.write(b"HTTP/1.1 200 Connection established\r\n\r\n")
            await cwriter.drain()
        else:
            # Replay the request line and headers we already consumed.
            swriter.write(header)
            await swriter.drain()

        await asyncio.gather(
            self._pump(sreader, cwriter, self.down, key, True),
            self._pump(creader, swriter, self.up, key, False),
        )


async def control_handler(reader, writer, shape: Shape, stats: Stats):
    try:
        line = await asyncio.wait_for(reader.readline(), timeout=5)
    except Exception:
        writer.close()
        return
    try:
        path = line.decode().split()[1]
    except Exception:
        writer.close()
        return
    u = urlsplit(path)
    q = parse_qs(u.query)
    body = ""

    if u.path == "/set":
        if "down" in q:
            shape.down_kbps = float(q["down"][0])
        if "up" in q:
            shape.up_kbps = float(q["up"][0])
        if "rtt" in q:
            shape.rtt_ms = float(q["rtt"][0])
        if "blackout" in q:
            shape.set_blackout(q["blackout"][0] not in ("0", "false", ""))
        body = shape.describe() + "\n"
        print(f"[ctl] {shape.describe()}", flush=True)
    elif u.path == "/stats":
        elapsed = max(0.001, time.monotonic() - stats.t0)
        rows = sorted(stats.bytes_down.items(), key=lambda kv: -kv[1])
        total = sum(stats.bytes_down.values())
        body = f"elapsed={elapsed:.1f}s total_down={total/1024:.0f}kB " \
               f"mean={total*8/1000/elapsed:.0f}kbps\n"
        body += f"{'host':<34}{'down_kB':>10}{'up_kB':>9}{'conns':>7}{'err':>5}\n"
        for host, n in rows:
            body += (f"{host:<34}{n/1024:>10.0f}{stats.bytes_up.get(host,0)/1024:>9.0f}"
                     f"{stats.conns.get(host,0):>7}{stats.errors.get(host,0):>5}\n")
    elif u.path == "/timeline":
        keys = sorted(stats.timeline)
        body = "sec  " + "  ".join(sorted({h for s in stats.timeline.values() for h in s})) + "\n"
        for sec in keys:
            row = stats.timeline[sec]
            body += f"{sec:<5}" + "  ".join(
                f"{h}={row.get(h,0)//1024}kB" for h in sorted(row)) + "\n"
    elif u.path == "/reset":
        stats.reset()
        body = "reset\n"
    else:
        body = "usage: /set?down=&up=&rtt=&blackout= | /stats | /timeline | /reset\n"

    payload = body.encode()
    writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: "
                 + str(len(payload)).encode() + b"\r\nConnection: close\r\n\r\n" + payload)
    await writer.drain()
    writer.close()


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18080)
    ap.add_argument("--control", type=int, default=18081)
    ap.add_argument("--down-kbps", type=float, default=0, help="0 = unlimited")
    ap.add_argument("--up-kbps", type=float, default=0)
    ap.add_argument("--rtt-ms", type=float, default=0)
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    shape = Shape(args.down_kbps, args.up_kbps, args.rtt_ms)
    stats = Stats()
    proxy = Proxy(shape, stats, args.verbose)

    srv = await asyncio.start_server(proxy.handle, "0.0.0.0", args.port)
    ctl = await asyncio.start_server(
        lambda r, w: control_handler(r, w, shape, stats), "0.0.0.0", args.control)
    print(f"netshape proxy=:{args.port} control=:{args.control} {shape.describe()}",
          flush=True)
    async with srv, ctl:
        await asyncio.gather(srv.serve_forever(), ctl.serve_forever())


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
