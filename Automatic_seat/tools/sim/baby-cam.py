"""
노트북 웹캠으로 '아기 모니터'를 흉내 냄 (시험 전용, 실차에서는 차 실내 카메라가 할 일).

  1) 얼굴 찾기: YOLO ONNX (yolov8n-face, akanametov/yolo-face → deepghs/yolo-face) 를 onnxruntime(CPU)으로.
  2) 눈 뜸/감음: 그 얼굴을 잘라 MediaPipe Face Mesh 로 눈꺼풀 점을 찾고, 눈이 벌어진 정도(EAR)를 잼.
     처음 3초(눈 뜬 채) 이 사람의 EAR 을 재서 기준으로 삼고, 그 65% 아래면 감음.
     고개를 많이 돌린 장(코가 두 눈 사이에서 많이 벗어남)은 판정에서 뺌.
     (YOLO 눈 모델 · OpenCV 눈 분류기는 뜬 눈을 감았다고 보거나 고개를 돌리면 틀려서 바꿈, 11/15)

판정 (깜빡임은 무시, 최근 1.5초 다수결):
  - 감음이 SLEEP_SEC 동안 이어지면 → ASLEEP (잠듦)
  - 뜸이 WAKE_SEC 동안 이어지면    → AWAKE  (깸)
  - 얼굴이 안 보이거나 고개를 돌리면 상태를 바꾸지 않음
상태가 바뀔 때만 adb 로 차 앱에 보냄 (debug 빌드의 DebugCommandReceiver, source=monitor).
웹캠은 차가 D단 · 주행 중일 때만 켬 (1초마다 차에 cameraState 를 물음, 11/15 사용자 결정). --dry 면 늘 켬.

영상은 저장하지도, 어디로 보내지도 않음. 보내는 것은 상태 글자(ASLEEP/AWAKE)뿐.
얼굴 모델은 처음 한 번 Hugging Face 에서 models/ 로 받음 (저장소에는 올리지 않음).

이 PC 는 C 드라이브가 좁아 D 드라이브 가상환경에 설치해 둠 (mediapipe 0.10.14 · onnxruntime):
  D:\\venvs\\babycam\\Scripts\\python.exe Automatic_seat/tools/sim/baby-cam.py [--cam 0] [--sleep 5]
  ... --dry --frames 40 --no-window   # 차에 안 보내고 판정만 출력
창에서 q/Esc 끔, c 다시 재기.
"""
import argparse
import collections
import os
import shutil
import subprocess
import time
import urllib.request

import cv2
import mediapipe as mp
import numpy as np
import onnxruntime as ort

HERE = os.path.dirname(os.path.abspath(__file__))
MODELS = os.path.join(HERE, "models")
FACE_URL = "https://huggingface.co/deepghs/yolo-face/resolve/main/yolov8n-face/model.onnx"
ADB_DEFAULT = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
PKG = "ai.moduui.jari"
WIN = "baby-cam (q: quit, c: recalibrate)"
CAL_SEC = 3.0        # 처음 몇 초 눈 뜬 모습을 재서 기준을 정함
CLOSED_FRAC = 0.65   # 기준 EAR 의 이 비율 아래면 감음
CLOSED_RATIO = 0.7   # 최근 1.5초 판정 중 이만큼이 감음이어야 감음
MAX_YAW = 0.22       # 코 위치가 두 눈 가운데에서 이만큼(눈 사이 거리 비율) 벗어나면 고개 돌림 → 판정 안 함
# Face Mesh 눈 점: 바깥 끝 · 위 2 · 안쪽 끝 · 아래 2
LEFT = (33, 160, 158, 133, 153, 144)
RIGHT = (362, 385, 387, 263, 373, 380)
NOSE = 1


def face_model():
    path = os.path.join(MODELS, "yolov8n-face.onnx")
    if not os.path.exists(path):
        os.makedirs(MODELS, exist_ok=True)
        print("얼굴 모델 받는 중")
        urllib.request.urlretrieve(FACE_URL, path + ".part")
        os.replace(path + ".part", path)
    s = ort.SessionOptions()
    s.intra_op_num_threads = 2  # CPU 아낌
    return ort.InferenceSession(path, s, providers=["CPUExecutionProvider"])


