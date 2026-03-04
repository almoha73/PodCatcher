import http.server
import socketserver
import urllib.request
import urllib.parse
import json

PORT = 8765

class ProxyHTTPRequestHandler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        super().end_headers()

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == '/proxy':
            qs = urllib.parse.parse_qs(parsed.query)
            target_url = qs.get('url', [None])[0]
            if not target_url:
                self.send_error(400, 'Missing url parameter')
                return

            req = urllib.request.Request(target_url, headers={'User-Agent': 'Mozilla/5.0'})
            try:
                with urllib.request.urlopen(req) as response:
                    content_type = response.headers.get('Content-Type', 'text/xml')
                    data = response.read()

                self.send_response(200)
                self.send_header('Content-Type', content_type)
                self.send_header('Content-Length', len(data))
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                self.wfile.write(data)
            except Exception as e:
                self.send_error(500, str(e))
        else:
            super().do_GET()

class ThreadedTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True

with ThreadedTCPServer(("127.0.0.1", PORT), ProxyHTTPRequestHandler) as httpd:
    httpd.serve_forever()
