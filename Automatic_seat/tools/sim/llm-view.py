"""
시연 화면용: 휴대폰 안 LLM(Qwen3 0.6B)이 무엇을 받고 무엇을 답했는지 보기 좋게 띄움 (시험 전용).

휴대폰 debug 빌드의 로그(태그 SeatLlm)를 adb 로 읽음:
  prompt[...]: 자리마다 입력 한 줄   → 카드 시작 (입력)
  askOnce in Nms: 답                  → 답 · 걸린 시간
  final: 자리=각도 앞뒤 (이유) || 고친 것 → 규칙 검사 · 짐 공간까지 반영한 최종 자세
  loaded in Nms / prefill ... in Nms  → 아래 상태 줄 (모델 준비 · KV 캐시)

  python Automatic_seat/tools/sim/llm-view.py [--serial emulator-5556] [--geo 616x278+370+450]
"""
import argparse
import os
import queue
import re
import shutil
import subprocess
import threading
import tkinter as tk

ADB_DEFAULT = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
BG, CARD, LINE, TEXT, SUB, ACCENT, OK, WARN = "#0A0C0F", "#14181D", "#262D35", "#F2F5F7", "#8B95A1", "#3EC5F0", "#3DD68C", "#F5B841"
FONT = "Malgun Gothic"
SEATS = {"R1_LEFT": "운전석", "R1_RIGHT": "조수석", "R2_LEFT": "2열 왼쪽", "R2_RIGHT": "2열 오른쪽", "R3_LEFT": "3열 왼쪽", "R3_RIGHT": "3열 오른쪽"}
SEAT_RE = re.compile(r"(R[1-3]_(?:LEFT|RIGHT))")


def seat_ko(text):
    return SEAT_RE.sub(lambda m: SEATS.get(m.group(1), m.group(1)), text)


class Card:
    def __init__(self, kind, inputs):
        self.kind = kind
        self.inputs = inputs
        self.answer = []
        self.ms = None
        self.final = []
        self.fixes = []