def find_face(sess, img, size=640, conf=0.5):
    """YOLOv8 얼굴 ONNX → 가장 큰 얼굴 (x1, y1, x2, y2) 또는 None"""
    h, w = img.shape[:2]
    r = size / max(h, w)
    nh, nw = int(round(h * r)), int(round(w * r))
    canvas = np.full((size, size, 3), 114, np.uint8)
    top, left = (size - nh) // 2, (size - nw) // 2
    canvas[top:top + nh, left:left + nw] = cv2.resize(img, (nw, nh))
    x = canvas[:, :, ::-1].transpose(2, 0, 1)[None].astype(np.float32) / 255.0
    out = sess.run(None, {sess.get_inputs()[0].name: x})[0][0].T  # [N, 5]
    out = out[out[:, 4] >= conf]
    if not len(out):
        return None
    cx, cy, bw, bh = out[:, 0], out[:, 1], out[:, 2], out[:, 3]
    boxes = np.stack([(cx - bw / 2 - left) / r, (cy - bh / 2 - top) / r, bw / r, bh / r], 1)
    idx = np.array(cv2.dnn.NMSBoxes(boxes.tolist(), out[:, 4].tolist(), conf, 0.45)).reshape(-1)
    bx, by, bw_, bh_ = max((boxes[i] for i in idx), key=lambda b: b[2] * b[3])
    return int(max(bx, 0)), int(max(by, 0)), int(min(bx + bw_, w)), int(min(by + bh_, h))


def ear(p, idx):
    a, b, c, d, e, f = (p[i] for i in idx)
    return (np.linalg.norm(b - f) + np.linalg.norm(c - e)) / (2 * np.linalg.norm(a - d) + 1e-6)


def adb_path():
    return ADB_DEFAULT if os.path.exists(ADB_DEFAULT) else (shutil.which("adb") or "adb")


def send(adb, serial, **kv):
    args = [adb, "-s", serial, "shell", "am", "broadcast", "-a", f"{PKG}.debug.CMD",
            "-n", f"{PKG}/.debug.DebugCommandReceiver"]
    for k, v in kv.items():
        args += ["--es", k, str(v)]
    r = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=10)
    ok = r.returncode == 0 and "Broadcast completed" in r.stdout
    print(f"[{time.strftime('%H:%M:%S')}] 보냄 {kv} {'OK' if ok else '실패: ' + (r.stderr or r.stdout).strip()}")
    return ok


