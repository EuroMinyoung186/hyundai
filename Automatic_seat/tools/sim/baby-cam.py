"""
노트북 웹캠으로 '아기 모니터'를 흉내 냄 (시험 전용).

얼굴을 찾고 눈이 보이는지만 봄 (OpenCV 기본 Haar 분류기, 따로 받을 모델 없음).
  - 눈이 SLEEP_SEC 동안 계속 안 보이면 → ASLEEP (잠듦)
  - 눈이 WAKE_SEC 동안 보이면          → AWAKE  (깸)
  - 얼굴이 안 보이면 상태를 바꾸지 않음
상태가 바뀔 때만 adb 로 차 앱에 보냄 (debug 빌드의 DebugCommandReceiver, source=monitor).
차 앱은 "📷 ○○이가 잠들었어요 [적용][괜찮아요]" 알림으로 물어봄.

영상은 저장하지도, 어디로 보내지도 않음. 보내는 것은 상태 글자(ASLEEP/AWAKE)뿐.

  python Automatic_seat/tools/sim/baby-cam.py                 # 기본 emulator-5554, 카메라 0
  python Automatic_seat/tools/sim/baby-cam.py --serial emulator-5554 --cam 1 --sleep 4
창에서 q 또는 Esc 로 끔. 미리보기 없이: --no-window
"""
import argparse
import os
import shutil
import subprocess
import time

import cv2

ADB_DEFAULT = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
PKG = "ai.moduui.jari"


def adb_path():
    return ADB_DEFAULT if os.path.exists(ADB_DEFAULT) else (shutil.which("adb") or "adb")


def send(adb, serial, **kv):
    args = [adb, "-s", serial, "shell", "am", "broadcast", "-a", f"{PKG}.debug.CMD",
            "-n", f"{PKG}/.debug.DebugCommandReceiver"]
    for k, v in kv.items():
        args += ["--es", k, str(v)]
    r = subprocess.run(args, capture_output=True, text=True, timeout=10)
    ok = r.returncode == 0 and "Broadcast completed" in r.stdout
    print(f"[{time.strftime('%H:%M:%S')}] 보냄 {kv} {'OK' if ok else '실패: ' + (r.stderr or r.stdout).strip()}")
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", "emulator-5554"))
    ap.add_argument("--cam", type=int, default=0)
    ap.add_argument("--sleep", type=float, default=5.0, help="눈 감은 채 몇 초면 잠듦")
    ap.add_argument("--wake", type=float, default=2.0, help="눈 뜬 채 몇 초면 깸")
    ap.add_argument("--no-window", action="store_true")
    a = ap.parse_args()

    adb = adb_path()
    # 차 아기 탭의 "카메라로 아기 살피기"를 켬 (꺼져 있으면 차가 카메라 상태를 무시함)
    send(adb, a.serial, cmd="camera", value="true")

    face_c = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
    eye_c = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_eye_tree_eyeglasses.xml")
    cap = cv2.VideoCapture(a.cam, cv2.CAP_DSHOW)
    if not cap.isOpened():
        raise SystemExit(f"카메라 {a.cam} 을 못 열었어요 (--cam 1 로 해 보세요)")
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)

    state = None          # 마지막으로 보낸 상태
    closed_since = None   # 눈이 안 보이기 시작한 때
    open_since = None     # 눈이 보이기 시작한 때
    print("시작: 눈을 감고 있으면 잠듦, 뜨면 깸. q/Esc 로 끔")
    try:
        while True:
            ok, frame = cap.read()
            if not ok:
                time.sleep(0.2)
                continue
            frame = cv2.flip(frame, 1)
            gray = cv2.equalizeHist(cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY))
            faces = face_c.detectMultiScale(gray, 1.2, 5, minSize=(120, 120))
            now = time.time()
            eyes_open = None
            if len(faces):
                x, y, w, h = max(faces, key=lambda f: f[2] * f[3])
                top = gray[y + h // 6: y + h // 2, x: x + w]  # 눈은 얼굴 위쪽 절반
                eyes = eye_c.detectMultiScale(top, 1.1, 6, minSize=(w // 10, w // 10))
                eyes_open = len(eyes) > 0
                cv2.rectangle(frame, (x, y), (x + w, y + h), (240, 197, 62), 2)
                for (ex, ey, ew, eh) in eyes:
                    cv2.rectangle(frame, (x + ex, y + h // 6 + ey), (x + ex + ew, y + h // 6 + ey + eh), (140, 214, 61), 2)

            if eyes_open is True:
                closed_since = None
                open_since = open_since or now
                if state != "AWAKE" and now - open_since >= a.wake:
                    state = "AWAKE"
                    send(adb, a.serial, cmd="baby", state=state, source="monitor")
            elif eyes_open is False:
                open_since = None
                closed_since = closed_since or now
                if state != "ASLEEP" and now - closed_since >= a.sleep:
                    state = "ASLEEP"
                    send(adb, a.serial, cmd="baby", state=state, source="monitor")
            else:
                closed_since = open_since = None  # 얼굴 없음 → 그대로

            if not a.no_window:
                # OpenCV 글씨는 한글이 안 나와서 영어로
                if eyes_open is None:
                    msg = "no face"
                elif eyes_open:
                    msg = f"eyes OPEN {now - open_since:.1f}s"
                else:
                    msg = f"eyes CLOSED {now - closed_since:.1f}s / {a.sleep:.0f}s"
                cv2.putText(frame, msg, (12, 32), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (255, 255, 255), 2)
                cv2.putText(frame, f"sent: {state or '-'}", (12, 66), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (65, 184, 245), 2)
                cv2.imshow("baby-cam (q: quit)", frame)
                if not getattr(main, "_top", False):
                    # 다른 창(Android Studio · 에뮬레이터)에 가려지지 않게 맨 위로
                    cv2.setWindowProperty("baby-cam (q: quit)", cv2.WND_PROP_TOPMOST, 1)
                    main._top = True
                if cv2.waitKey(1) & 0xFF in (ord("q"), 27):
                    break
            time.sleep(0.08)  # 10fps 쯤이면 충분 (CPU 아낌)
    finally:
        cap.release()
        cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
