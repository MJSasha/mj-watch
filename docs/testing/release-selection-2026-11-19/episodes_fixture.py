"""Loopback-only synthetic metadata API for opt-in ManagedEpisodesUiTest.
Run with Python 3 + OpenSSL; adb reverse tcp:18545/18546 to the same ports.
No production credentials or remote services are used.
"""
import http.server
import json
from pathlib import Path
import ssl
import subprocess
import tempfile
import threading

with tempfile.TemporaryDirectory(prefix="mjwatch-episodes-") as directory:
    root = Path(directory)
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1", "-keyout", str(root/"key.pem"), "-out", str(root/"cert.pem")], check=True, capture_output=True)
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass
        def do_GET(self):
            path = self.path.split("?", 1)[0]
            if self.server.server_port == 18546 and path == "/bootstrap":
                body = {"url": "https://127.0.0.1:18545", "ca": (root/"cert.pem").read_text(), "accessToken": "fixture-access", "refreshToken": "fixture-refresh", "expiresIn": 3600}
            elif path == "/api/v1/metadata/tv/8844":
                body = {"seasons": [{"season_number": 1, "name": "Сезон 1", "episode_count": 10}, {"season_number": 2, "name": "Сезон 2", "episode_count": 8}]}
            elif path == "/api/v1/metadata/tv/8844/seasons/2":
                body = {"episodes": [{"episode_number": 1, "name": "Новая орбита"}, {"episode_number": 2, "name": "Сигнал издалека"}, {"episode_number": 3, "name": "Возвращение домой"}]}
            else:
                self.send_response(404); self.end_headers(); return
            data = json.dumps(body, ensure_ascii=False).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
    api = http.server.ThreadingHTTPServer(("127.0.0.1", 18545), Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(root/"cert.pem", root/"key.pem")
    api.socket = context.wrap_socket(api.socket, server_side=True)
    control = http.server.ThreadingHTTPServer(("127.0.0.1", 18546), Handler)
    threading.Thread(target=api.serve_forever, daemon=True).start()
    print("Synthetic episodes fixture: TLS 18545, bootstrap 18546", flush=True)
    try:
        control.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        api.shutdown(); api.server_close(); control.server_close()