def car_camera_on(adb, serial):
    """차가 D단 · 주행 중이면 True, 아니면 False, adb 가 답을 못 받으면 None (debug 명령 cameraState 의 답)"""
    try:
        r = subprocess.run([adb, "-s", serial, "shell", "am", "broadcast", "-a", f"{PKG}.debug.CMD",
                            "-n", f"{PKG}/.debug.DebugCommandReceiver", "--es", "cmd", "cameraState"],
                           capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=5)
        out = r.stdout or ""
        if "Broadcast completed" not in out:
            return None  # adb 가 잠깐 끊김 (Winsock 10014 등) → 모름
        return 'data="on"' in out
    except Exception:
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", "emulator-5554"))
    ap.add_argument("--cam", type=int, default=0)
    ap.add_argument("--sleep", type=float, default=5.0, help="눈 감은 채 몇 초면 잠듦")
    ap.add_argument("--wake", type=float, default=2.0, help="눈 뜬 채 몇 초면 깸")
    ap.add_argument("--no-window", action="store_true")
    ap.add_argument("--dry", action="store_true", help="차에 보내지 않고 판정만 출력")
    ap.add_argument("--frames", type=int, default=0, help="이만큼만 보고 끝 (시험용)")
    ap.add_argument("--geo", default="0,524,256,173", help="창 위치 · 영상 크기 x,y,w,h (시연 화면 배치, layout.py 와 맞춤)")
    a = ap.parse_args()

    yolo = face_model()
    mesh = mp.solutions.face_mesh.FaceMesh(static_image_mode=False, max_num_faces=1, refine_landmarks=True,
                                           min_detection_confidence=0.5, min_tracking_confidence=0.5)
    adb = adb_path()
    if not a.dry:
        # 차 아기 탭의 "카메라로 아기 살피기"를 켬 (꺼져 있으면 차가 카메라 상태를 무시함)
        send(adb, a.serial, cmd="camera", value="true")

    def open_cam():
        c = cv2.VideoCapture(a.cam, cv2.CAP_DSHOW)
        if not c.isOpened():
            raise SystemExit(f"카메라 {a.cam} 을 못 열었어요 (--cam 1 로 해 보세요)")
        c.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
        c.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
        return c

    cap = None
    checked_at = 0.0
    off_votes = 0
    drive = a.dry

    state = None
    closed_since = open_since = None
    votes = collections.deque()  # (시각, 뜸?)
    base, cal, cal_start = None, [], None
    n, top = 0, False
    if not a.no_window:
        # 창 크기를 바꾸면 영상도 맞춰 줄어들게 (시연 화면 배치: tools/sim/layout.py)
        cv2.namedWindow(WIN, cv2.WINDOW_NORMAL)
        x, y, w, h = (int(v) for v in a.geo.split(","))
        cv2.resizeWindow(WIN, w, h)
        cv2.moveWindow(WIN, x, y)
    print("시작: 처음 3초는 눈을 뜬 채로 카메라를 보세요. q/Esc 끔, c 다시 재기")
    try:
        while True:
            # 1초마다 차 기어 확인: D단이면 웹캠을 켜고, 아니면 끔 (판정도 처음부터)
            if not a.dry and time.time() - checked_at >= 1.0:
                checked_at = time.time()
                ans = car_camera_on(adb, a.serial)
                # adb 가 한 번 끊기거나 한 번만 "아님"이 와도 웹캠이 꺼졌다 켜지지 않게: 모르면 그대로, "아님"은 3번 연달아 와야 끔
                off_votes = 0 if ans else (off_votes + 1 if ans is False else off_votes)
                was = drive
                if ans:
                    drive = True
                elif ans is False and off_votes >= 3:
                    drive = False
                if drive != was:
                    print(f"[{time.strftime('%H:%M:%S')}] {'D단 · 웹캠 켬' if drive else 'D단 아님 · 웹캠 끔'}")
                    if drive:
                        # 차 앱 데이터를 지우면 "카메라로 아기 살피기"도 꺼지므로 켤 때마다 다시 켬
                        send(adb, a.serial, cmd="camera", value="true")
            if not drive:
                if cap is not None:
                    cap.release()
                    cap = None
                    votes.clear()
                    closed_since = open_since = None
                if not a.no_window:
                    idle = np.zeros((480, 640, 3), np.uint8)
                    cv2.putText(idle, "camera OFF - waiting for gear D", (40, 240), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (200, 200, 200), 2)
                    cv2.imshow(WIN, idle)
                    if not top:
                        cv2.setWindowProperty(WIN, cv2.WND_PROP_TOPMOST, 1)
                        top = True
                    if cv2.waitKey(300) & 0xFF in (ord("q"), 27):
                        break
                else:
                    time.sleep(0.3)
                continue
            if cap is None:
                cap = open_cam()
            ok, frame = cap.read()
            if not ok:
                time.sleep(0.2)
                continue
            frame = cv2.flip(frame, 1)
            t0 = time.time()
            face = find_face(yolo, frame)
            e = yaw = None
            pts = None
            if face:
                x1, y1, x2, y2 = face
                mx, my = int((x2 - x1) * 0.2), int((y2 - y1) * 0.2)
                cx1, cy1 = max(x1 - mx, 0), max(y1 - my, 0)
                crop = frame[cy1:min(y2 + my, frame.shape[0]), cx1:min(x2 + mx, frame.shape[1])]
                res = mesh.process(cv2.cvtColor(crop, cv2.COLOR_BGR2RGB))
                if res.multi_face_landmarks:
                    ch, cw = crop.shape[:2]
                    lm = res.multi_face_landmarks[0].landmark
                    pts = {i: np.array([lm[i].x * cw + cx1, lm[i].y * ch + cy1]) for i in set(LEFT + RIGHT + (NOSE,))}
                    e = (ear(pts, LEFT) + ear(pts, RIGHT)) / 2
                    mid = (pts[33] + pts[263]) / 2
                    yaw = (pts[NOSE][0] - mid[0]) / (np.linalg.norm(pts[263] - pts[33]) + 1e-6)
            ms = (time.time() - t0) * 1000
            now = time.time()
            usable = e is not None and abs(yaw) <= MAX_YAW

            # 처음 몇 초: 이 사람의 '뜬 눈' EAR 을 잼
            if base is None:
                if usable:
                    cal.append(e)
                    cal_start = cal_start or now
                if cal_start and now - cal_start >= CAL_SEC and len(cal) >= 5:
                    base = float(np.median(cal))
                    print(f"[{time.strftime('%H:%M:%S')}] 기준 EAR {base:.3f} → 감음 {base * CLOSED_FRAC:.3f} 아래")
            elif usable:
                votes.append((now, e >= base * CLOSED_FRAC))
            while votes and now - votes[0][0] > 1.5:
                votes.popleft()
            eyes_open = None if not votes else (sum(1 for _, v in votes if not v) < CLOSED_RATIO * len(votes))

            if eyes_open is True:
                closed_since = None
                open_since = open_since or now
                if state != "AWAKE" and now - open_since >= a.wake:
                    state = "AWAKE"
                    print(f"[{time.strftime('%H:%M:%S')}] 판정: 깸")
                    if not a.dry:
                        send(adb, a.serial, cmd="baby", state=state, source="monitor")
            elif eyes_open is False:
                open_since = None
                closed_since = closed_since or now
                if state != "ASLEEP" and now - closed_since >= a.sleep:
                    state = "ASLEEP"
                    print(f"[{time.strftime('%H:%M:%S')}] 판정: 잠듦")
                    if not a.dry:
                        send(adb, a.serial, cmd="baby", state=state, source="monitor")
            else:
                closed_since = open_since = None

            n += 1
            if a.dry and n % 5 == 0:
                print(f"frame {n}: face={'Y' if face else '-'} ear={e if e is None else round(e, 3)} yaw={yaw if yaw is None else round(yaw, 2)} base={base} open={eyes_open} {ms:.0f}ms")

            if not a.no_window:
                if face:
                    cv2.rectangle(frame, face[:2], face[2:], (240, 197, 62), 2)
                if pts:
                    for i in LEFT + RIGHT:
                        cv2.circle(frame, tuple(int(v) for v in pts[i]), 2, (140, 214, 61), -1)
                # OpenCV 글씨는 한글이 안 나와서 영어로
                if base is None:
                    msg = f"CALIBRATING: keep eyes OPEN ({len(cal)})"
                elif e is None:
                    msg = "no face"
                elif not usable:
                    msg = "head turned - ignored"
                elif eyes_open:
                    msg = f"eyes OPEN {now - open_since:.1f}s"
                elif eyes_open is False:
                    msg = f"eyes CLOSED {now - closed_since:.1f}s / {a.sleep:.0f}s"
                else:
                    msg = "..."
                cv2.putText(frame, msg, (12, 32), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2)
                info = f"EAR {e:.3f}" if e is not None else "EAR -"
                if base is not None:
                    info += f" / limit {base * CLOSED_FRAC:.3f}"
                cv2.putText(frame, info, (12, 62), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (200, 200, 200), 2)
                cv2.putText(frame, f"sent: {state or '-'}   {ms:.0f}ms", (12, 92), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (65, 184, 245), 2)
                cv2.imshow(WIN, frame)
                if not top:
                    cv2.setWindowProperty(WIN, cv2.WND_PROP_TOPMOST, 1)  # 다른 창에 가려지지 않게
                    top = True
                key = cv2.waitKey(1) & 0xFF
                if key in (ord("q"), 27):
                    break
                if key == ord("c"):
                    base, cal, cal_start = None, [], None
                    votes.clear()
                    closed_since = open_since = None
            if a.frames and n >= a.frames:
                break
            time.sleep(0.03)
    finally:
        if cap is not None:
            cap.release()
        cv2.destroyAllWindows()
        mesh.close()


if __name__ == "__main__":
    main()
