#!/usr/bin/env python3
"""Log the camera's raw RTP timestamps and flag discontinuities.

Usage: rtp_timestamps.py HOST ACCESS_CODE [SECONDS] [teardown]

A minimal RTSPS client (Digest auth, RTP interleaved over TCP) that bypasses every
player's timestamp handling, so what it prints is what the printer sends. At 90 kHz and
30 fps each frame should advance the RTP timestamp by ~3000; anything else is reported
with the wall-clock gap, along with every RTCP sender report (RTP <-> NTP mapping).
"""
import hashlib, re, socket, ssl, struct, sys, time

host, code = sys.argv[1], sys.argv[2]
secs = float(sys.argv[3]) if len(sys.argv) > 3 else 600
teardown = len(sys.argv) > 4 and sys.argv[4] == "teardown"
url = f"rtsps://{host}:322/streaming/live/1"

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE
sock = ctx.wrap_socket(socket.create_connection((host, 322), timeout=30))
buf = b""
cseq = 0
auth = None  # (realm, nonce)
session = None


def md5(s):
    return hashlib.md5(s.encode()).hexdigest()


def read_exact(n):
    global buf
    while len(buf) < n:
        chunk = sock.recv(65536)
        if not chunk:
            raise EOFError("connection closed")
        buf += chunk
    out, buf = buf[:n], buf[n:]
    return out


def read_response():
    """Read one RTSP response, skipping any interleaved packets before it."""
    global buf
    while True:
        while not buf:
            buf += sock.recv(65536)
        if buf[:1] == b"$":
            _, _, length = struct.unpack(">cBH", read_exact(4))
            read_exact(length)
            continue
        while b"\r\n\r\n" not in buf:
            buf += sock.recv(65536)
        head, buf = buf.split(b"\r\n\r\n", 1)
        lines = head.decode(errors="replace").split("\r\n")
        headers = {}
        for line in lines[1:]:
            k, _, v = line.partition(":")
            headers[k.strip().lower()] = v.strip()
        body = read_exact(int(headers.get("content-length", 0))).decode(errors="replace")
        return int(lines[0].split()[1]), headers, body


def request(method, uri, extra=""):
    global cseq, auth
    for _ in range(2):
        cseq += 1
        hdrs = f"{method} {uri} RTSP/1.0\r\nCSeq: {cseq}\r\nUser-Agent: rtp_timestamps\r\n"
        if auth:
            realm, nonce = auth
            resp = md5(f"{md5(f'bblp:{realm}:{code}')}:{nonce}:{md5(f'{method}:{uri}')}")
            hdrs += (f'Authorization: Digest username="bblp", realm="{realm}", nonce="{nonce}", '
                     f'uri="{uri}", response="{resp}"\r\n')
        if session:
            hdrs += f"Session: {session}\r\n"
        sock.sendall((hdrs + extra + "\r\n").encode())
        status, headers, body = read_response()
        if status == 401 and "www-authenticate" in headers:
            h = headers["www-authenticate"]
            auth = (re.search(r'realm="([^"]*)"', h).group(1), re.search(r'nonce="([^"]*)"', h).group(1))
            continue
        if status != 200:
            sys.exit(f"{method} failed: {status}")
        return headers, body
    sys.exit(f"{method}: authentication failed")


request("OPTIONS", url)
_, sdp = request("DESCRIBE", url, "Accept: application/sdp\r\n")
control = re.findall(r"a=control:(\S+)", sdp)[-1]
track = control if control.startswith("rtsp") else url.rstrip("/") + "/" + control
headers, _ = request("SETUP", track, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n")
session = headers["session"].split(";")[0]
headers, _ = request("PLAY", url, "Range: npt=0.000-\r\n")
print(f"{time.strftime('%T')} PLAY RTP-Info: {headers.get('rtp-info')}", flush=True)

start = last_keepalive = last_rr = time.monotonic()
last_ts = last_wall = last_ssrc = last_seq = None
last_packet = time.monotonic()
frames = jumps = 0
print(f"{time.strftime('%T')} playing; flagging RTP timestamp steps outside 1500..6000 (90 kHz)", flush=True)
while time.monotonic() - start < secs:
    if time.monotonic() - last_rr > 5:
        # An empty RTCP receiver report on the interleaved RTCP channel; without one the
        # printer stops sending after ~10 s.
        sock.sendall(b"$\x01\x00\x08" + struct.pack(">BBHI", 0x80, 201, 1, 0x1A2B3C4D))
        last_rr = time.monotonic()
    if time.monotonic() - last_keepalive > 20:
        cseq += 1
        sock.sendall(f"GET_PARAMETER {url} RTSP/1.0\r\nCSeq: {cseq}\r\nSession: {session}\r\n\r\n".encode())
        last_keepalive = time.monotonic()
    first = read_exact(1)
    if first != b"$":
        # An RTSP response (to the keepalive): drain it.
        buf = first + buf
        read_response()
        continue
    channel, length = struct.unpack(">BH", read_exact(3))
    pkt = read_exact(length)
    now = time.monotonic()
    if now - last_packet > 1:
        print(f"{time.strftime('%T')} no data for {now - last_packet:.1f} s (channel {channel})", flush=True)
    last_packet = now
    if channel == 1:
        if len(pkt) >= 20 and pkt[1] == 200:
            ssrc, ntp_s, ntp_f, rtp = struct.unpack(">IIII", pkt[4:20])
            print(f"{time.strftime('%T')} RTCP SR: ssrc={ssrc:08x} rtp={rtp} ntp={ntp_s + ntp_f / 2**32:.3f} "
                  f"(data rtp {last_ts}, data - SR = {((last_ts or rtp) - rtp) / 90000:+.1f} s)", flush=True)
        continue
    seq, ts, ssrc = struct.unpack(">HII", pkt[2:12])
    if ssrc != last_ssrc:
        print(f"{time.strftime('%T')} data ssrc={ssrc:08x} seq={seq} rtp={ts}", flush=True)
        last_ssrc = ssrc
    if last_seq is not None and seq != (last_seq + 1) & 0xFFFF:
        print(f"{time.strftime('%T')} SEQ JUMP: {last_seq} -> {seq} (step {((seq - last_seq + 32768) & 0xFFFF) - 32768:+d})", flush=True)
    last_seq = seq
    if ts == last_ts:
        continue
    frames += 1
    if last_ts is not None:
        step = (ts - last_ts) & 0xFFFFFFFF
        signed = step - 2**32 if step >= 2**31 else step
        if not 1500 <= signed <= 6000:
            jumps += 1
            print(f"{time.strftime('%T')} JUMP after {frames} frames: rtp {last_ts} -> {ts} "
                  f"(step {signed:+d} = {signed / 90000:+.3f} s, wall gap {now - last_wall:.3f} s, seq {seq})",
                  flush=True)
    last_ts, last_wall = ts, now
    if frames % 300 == 0:
        print(f"{time.strftime('%T')} {frames} frames, {jumps} jumps, rtp now {ts}", flush=True)
if teardown:
    cseq += 1
    sock.sendall(f"TEARDOWN {url} RTSP/1.0\r\nCSeq: {cseq}\r\nSession: {session}\r\n\r\n".encode())
    print(f"{time.strftime('%T')} sent TEARDOWN", flush=True)
sock.close()
