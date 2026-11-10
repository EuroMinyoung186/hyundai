"""
모두의 자리 · 휴대폰 ↔ 차 중계 서버 (시연용)

실서비스에서는 팀 서버가 할 일을 PC 에서 대신 함. 메모리에만 두고, 끄면 다 지워짐.
휴대폰과 에뮬레이터 둘 다 USB(adb reverse)로 PC 의 127.0.0.1:8090 에 닿음:
    adb -s emulator-5554 reverse tcp:8090 tcp:8090
    adb -s <휴대폰 시리얼> reverse tcp:8090 tcp:8090
everyseat-sim.ps1 -Step phone 이 이 서버를 켜고 reverse 를 걸어 줌.

차:     GET  /car/inbox?code=482913&after=N   → {"connected": bool, "messages": [...]}
        POST /car/status?code=482913          (차 상태 JSON)
휴대폰: POST /phone/pair      {"code": "482913"}  → {"ok": bool}
        POST /phone/send      {"code": ..., "type": "trip" | "prepare" | "baby" | ...}
        GET  /phone/status?code=482913        → {"car": bool, "status": {...}}
"""
import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8090
# 에뮬레이터가 바쁘면 차 앱의 확인이 몇 초씩 늦어질 수 있어 넉넉히 둠
CAR_STALE_SEC = 15

lock = threading.Lock()
cars = {}  # code -> {"seen": t, "paired": bool, "inbox": [...], "seq": n, "status": {...}}


def car(code):
    c = cars.get(code)
    if c is None:
        c = cars[code] = {"seen": 0.0, "paired": False, "inbox": [], "seq": 0, "status": None}
    return c


def log(msg):
    print(time.strftime("[%H:%M:%S] ") + msg, flush=True)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, obj, status=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0:
            return {}
        try:
            return json.loads(self.rfile.read(n).decode("utf-8"))
        except ValueError:
            return {}

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        code = q.get("code", "")
        if u.path == "/car/inbox":
            after = int(q.get("after", "0") or 0)
            with lock:
                c = car(code)
                if time.time() - c["seen"] > CAR_STALE_SEC:
                    log(f"차 접속: 코드 {code}")
                c["seen"] = time.time()
                now = time.time()
                # age: 받은 지 몇 초 지났는지. 차는 오래된 명령(특히 출발 준비)을 실행하지 않음.
                msgs = [dict(m, age=round(now - m["at"], 1)) for m in c["inbox"] if m["seq"] > after]
                self.reply({"connected": c["paired"], "latest": c["seq"], "messages": msgs})
            return
        if u.path == "/phone/status":
            with lock:
                c = cars.get(code)
                if c is None or not c["paired"]:
                    self.reply({"ok": False, "error": "not_paired"}, 404)
                    return
                online = time.time() - c["seen"] < CAR_STALE_SEC
                self.reply({"ok": True, "car": online, "status": c["status"] or {}})
            return
        if u.path == "/":
            with lock:
                self.reply({"cars": {k: {"paired": v["paired"], "online": time.time() - v["seen"] < CAR_STALE_SEC} for k, v in cars.items()}})
            return
        self.reply({"error": "not_found"}, 404)

    def do_POST(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        data = self.body()
        if u.path == "/car/status":
            with lock:
                c = car(q.get("code", ""))
                c["seen"] = time.time()
                c["status"] = data
            self.reply({"ok": True})
            return
        if u.path == "/phone/pair":
            code = str(data.get("code", "")).strip()
            with lock:
                c = cars.get(code)
                ok = c is not None and time.time() - c["seen"] < CAR_STALE_SEC
                if ok:
                    c["paired"] = True
                error = None if ok else ("wrong_code" if c is None else "car_offline")
            log(f"휴대폰 연결 {'성공' if ok else '실패 (' + error + ')'}: 코드 {code}")
            self.reply({"ok": ok, "error": error})
            return
        if u.path == "/phone/send":
            code = str(data.pop("code", "")).strip()
            with lock:
                c = cars.get(code)
                if c is None or not c["paired"]:
                    self.reply({"ok": False, "error": "not_paired"}, 404)
                    return
                c["seq"] += 1
                data["seq"] = c["seq"]
                data["at"] = time.time()
                c["inbox"].append(data)
                c["inbox"] = c["inbox"][-50:]
            log(f"휴대폰 → 차: {json.dumps(data, ensure_ascii=False)}")
            self.reply({"ok": True, "seq": data["seq"]})
            return
        self.reply({"error": "not_found"}, 404)


if __name__ == "__main__":
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    log(f"중계 서버 시작: http://127.0.0.1:{PORT}")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
