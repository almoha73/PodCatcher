#!/usr/bin/env python3
import argparse
import gzip
import mimetypes
import os
import threading
import time
import zlib
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlparse
from urllib.request import Request, urlopen

CACHE_TTL = 300
MAX_BYTES = 25 * 1024 * 1024
_cache = {}
_cache_lock = threading.Lock()

ALLOWED_SCHEMES = ("http", "https")


class ProxyHandler(SimpleHTTPRequestHandler):
    server_version = "PodcatcherLocal/1.0"

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/proxy":
            self.handle_proxy(parsed)
            return
        super().do_GET()

    def do_HEAD(self):
        parsed = urlparse(self.path)
        if parsed.path == "/proxy":
            self.send_response(200)
            self.send_header("Content-Type", "application/xml; charset=utf-8")
            self.end_headers()
            return
        super().do_HEAD()

    def handle_proxy(self, parsed):
        values = parse_qs(parsed.query).get("url") or []
        target = values[0].strip() if values else ""
        if not target:
            self.send_error(400, "Parametre url manquant")
            return
        scheme = urlparse(target).scheme.lower()
        if scheme not in ALLOWED_SCHEMES:
            self.send_error(400, "Schema non autorise")
            return

        cached = self.cached(target)
        if cached is not None:
            body, content_type = cached
            self.send_payload(body, content_type)
            return

        request = Request(
            target,
            headers={
                "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) PodcatcherLocal/1.0",
                "Accept": "application/rss+xml, application/atom+xml, application/xml, text/xml, */*",
            },
        )
        try:
            with urlopen(request, timeout=20) as response:
                raw = response.read(MAX_BYTES + 1)
                if len(raw) > MAX_BYTES:
                    self.send_error(502, "Flux trop volumineux")
                    return
                encoding = (response.headers.get("Content-Encoding") or "").lower()
                if encoding == "gzip":
                    raw = gzip.decompress(raw)
                elif encoding == "deflate":
                    raw = zlib.decompress(raw)
                content_type = (
                    response.headers.get("Content-Type")
                    or mimetypes.guess_type(target)[0]
                    or "application/xml; charset=utf-8"
                )
        except HTTPError as error:
            self.send_error(502, f"Le flux a repondu {error.code}")
            return
        except (URLError, TimeoutError, OSError):
            self.send_error(502, "Impossible de joindre le flux")
            return
        except (gzip.BadGzipFile, zlib.error):
            self.send_error(502, "Reponse compressee invalide")
            return

        self.store(target, raw, content_type)
        self.send_payload(raw, content_type)

    def cached(self, target):
        with _cache_lock:
            entry = _cache.get(target)
            if entry is None:
                return None
            stamp, body, content_type = entry
            if time.time() - stamp > CACHE_TTL:
                _cache.pop(target, None)
                return None
            return body, content_type

    def store(self, target, body, content_type):
        with _cache_lock:
            if len(_cache) > 40:
                oldest = min(_cache, key=lambda key: _cache[key][0])
                _cache.pop(oldest, None)
            _cache[target] = (time.time(), body, content_type)

    def send_payload(self, body, content_type):
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, _format, *_args):
        pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--dir", default=os.path.expanduser("~/PodCatcher"))
    args = parser.parse_args()

    directory = os.path.abspath(os.path.expanduser(args.dir))
    handler = lambda *handler_args, **handler_kwargs: ProxyHandler(
        *handler_args, directory=directory, **handler_kwargs
    )
    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
