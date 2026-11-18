import http.server
import socketserver
import json
import os
import webbrowser
import sys
from export_data import save_exports, find_anki_db

PORT = 8090
DIRECTORY = os.path.dirname(os.path.abspath(__file__))

class AnkiHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DIRECTORY, **kwargs)

    def do_POST(self):
        # Gracefully handle POST requests (e.g. if sync or json rpc is posted)
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(b'{"success": true}')

    def do_GET(self):
        if self.path == "/api/sync":
            try:
                data = save_exports()
                self.send_response(200)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(json.dumps({"success": True, "data": data}, ensure_ascii=False).encode("utf-8"))
            except Exception as e:
                self.send_response(500)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(json.dumps({"success": False, "error": str(e)}).encode("utf-8"))
        elif self.path == "/favicon.ico":
            self.send_response(204)
            self.end_headers()
        elif self.path == "/api/data":
            json_file = os.path.join(DIRECTORY, "anki_data.json")
            if os.path.exists(json_file):
                with open(json_file, "r", encoding="utf-8") as f:
                    content = f.read()
                self.send_response(200)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(content.encode("utf-8"))
            else:
                self.send_error(404, "Data file not found")
        else:
            super().do_GET()

class ThreadingSimpleServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True

def start_server(port=PORT):
    os.chdir(DIRECTORY)
    if not os.path.exists(os.path.join(DIRECTORY, "anki_data.json")):
        try:
            save_exports()
        except Exception as e:
            print("注意：导出初始化数据失败：", e, flush=True)

    handler = AnkiHandler
    server = None
    for p in [port, port + 1, port + 2, 8888]:
        try:
            server = ThreadingSimpleServer(("127.0.0.1", p), handler)
            actual_port = p
            break
        except OSError:
            continue

    if not server:
        print("错误：无法绑定可用端口，请关闭冲突程序后重试。", flush=True)
        return

    with server:
        url = f"http://127.0.0.1:{actual_port}"
        print("=" * 60, flush=True)
        print(f"🚀 Anki 数据可视化 Web 服务已启动！", flush=True)
        print(f"🌐 访问地址: {url}", flush=True)
        print(f"📂 监控本地数据库: {find_anki_db()}", flush=True)
        print("按 Ctrl+C 可停止服务", flush=True)
        print("=" * 60, flush=True)
        
        try:
            webbrowser.open(url)
        except Exception:
            pass

        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("\n正在关闭服务...", flush=True)
            server.shutdown()

if __name__ == "__main__":
    start_server()
