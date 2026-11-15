"""
시연 화면 배치 (시험 전용). 노트북 화면(작업 영역)을 나눠 창을 놓음:
  오른쪽     휴대폰 에뮬레이터
  가운데     차(내비) 에뮬레이터
  왼쪽 위    웹캠 아기 모니터 (baby-cam.py, 아래 출력의 --geo 로 띄움)
  왼쪽 아래  LLM 보기 (llm-view.py, 아래 출력의 --geo 로 띄움)

  python Automatic_seat/tools/sim/layout.py          # 자리만 잡고 앞으로 가져옴
  python Automatic_seat/tools/sim/layout.py --top    # 녹화용: 네 창을 늘 맨 위에 고정 (다른 창이 가리지 않음)
  python Automatic_seat/tools/sim/layout.py --free   # 고정 풀기
"""
import sys
import ctypes
from ctypes import wintypes

user32 = ctypes.windll.user32
user32.SetProcessDPIAware()


def work_area():
    r = wintypes.RECT()
    ctypes.windll.user32.SystemParametersInfoW(0x0030, 0, ctypes.byref(r), 0)  # SPI_GETWORKAREA
    return r.left, r.top, r.right - r.left, r.bottom - r.top


def find(part):
    found = []
    def cb(h, _):
        if user32.IsWindowVisible(h):
            n = user32.GetWindowTextLengthW(h)
            if n:
                buf = ctypes.create_unicode_buffer(n + 1)
                user32.GetWindowTextW(h, buf, n + 1)
                if part in buf.value:
                    found.append((h, buf.value))
        return True
    user32.EnumWindows(ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)(cb), 0)
    return found


def rect(wins):
    if not wins:
        return None
    r = wintypes.RECT()
    user32.GetWindowRect(wins[0][0], ctypes.byref(r))
    return r.left, r.top, r.right - r.left, r.bottom - r.top


def put(part, x, y, w, h):
    wins = find(part)
    if not wins:
        print(f"없음: {part}")
        return
    hwnd, title = wins[0]
    user32.ShowWindow(hwnd, 9)  # SW_RESTORE
    if "Emulator" in title:
        # 에뮬레이터는 밖에서 크기를 바꾸면 무시하고 자리까지 되돌림 → 자리만 옮김 (크기는 에뮬레이터가 정함)
        user32.SetWindowPos(hwnd, -1 if TOP else 0, x, y, 0, 0, 0x0001 | 0x0040)
        r = wintypes.RECT()
        user32.GetWindowRect(hwnd, ctypes.byref(r))
        print(f"{title}: {r.left},{r.top} {r.right - r.left}x{r.bottom - r.top}")
        return
    # 맨 앞으로 (크롬 등 다른 창에 가려지지 않게). 녹화 중 가려지면 다시 실행
    user32.SetWindowPos(hwnd, -1, x, y, w, h, 0x0040)  # HWND_TOPMOST · SWP_SHOWWINDOW
    if not TOP:
        user32.SetWindowPos(hwnd, -2, x, y, w, h, 0x0040)  # HWND_NOTOPMOST: 앞에 둔 채 고정은 풂
    r = wintypes.RECT()
    user32.GetWindowRect(hwnd, ctypes.byref(r))
    print(f"{title}: 요청 {x},{y} {w}x{h} → 실제 {r.left},{r.top} {r.right - r.left}x{r.bottom - r.top}")


TOP = "--top" in sys.argv
X, Y, W, H = work_area()
# 11/15 사용자 배치: 가운데 내비 · 오른쪽 휴대폰 · 왼쪽 위 웹캠 · 왼쪽 아래 LLM.
# 에뮬레이터는 창 크기를 스스로 정함(이 화면에서 내비 약 748x451) → 실제 크기를 읽어 나머지를 맞춤
pw = (rect(find("Medium_Phone")) or (0, 0, 326, H))[2]
put("Medium_Phone", X + W - pw, Y, pw, H)
phone = rect(find("Medium_Phone"))
navi0 = rect(find("Pleos_Connect")) or (0, 0, 908, 541)
navi_w, navi_h = navi0[2], navi0[3]
phone_left = phone[0] if phone else X + W - 326
# 내비는 휴대폰 바로 왼쪽, 세로 가운데. 내비 왼쪽 1/3 은 Pleos 시스템 차량 패널이라 그 위에 웹캠 · LLM 을 올림
put("Pleos_Connect", phone_left - navi_w, Y + (H - navi_h) // 2, navi_w, navi_h)
navi = rect(find("Pleos_Connect")) or (phone_left - navi_w, 0, navi_w, navi_h)
for hwnd, _ in [w for w in find("Emulator") if w[1] == "Emulator"]:
    user32.ShowWindow(hwnd, 0)
left_w = navi[0] - X + round(navi_w * 0.33)
cam_h = round((left_w - 16) * 3 / 4) + 31   # 4:3 영상 + 제목 줄
print(f"웹캠  --geo {X},{Y},{left_w - 16},{cam_h - 31}")
print(f"LLM   --geo {left_w - 16}x{H - cam_h - 39}+{X}+{Y + cam_h}")
# LLM 창(tkinter)은 밖에서 옮기고 키워도 그대로 있음 → 왼쪽 아래를 꽉 채움
put("LLM - Qwen3", X, Y + cam_h, left_w, H - cam_h)
for part in ("baby-cam",):
    for hwnd, _ in find(part):
        user32.SetWindowPos(hwnd, -1 if TOP else 0, 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0040)
