# Baby Seat (베이비 시트)

> 아기 자리는 미리, 운전은 편하게.

**한 줄 소개:** 아기와 함께 타는 가족을 위해, 카시트 자리 · 좌석 자세를 미리 맞추고 주행 중 아기 상태와 수유 · 밥 시간에 맞춰 휴게소를 내비 경유지로 넣어 주는 Pleos Connect 앱입니다.
(예전 이름: 모두의 자리 / EVERY SEAT. 패키지 이름 `ai.moduui.jari`는 그대로입니다.)

## 제출 정보 (현대자동차그룹 차량용 앱 개발 공모전)

| 항목 | 내용 |
|---|---|
| 앱 이름 | Baby Seat (베이비 시트) · 팀 babyseatter |
| APK (차 앱, 심사 대상) | [`apk/babyseatter_BabySeat_release.apk`](apk/babyseatter_BabySeat_release.apk) |
| APK (보호자 휴대폰 앱) | [`apk/babyseatter_BabySeat_phone_release.apk`](apk/babyseatter_BabySeat_phone_release.apk) |
| Pleos Connect 에뮬레이터 | **v2.1.0** (AVD `Pleos_Connect_v2.1.0`) |
| 화면 규격 | **2560 x 1440**, 160 dpi (가로). 앱 창은 Pleos가 오른쪽 영역에 둠, 내비 옆 분할 창(약 840 x 1210)도 지원 |
| 사용한 Pleos SDK | Vehicle SDK 2.3.2 (좌석 · 기어 · 문 · 공조), NaviHelper 3.0.5 (경로 · 경유지 · 회전 안내), Gleo 음성 함수 |
| 휴대폰 | Android 8.0 이상 (시험: Galaxy A15, Android 16) |

### 테스트 환경
- Windows 11 Home (10.0.26200), 메모리 16GB, PowerShell
- Android Studio 내장 JDK(JBR 25)로 빌드, 소스 · 대상은 Java 17
- Pleos Connect Emulator v2.1.0 · 보호자 휴대폰(실기기 USB 또는 휴대폰 에뮬레이터)
- Python 3 (휴대폰 연결용 중계 서버 `Automatic_seat/tools/sim/phone-relay.py`, 표준 라이브러리만 씀)

