"""A stand-in for the recorder's OpenAPI, built from TP-Link's document: HTTPS, SHA-256 Digest, Bearer token, event_server."""
import hashlib
import http.server
import json
import re
import ssl
import sys
import urllib.parse

PORT, CERT, KEY, USER, PASSWORD, STATE_FILE, MODE = (
    int(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5], sys.argv[6], sys.argv[7])
REALM, NONCE, TOKEN = "TP-LINK NVR", "5b032b462e0ad5ea60a0b44eaa871377", "abc/def+ghi=="
log = []


def sha(text):
    return hashlib.sha256(text.encode()).hexdigest()


def load():
    with open(STATE_FILE) as f:
        return json.load(f)


def save(state):
    with open(STATE_FILE, "w") as f:
        json.dump(state, f)


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def reply(self, status, body, extra=None):
        data = json.dumps(body).encode()
        if MODE == "encoded":
            data = urllib.parse.quote(data.decode(), safe="").encode()
        self.send_response(status)
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def authorized(self):
        return self.headers.get("Authorization") == "Bearer " + TOKEN

    def do_GET(self):
        auth = self.headers.get("Authorization", "")
        if self.path == "/openapi/token":
            if not auth:
                print("TOKEN_CHALLENGE", flush=True)
                self.reply(401 if MODE != "status200" else 200, {"error_code": -1}, {
                    "WWW-Authenticate": f'Digest realm="{REALM}", nonce="{NONCE}", algorithm="SHA-256", url="/openapi/token"'})
                return
            params = dict(re.findall(r'(\w+)="([^"]*)"', auth))
            expected = sha(f"{sha(f'{USER}:{REALM}:{PASSWORD}')}:{NONCE}:{sha('GET:/openapi/token')}")
            if params.get("response") != expected or params.get("username") != USER:
                print("TOKEN_REFUSED", flush=True)
                self.reply(401, {"error_code": -1})
                return
            print("TOKEN_GIVEN", flush=True)
            self.reply(200, {"token_type": "bearer", "expires_in": 1800,
                             "access_token": urllib.parse.quote(TOKEN, safe=""), "refresh_token": "x"})
        elif self.path == "/openapi/event_server" and self.authorized():
            print("LIST", flush=True)
            self.reply(200, {"event_server": load(), "error_code": 0})
        else:
            self.reply(401, {"error_code": -40401})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length))
        if not self.authorized():
            self.reply(401, {"error_code": -40401})
            return
        state = load()
        if self.path == "/openapi/event_server":
            if MODE == "textid" and not isinstance(body["id"], str):
                print("ADD_REFUSED_NUMBER_ID", flush=True)
                self.reply(200, {"error_code": -40209})
                return
            body["id"] = int(body["id"])
            if any(s["id"] == body["id"] for s in state):
                print("ADD_CONFLICT", flush=True)
                self.reply(200, {"error_code": -1})
                return
            state.append(body)
            save(state)
            print("ADDED", json.dumps(body), flush=True)
            self.reply(200, {"error_code": 0})
        elif self.path == "/openapi/event_server/delete_server":
            save([s for s in state if s["id"] != int(body["id"])])
            print("DELETED", body["id"], flush=True)
            self.reply(200, {"error_code": 0})
        else:
            self.reply(404, {"error_code": -1})


server = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(CERT, KEY)
server.socket = context.wrap_socket(server.socket, server_side=True)
server.serve_forever()
