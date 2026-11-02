"""A stand-in recorder for testing the RTSP client on a PC.

Behaves like the VIGI recorder's login (Digest, no qop, stale="FALSE") and sends real
H.264 or H.265 video over RTP/TCP. Its description omits the codec setup data unless
told otherwise, which is the case the old player library refused.
"""
import hashlib
import re
import socket
import struct
import sys
import base64

PORT, CODEC, VIDEO, USER, PASSWORD, INCLUDE_SPROP = (
    int(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5], sys.argv[6] == "sprop")
REALM, NONCE = "vigi", "d5f14ab0"


def md5(text):
    return hashlib.md5(text.encode()).hexdigest()


def split_nals(data):
    nals, i, start = [], 0, None
    while True:
        j = data.find(b"\x00\x00\x01", i)
        if j < 0:
            if start is not None:
                nals.append(data[start:])
            return nals
        if start is not None:
            end = j - 1 if data[j - 1] == 0 else j
            nals.append(data[start:end])
        start = j + 3
        i = j + 3


def nal_type(nal):
    return (nal[0] >> 1) & 0x3F if CODEC == "h265" else nal[0] & 0x1F


def is_config(t):
    return 32 <= t <= 34 if CODEC == "h265" else t in (7, 8)


def is_picture(t):
    return t <= 21 if CODEC == "h265" else 1 <= t <= 5


def group_units(nals):
    units, current = [], []
    for nal in nals:
        current.append(nal)
        if is_picture(nal_type(nal)):
            units.append(current)
            current = []
    return units


def packets_for_unit(unit):
    """Exercises every packet shape: aggregated config, single NAL, fragmented NAL."""
    limit = 1400
    config = [n for n in unit if is_config(nal_type(n))]
    rest = [n for n in unit if not is_config(nal_type(n))]
    out = []
    if config:
        body = b"".join(struct.pack(">H", len(n)) + n for n in config)
        if CODEC == "h265":
            out.append(bytes([48 << 1, 1]) + body)
        else:
            out.append(bytes([24 | (config[0][0] & 0xE0)]) + body)
    for nal in rest:
        if len(nal) <= limit:
            out.append(nal)
            continue
        if CODEC == "h265":
            head, payload, t = nal[:2], nal[2:], nal_type(nal)
            indicator = bytes([(head[0] & 0x81) | (49 << 1), head[1]])
            for i in range(0, len(payload), limit):
                chunk = payload[i:i + limit]
                flags = (0x80 if i == 0 else 0) | (0x40 if i + limit >= len(payload) else 0)
                out.append(indicator + bytes([flags | t]) + chunk)
        else:
            head, payload, t = nal[0], nal[1:], nal[0] & 0x1F
            indicator = bytes([(head & 0xE0) | 28])
            for i in range(0, len(payload), limit):
                chunk = payload[i:i + limit]
                flags = (0x80 if i == 0 else 0) | (0x40 if i + limit >= len(payload) else 0)
                out.append(indicator + bytes([flags | t]) + chunk)
    return out


def sdp(nals):
    if CODEC == "h265":
        rtpmap = "a=rtpmap:96 H265/90000"
    else:
        rtpmap = "a=rtpmap:96 H264/90000"
    lines = ["v=0", "o=- 0 0 IN IP4 127.0.0.1", "s=vigi", "t=0 0", "a=control:*",
             "m=video 0 RTP/AVP 96", rtpmap, "a=control:trackID=1"]
    if INCLUDE_SPROP:
        b64 = lambda n: base64.b64encode(n).decode()
        by_type = {nal_type(n): n for n in nals if is_config(nal_type(n))}
        if CODEC == "h265":
            lines.append(f"a=fmtp:96 sprop-vps={b64(by_type[32])};sprop-sps={b64(by_type[33])};sprop-pps={b64(by_type[34])}")
        else:
            lines.append(f"a=fmtp:96 packetization-mode=1;sprop-parameter-sets={b64(by_type[7])},{b64(by_type[8])}")
    lines += ["m=audio 0 RTP/AVP 8", "a=rtpmap:8 PCMA/8000", "a=control:trackID=2"]
    return "\r\n".join(lines) + "\r\n"


def read_request(conn):
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = conn.recv(4096)
        if not chunk:
            return None
        data += chunk
    return data.decode()


def respond(conn, cseq, status, extra="", body=""):
    length = f"Content-Length: {len(body)}\r\n"
    conn.sendall(f"RTSP/1.0 {status}\r\nCSeq: {cseq}\r\nUser-Agent: vigi\r\n{extra}{length}\r\n{body}".encode())


def authorized(request, method, uri):
    match = re.search(r"Authorization: Digest (.+)", request)
    if not match:
        return False
    params = dict(re.findall(r'(\w+)="?([^",\r]*)"?', match.group(1)))
    expected = md5(f"{md5(f'{USER}:{REALM}:{PASSWORD}')}:{NONCE}:{md5(f'{method}:{uri}')}")
    return (params.get("username") == USER and params.get("uri") == uri
            and params.get("response") == expected and params.get("nonce") == NONCE)


def serve(conn, nals):
    while True:
        request = read_request(conn)
        if request is None:
            return
        first = request.split("\r\n")[0].split(" ")
        method, uri = first[0], first[1]
        cseq = re.search(r"CSeq: (\d+)", request).group(1)
        print("REQUEST", method, uri, flush=True)
        if method == "OPTIONS":
            respond(conn, cseq, "200 OK", "Public: DESCRIBE,SETUP,TEARDOWN,PLAY\r\n")
            continue
        if not authorized(request, method, uri):
            respond(conn, cseq, "401 Unauthorized", f'WWW-Authenticate: Digest realm="{REALM}", nonce="{NONCE}", stale="FALSE"\r\n')
            continue
        if method == "DESCRIBE":
            respond(conn, cseq, "200 OK", f"Content-Type: application/sdp\r\nContent-Base: {uri}/\r\n", sdp(nals))
        elif method == "SETUP":
            assert uri.endswith("/trackID=1"), uri
            assert "interleaved=0-1" in request
            respond(conn, cseq, "200 OK", "Session: 12345678;timeout=60\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n")
        elif method == "PLAY":
            assert "Session: 12345678" in request
            respond(conn, cseq, "200 OK", "Session: 12345678\r\n")
            seq, ts = 0, 1000
            # Starts on a picture that is not a key frame, as a live stream joined midway would.
            units = group_units(nals)
            for unit in units[1:] + units:
                packets = packets_for_unit(unit)
                for i, payload in enumerate(packets):
                    marker = 0x80 if i == len(packets) - 1 else 0
                    rtp = struct.pack(">BBHII", 0x80, marker | 96, seq & 0xFFFF, ts & 0xFFFFFFFF, 1) + payload
                    conn.sendall(b"$\x00" + struct.pack(">H", len(rtp)) + rtp)
                    conn.sendall(b"$\x03\x00\x04abcd")  # Audio/other channel noise to ignore.
                    seq += 1
                ts += 6000
            print("SENT ALL", flush=True)
            read_request(conn)
            return


def main():
    with open(VIDEO, "rb") as f:
        nals = split_nals(f.read())
    server = socket.socket()
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", PORT))
    server.listen(4)
    print("LISTENING", flush=True)
    while True:
        conn, _ = server.accept()
        try:
            serve(conn, nals)
        except (ConnectionError, OSError) as e:
            print("CLOSED", e, flush=True)
        finally:
            conn.close()


main()