def reader(adb, serial, q):
    subprocess.run([adb, "-s", serial, "logcat", "-c"], capture_output=True)  # 지난 기록은 지우고 새로 온 것만
    p = subprocess.Popen([adb, "-s", serial, "logcat", "-v", "brief", "-s", "SeatLlm:I"],
                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding="utf-8", errors="replace")
    for line in p.stdout:
        q.put(line.rstrip())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="emulator-5556")
    ap.add_argument("--geo", default="616x278+370+450")
    a = ap.parse_args()
    adb = ADB_DEFAULT if os.path.exists(ADB_DEFAULT) else (shutil.which("adb") or "adb")

    root = tk.Tk()
    root.title("LLM - Qwen3 0.6B")
    root.geometry(a.geo)
    root.configure(bg=BG)
    head = tk.Frame(root, bg=BG)
    head.pack(fill="x", padx=12, pady=(10, 4))
    tk.Label(head, text="휴대폰 AI · Qwen3-0.6B (기기 안)", fg=ACCENT, bg=BG, font=(FONT, 11, "bold")).pack(side="left")
    status = tk.Label(head, text="기다리는 중…", fg=SUB, bg=BG, font=(FONT, 9))
    status.pack(side="right")
    body = tk.Text(root, bg=BG, fg=TEXT, bd=0, highlightthickness=0, wrap="word", font=(FONT, 10), padx=12, pady=4, cursor="arrow")
    body.pack(fill="both", expand=True)
    for name, opts in {
        "kind": dict(foreground=ACCENT, font=(FONT, 11, "bold"), spacing1=8),
        "time": dict(foreground=OK, font=(FONT, 10, "bold")),
        "label": dict(foreground=SUB, font=(FONT, 9), spacing1=4),
        "in": dict(foreground=TEXT, font=(FONT, 10), lmargin1=12, lmargin2=12),
        "ans": dict(foreground=TEXT, font=("Consolas", 10), lmargin1=12, lmargin2=12),
        "final": dict(foreground=OK, font=(FONT, 10, "bold"), lmargin1=12, lmargin2=12),
        "why": dict(foreground=SUB, font=(FONT, 9)),
        "fix": dict(foreground=WARN, font=(FONT, 9), lmargin1=12, lmargin2=12),
        "rule": dict(foreground=LINE, font=(FONT, 6), spacing3=4),
    }.items():
        body.tag_configure(name, **opts)

    cards = []
    q = queue.Queue()
    threading.Thread(target=reader, args=(adb, a.serial, q), daemon=True).start()

    def render():
        body.configure(state="normal")
        body.delete("1.0", "end")
        if not cards:
            body.insert("end", "휴대폰에서 [차로 보내기] · [다시 계산]을 누르면 여기에 AI 입력과 답이 떠요", "label")
        for c in reversed(cards[-4:]):  # 최근 것이 위
            body.insert("end", c.kind, "kind")
            if c.ms is not None:
                body.insert("end", f"   {c.ms / 1000:.1f}초", "time")
            body.insert("end", "\n")
            body.insert("end", "입력\n", "label")
            for i in c.inputs:
                body.insert("end", f"· {seat_ko(i)}\n", "in")
            if c.answer:
                body.insert("end", "AI 답\n", "label")
                for x in c.answer:
                    body.insert("end", f"{seat_ko(x)}\n", "ans")
            if c.final:
                body.insert("end", "최종 (안전 규칙 · 짐 공간 검사 뒤)\n", "label")
                for seat, rest, why in c.final:
                    body.insert("end", f"{seat}  {rest}", "final")
                    if why:
                        body.insert("end", f"   {why}", "why")
                    body.insert("end", "\n")
                for f in c.fixes:
                    body.insert("end", f"규칙이 고침 · {seat_ko(f)}\n", "fix")
            body.insert("end", "─" * 60 + "\n", "rule")
        body.configure(state="disabled")

    def poll():
        changed = False
        while not q.empty():
            line = q.get()
            msg = line.split(": ", 1)[1] if ": " in line else line
            if msg.startswith("loaded in "):
                ms = int(re.findall(r"\d+", msg)[0])
                status.configure(text=f"모델 준비 {ms / 1000:.1f}초")
            elif msg.startswith("prefill "):
                ms = int(re.findall(r"in (\d+)ms", msg)[0])
                status.configure(text=f"지시문 KV 캐시 준비 {ms / 1000:.1f}초 · 다음부터 질문만 계산")
            elif msg.startswith("prompt["):
                tag, _, body_ = msg.partition("]: ")
                kind = "좌석 자세" if "좌석" in tag else "돌봄 추천"
                inputs = [x for x in body_.split("|") if x and x != "답:"]
                cards.append(Card(kind, inputs))
                changed = True
            elif msg.startswith("askOnce in ") and cards:
                m = re.match(r"askOnce in (\d+)ms: (.*)", msg)
                if m:
                    cards[-1].ms = int(m.group(1))
                    cards[-1].answer = [x.strip() for x in m.group(2).split("|") if x.strip()]
                    changed = True
            elif msg.startswith("final: ") and cards:
                main_, _, fixes = msg[len("final: "):].partition(" || ")
                final = []
                for item in main_.split("|"):
                    m = re.match(r"(R[1-3]_\w+)=(\d+)도 앞뒤(\S+) \((.*)\)", item.strip())
                    if m:
                        final.append((SEATS.get(m.group(1), m.group(1)), f"{m.group(2)}° · 앞뒤 {m.group(3)}", m.group(4)))
                cards[-1].final = final
                cards[-1].fixes = [f for f in fixes.split("|") if f]
                changed = True
        if changed:
            render()
        root.after(300, poll)

    render()
    poll()
    root.mainloop()


if __name__ == "__main__":
    main()