### 실행 방법 (release APK)
1. 에뮬레이터 켜기 (DNS 옵션이 없으면 내비가 "서비스 연결 중"에서 멈출 수 있음)
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd Pleos_Connect_v2.1.0 -dns-server 8.8.8.8,168.126.63.1
   ```
2. 부팅 후 1분쯤 기다렸다가 차 앱 설치
   ```powershell
   adb -s emulator-5554 install -r apk\babyseatter_BabySeat_release.apk
   ```
3. 내비 위 알림 카드 허용 (Pleos는 시스템 앱 알림만 헤드업으로 띄워서, 앱이 다른 앱 위에 그리기로 카드를 그림)
   ```powershell
   adb -s emulator-5554 shell appops set --user 10 ai.moduui.jari SYSTEM_ALERT_WINDOW allow
   ```
4. 앱 목록에서 **Baby Seat**를 켜면 홈 화면(3D 좌석 · 연결 코드)이 뜹니다. 첫 실행 때 차량 속도 · 알림 권한을 허용해 주세요.

### 필요한 설정 (휴대폰 연결, 선택)
차 앱과 휴대폰 앱은 중계 서버를 거쳐 6자리 코드로 이어집니다. 두 앱 모두 `127.0.0.1:8090`을 쓰므로 PC에서 중계 서버를 켜고 USB로 이어 줍니다.
```powershell
python Automatic_seat\tools\sim\phone-relay.py 8090
adb -s emulator-5554 reverse tcp:8090 tcp:8090      # 차
adb -s <휴대폰 시리얼> install -r apk\babyseatter_BabySeat_phone_release.apk
adb -s <휴대폰 시리얼> reverse tcp:8090 tcp:8090    # 휴대폰
```
- 휴대폰 앱 [시작하기] → 아기 정보 → 차 화면의 6자리 코드 입력 → "차 연결됨".
- 휴대폰 AI 자세(Qwen3 0.6B, 기기 안에서 실행)는 앱 안에서 [AI 모델 받기](Wi-Fi, 약 340MB)로 받거나, 받은 파일을 넣어 주면 됩니다. 모델이 없으면 규칙으로 자세를 정합니다.
  ```powershell
  adb -s <휴대폰 시리얼> push Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm /sdcard/Android/data/ai.moduui.jari.phone/files/seat-ai.litertlm
  ```
- PC의 8090 포트를 다른 프로그램이 쓰고 있으면 `phone-relay.py 8091`로 켜고 `reverse tcp:8090 tcp:8091`로 이어 주세요.

> release APK에는 시연 명령(`everyseat-sim.ps1`)과 시연 버튼(↺ 처음부터 · P 정차 · D 출발) · 시연 주행 지도 · 웹캠 아기 모니터 연동이 들어 있지 않습니다. 자동 시연은 아래 "실행해 보기"처럼 debug 빌드로 확인해 주세요.
> 휴대폰 연동은 PC에서 `python Automatic_seat/tools/sim/phone-relay.py`를 켜고 `adb reverse tcp:8090 tcp:8090`을 한 뒤, 휴대폰 앱(`apk/babyseatter_BabySeat_phone_release.apk`)을 설치해 차 화면의 6자리 코드로 연결합니다.

아기와 함께 차를 타는 가족을 위한 **Pleos Connect(차량용 안드로이드) 탑승 도우미 앱**입니다.
누가 타고 무엇을 싣는지 알려 주면, 카시트 자리와 짐 공간을 계산해 **좌석을 직접 움직여 맞춰 주고**,
주행 중에는 아기 상태와 수유 시간을 지켜보며 수유실 있는 휴게소를 경유지로 넣어 줍니다.

---

## 무엇을 하나요?

| 언제 | 하는 일 |
|---|---|
| **출발 전** | 휴대폰 2D 자리 배치에 구성원·짐을 넣으면 → AI가 자리별 자세 추천 → [차로 보내기]를 누르면 좌석이 하나씩 움직임 |
| **주행 중** | 아기가 잠들면 조용히, 배고파하면 다음 수유 시간과 수유실 있는 휴게소 제안 → [경유]로 내비에 바로 추가 |
| **도착** | 도착 몇 분 전 알림, 문을 잠글 때 "뒷좌석 아기를 확인하세요" 경고 |

### 안전 설계
좌석을 자동으로 움직이는 앱이라 안전 장치를 먼저 넣었습니다.
- 움직이기 전 **5초 카운트다운**, 언제든 [취소]
- **P단(정차)에서만** 움직임. 도중에 P가 풀리면 즉시 멈춤
- 그 좌석에 **사람이 앉아 있으면 건너뜀**, 그쪽 **문이 열리면 즉시 멈춤**
- 한 번에 한 좌석, 한 방향만. 이전 위치를 기억해 [되돌리기] 가능
- 자리는 보호자가 고르고 AI는 자세만 제안, 카시트·에어백 안전 규칙은 차에서 한 번 더 검사

---

## 앱 두 개

| 앱 | 폴더 | 설명 |
|---|---|---|
| 🚗 **차 앱** | `Automatic_seat/` | 차 화면(Pleos Connect)에서 돌아가는 본체. 좌석 이동 · 내비 연동 · 주행 중 화면 |
| 📱 **휴대폰 앱** | `Automatic_seat_phone/` | 보호자 휴대폰. 차에 타기 전에 인원·짐을 미리 보내고, 주행 중 아기 상태를 눌러 알려 줌 |

휴대폰과 차는 **중계 서버**를 거쳐 연결됩니다. 시연에서는 PC에서 돌리는 작은 서버(`Automatic_seat/tools/sim/phone-relay.py`)를 씁니다.
차 화면 오른쪽 위의 **6자리 코드**를 휴대폰 앱에 넣으면 연결돼요.

---

## 실행해 보기

### 준비물
- Android Studio (최신)
- **Pleos Connect Emulator v2.1.0** (AVD 이름 예: `Pleos_Connect_v2.1.0`)
- Python 3 (휴대폰 연결 시연할 때만)
- Windows PowerShell (시연 스크립트)

### 1. 빌드
Android Studio로 이 폴더를 열고, 실행 대상에서 `Automatic_seat`(차 앱) 또는 `Automatic_seat_phone`(휴대폰 앱)을 골라 ▶ 실행.

명령어로 하려면:
```powershell
.\gradlew :Automatic_seat:installDebug          # 차 앱 → 에뮬레이터에 설치
.\gradlew :Automatic_seat_phone:assembleDebug   # 휴대폰 앱 APK 만들기
```

### 2. 에뮬레이터 켜기
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd Pleos_Connect_v2.1.0 -dns-server 8.8.8.8,168.126.63.1
```
> `-dns-server` 를 빼면 내비가 "서비스 연결 중입니다"에서 멈출 수 있어요.

