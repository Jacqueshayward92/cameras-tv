"""Plays video in real time, checks the client's check-ins arrive, then stalls to test recovery."""
import re
import socket
import struct
import sys
import threading
import time

PORT, VIDEO = int(sys.argv[1]), sys.argv[2]
PLAY_SECONDS, STALL_SECONDS = 12, 8
counts = {"reports": 0, "keepalives": 0}


def reader(conn):
    data = b""
    try:
        while True:
            chunk = conn.recv(4096)
            if not chunk:
                return
            data += chunk
            while data:
                if data[:1] == b"$":
                    if len(data) < 4:
                        break
                    size = struct.unpack(">H", data[2:4])[0]
                    if len(data) < 4 + size:
                        break
                    if data[1] == 1 and data[5] == 201:
                        counts["reports"] += 1
                    data = data[4 + size:]
                elif b"\r\n\r\n" in data:
                    head, data = data.split(b"\r\n\r\n", 1)
                    if head.startswith(b"GET_PARAMETER"):
                        counts["keepalives"] += 1
                else:
                    break
    except OSError:
        pass


def read_request(conn):
    data = b""
    while b"\r\n\r\n" not in data:
        data += conn.recv(4096)
    return data.decode()


with open(VIDEO, "rb") as f:
    raw = f.read()
nals = [n for n in re.split(b"\x00\x00\x00\x01|\x00\x00\x01", raw) if n]
sdp = "v=0\r\ns=vigi\r\nt=0 0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=control:trackID=1\r\n"

server = socket.socket()
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(("127.0.0.1", PORT))
server.listen(1)
conn, _ = server.accept()
while True:
    request = read_request(conn)
    method = request.split(" ")[0]
    cseq = re.search(r"CSeq: (\d+)", request).group(1)
    if method == "DESCRIBE":
        conn.sendall(f"RTSP/1.0 200 OK\r\nCSeq: {cseq}\r\nContent-Length: {len(sdp)}\r\n\r\n{sdp}".encode())
    elif method == "SETUP":
        conn.sendall(f"RTSP/1.0 200 OK\r\nCSeq: {cseq}\r\nSession: 1\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n\r\n".encode())
    elif method == "PLAY":
        conn.sendall(f"RTSP/1.0 200 OK\r\nCSeq: {cseq}\r\nSession: 1\r\n\r\n".encode())
        break

threading.Thread(target=reader, args=(conn,), daemon=True).start()
start, seq, ts, index = time.time(), 0, 0, 0
while time.time() - start < PLAY_SECONDS:
    nal = nals[index % len(nals)]
    index += 1
    picture = 1 <= (nal[0] & 0x1F) <= 5
    if len(nal) < 60000:
        marker = 0x80 if picture else 0
        rtp = struct.pack(">BBHII", 0x80, marker | 96, seq & 0xFFFF, ts, 1) + nal
        conn.sendall(b"$\x00" + struct.pack(">H", len(rtp)) + rtp)
        seq += 1
    if picture:
        ts += 6000
        time.sleep(1 / 15)
print(f"PLAYED {PLAY_SECONDS}s; check-ins received: {counts['reports']}", flush=True)
time.sleep(STALL_SECONDS)
print(f"STALL OVER; check-ins during whole run: {counts['reports']}", flush=True)
