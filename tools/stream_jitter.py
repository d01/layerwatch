#!/usr/bin/env python3
"""Measure X1C camera delivery quality: frame arrival gaps as seen by this host.

Usage: stream_jitter.py HOST ACCESS_CODE [SECONDS]
Reports frame rate and inter-arrival gap percentiles; gaps >= 250 ms are what the
eye sees as stutter, >= 1 s as a freeze.
"""
import subprocess, sys, time

host, code = sys.argv[1], sys.argv[2]
secs = float(sys.argv[3]) if len(sys.argv) > 3 else 60
cmd = ["ffprobe", "-v", "error", "-rtsp_transport", "tcp", "-tls_verify", "0",
       "-select_streams", "v", "-show_entries", "packet=pts_time,size,flags", "-of", "csv=p=0",
       f"rtsps://bblp:{code}@{host}:322/streaming/live/1"]
p = subprocess.Popen(cmd, stdout=subprocess.PIPE, text=True, bufsize=1)
arrivals, sizes, start = [], [], time.monotonic()
for line in p.stdout:
    now = time.monotonic()
    if now - start > secs:
        break
    parts = line.strip().split(",")
    if len(parts) >= 2 and parts[1].isdigit():
        arrivals.append(now); sizes.append(int(parts[1]))
p.kill()
gaps = sorted((b - a) * 1000 for a, b in zip(arrivals, arrivals[1:]))
if not gaps:
    sys.exit("no frames received")
pct = lambda q: gaps[min(len(gaps) - 1, int(q * len(gaps)))]
dur = arrivals[-1] - arrivals[0]
print(f"frames {len(arrivals)} over {dur:.1f}s = {len(arrivals)/dur:.1f} fps, "
      f"bitrate {sum(sizes)*8/dur/1e6:.2f} Mbit/s")
print(f"gap ms: p50 {pct(.5):.0f}  p90 {pct(.9):.0f}  p99 {pct(.99):.0f}  max {gaps[-1]:.0f}")
print(f"stutters (>=250ms): {sum(g >= 250 for g in gaps)}   freezes (>=1s): {sum(g >= 1000 for g in gaps)}")