### 3. 자동 시연 돌리기
에뮬레이터가 다 켜진 뒤:
```powershell
cd Automatic_seat\tools\sim
powershell -ExecutionPolicy Bypass -File everyseat-sim.ps1 -Step demo
```
차 상태(기어·속도·문·좌석)를 가상 콘솔로 바꿔 가며 **출발 전 → 주행 중 → 도착**을 자동으로 보여 줍니다.

| 명령 (`-Step ...`) | 보여 주는 것 |
|---|---|
| `predrive` | 출발 전: 여정 입력 → 추천 → 좌석 이동 |
| `drive` | 주행 중: 내비 옆 화면, 아기 잠듦 · 수유 · 휴게소 |
| `demo` | 위 둘 + 도착까지 전부 |
| `phone` | 휴대폰 앱 연결 준비 (중계 서버 켜기 · USB 연결 · 앱 설치) |
| `phonedrive` / `phonearrive` | 휴대폰으로 하는 주행 중 / 도착 시연 |

### 4. 휴대폰 연결 시연
1. 휴대폰을 USB로 연결하고 **USB 디버깅 허용**
2. `everyseat-sim.ps1 -Step phone` 실행 → 휴대폰에 앱이 설치되고 켜짐
3. 차 화면 오른쪽 위 **6자리 코드**를 휴대폰에 입력 → "차 연결됨"
4. 휴대폰에서 자리·짐 입력 → [차로 보내기] → 5초 카운트다운 → 좌석이 움직임

> 실제 휴대폰 대신 휴대폰 에뮬레이터를 같이 켜도 되지만, 메모리 16GB PC에서는 빠듯합니다.

---

## 알아 두면 좋은 점
- Pleos 에뮬레이터에서는 **주행 중(D단)에는 좌석 명령이 거부**돼요. 그래서 주행 중 좌석 소폭 조절은 화면에 **"(시연)"** 으로 표시됩니다.
- 에뮬레이터의 내비 위치는 고정이라 남은 시간이 줄지 않아요. 도착은 시연 스크립트가 흉내 냅니다.
- 휴게소 데이터는 데모용입니다(`assets/rest_areas_demo.json`). 실제로는 한국도로공사 공공데이터로 바꿀 예정.
- 음성 명령은 차의 **Gleo**(Pleos 음성 비서)가 알아듣고 앱에 전달합니다. 차 앱 안에는 LLM이 없어요. 휴대폰 앱은 기기 안에서 도는 소형 LLM(Qwen3 0.6B)으로 좌석 자세를 추천하고, 안전 규칙이 결과를 검사해요.

## 폴더 한눈에 보기
```
Automatic_seat/            차 앱
  src/main/java/ai/moduui/core/   판단 로직 (좌석 계획 · 도착 예측 · 휴게소 고르기) — 안드로이드와 무관
  src/main/java/ai/moduui/jari/   앱 (화면 · 차량/내비 연결 · 휴대폰 연결)
  src/debug/                      시연용 명령 수신기 (debug 빌드에만 들어감)
  tools/sim/                      시연 스크립트 · 휴대폰 중계 서버
Automatic_seat_phone/      휴대폰 앱
```

자세한 구조와 실행 규칙은 [`README_for_agent.md`](README_for_agent.md)에 정리돼 있습니다.
