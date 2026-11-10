# README_for_agent — 모두의 자리 (EVERY SEAT)

이 파일은 코드 작업을 하는 에이전트용 요약입니다. 사람이 읽는 소개는 `README.md`에 있어요.
UI 문구와 주석은 한국어로 써요. 사용자에게 보이는 문자열도 한국어로 유지하세요.

## 1. 한 줄 요약
Pleos Connect(AAOS) 차량 앱과 보호자 휴대폰 앱으로 이뤄져 있어요. 탑승 인원과 짐을 받아 카시트·좌석 배치를 **규칙으로** 계산하고, Pleos Vehicle SDK로 좌석을 움직여요. 주행 중에는 아기 상태, 수유 시간, 수유실 휴게소를 관리하고 NaviHelper SDK로 경유지를 넣어요.

## 2. 빌드
| 항목 | 값 |
|---|---|
| Gradle 모듈 | `:Automatic_seat`(차), `:Automatic_seat_phone`(휴대폰). `settings.gradle.kts`는 `app`·`EV_Curation` 폴더가 있을 때만 포함해요(이 저장소에는 없음). |
| AGP / Kotlin | AGP 9.2.1, Kotlin은 AGP 내장(2.3.21). `kotlin-android` 플러그인을 **추가하지 말 것**(androidApplication + kotlinCompose만 사용). |
| SDK | compileSdk/targetSdk 37. minSdk는 차 28, 휴대폰 26. Java 17 타깃. |
| 버전 카탈로그 | `gradle/libs.versions.toml` |
| Pleos 저장소 | `https://nexus-playground.pleos.ai/repository/maven-releases/` (인증 없음). `ai.pleos.playground:Vehicle:2.3.2`, `NaviHelper:3.0.5`, `LLM:2.1.3.2`(debug 전용) |
| `local.properties` | 커밋하지 않음. 없으면 `ANDROID_HOME`이 필요해요. |

```powershell
.\gradlew :Automatic_seat:compileDebugKotlin          # 차 앱 컴파일 확인
.\gradlew :Automatic_seat:installDebug                # 실행 중인 에뮬레이터에 설치
.\gradlew :Automatic_seat_phone:assembleDebug         # → Automatic_seat_phone/build/outputs/apk/debug/Automatic_seat_phone-debug.apk
.\gradlew :Automatic_seat_phone:testDebugUnitTest     # TripParser 테스트 11개
```
- 차 앱에는 단위 테스트가 없어요(`Automatic_seat/src/test` 없음). 검증은 컴파일과 에뮬레이터 시연으로 해요.
- 차 앱 실행 액티비티: `ai.moduui.jari/.ui.MainActivity`. 휴대폰 앱: `ai.moduui.jari.phone/.MainActivity`.

## 3. 차 앱 구조 (`Automatic_seat/src/main/java/ai/moduui/`)
```
core/                        안드로이드와 무관한 순수 로직
  model/Models.kt            TripRequest, SeatId, BabyState, StateSource, LuggageType …
  seat/SeatPlanner.kt        카시트·보호자 자리·3열·트렁크 판단 (규칙 기반, LLM 금지, 결정적)
  seat/SeatPlan.kt           계획 결과 (sdkSteps / guideSteps / notesKo)
  eta/EtaPredictor.kt        아기 동반 도착 예측 (수유·기저귀 정차 시뮬레이션 + 과거 기록 보정)
  reststop/RestStopSelector.kt  수유 시간에 맞는 수유실 휴게소 후보
  gleo/GleoContract.kt       Gleo 함수 계약 (assets/schema.json과 짝)
  session/                   TripSession, CarAction
jari/
  AppGraph.kt                수동 DI. Pleos 차량 서비스가 있으면 실제 SDK 게이트웨이, 없거나 forceSim이면 Fake
  ModuuiApp.kt               Application. controller.start() → phoneLink?.start()
  control/TripController.kt  핵심 상태 머신. StateFlow<UiState> 하나로 화면 전체를 그림
  control/SeatStepRunner.kt  좌석 단계 실행기 (카운트다운 · P단 · 착좌 · 문 열림 정지 · 되돌리기)
  vehicle/                   VehicleGateway (Pleos / Fake). ConsoleSeatPositions = 에뮬레이터 콘솔에서 좌석 위치 읽기 + keepAlive
  navi/                      NaviGateway (Pleos NaviHelper / Fake)
  car/                       주행 상태(기어·속도) 소스
  gleo/Gleo.kt               Gleo 액션 수신과 7초 안에 보내는 콜백
  phone/PhoneLink.kt         휴대폰 중계 서버 폴링 (아래 5장)
  service/TripMonitorService.kt  주행 중 포그라운드 서비스 (여정 중에만 켬)
  ui/                        Compose. screens/ParkedScreens.kt(출발 전) · DrivingScreens.kt(주행 중, 내비 옆) · SettingsScreens.kt
  ui/three/                  3D 차 모델 (MeshBuilder)
assets/schema.json           Gleo 함수 3개: PREPARE_DEPARTURE(activity), REPORT_BABY_STATE / ADD_NURSING_STOP(receiver)
assets/rest_areas_demo.json  데모 휴게소
```
**데이터 흐름:** 입력(Gleo · 휴대폰 · 디버그 명령 · 화면) → `TripController.planTrip()` → `SeatPlanner.plan()` → 화면에서 확인 → `confirmDeparture()` → `SeatStepRunner`가 `VehicleGateway`로 한 단계씩 실행 → `UiState.run`에 진행 상황 표시.

## 4. 시뮬레이션과 시연 (Pleos Connect Emulator v2.1.0)
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd Pleos_Connect_v2.1.0 -dns-server 8.8.8.8,168.126.63.1
powershell -ExecutionPolicy Bypass -File Automatic_seat\tools\sim\everyseat-sim.ps1 -Step demo
```
- **차량 상태**는 Virtual Console REST로 바꿔요(`adb forward tcp:8088 tcp:8088`, `http://127.0.0.1:8088`). 엔드포인트: `PUT /vehicle/gear?gear=P|D`, `/vehicle/controls?speed=…`, `/vehicle/body?door_fl=true|doors_locked=true`, `/vehicle/seat?kind=occupied|position…`, `GET /vehicle/state`.
  - 쓰기 전 3초 안에 `POST /browser/session?client=x`를 보내지 않으면 쓰기가 무시돼요(`emulator_inactive`).
- **차에 없는 입력**(아기 상태, 수유, 여정, 내비 경로, 도착)은 debug 빌드의 `DebugCommandReceiver`로 보내요.
  ```
  adb shell am broadcast -a ai.moduui.jari.debug.CMD -n ai.moduui.jari/.debug.DebugCommandReceiver --es cmd <cmd> [--es key value …]
  ```
  - cmd 목록: `baby(state, source)` `feed(minutesAgo)` `trip(destination, seniors, adults, children, infants, strollers, suitcases, babyBags)` `naviRoute` `prepare` `rest(index)` `drive` `comfort` `undoComfort` `remain(minutes)` `allowance(value)` `naviCancel` `arrive(name)` `gear` `speed` `occupied` `door` `forceSim(value)` `clock` `llm`
  - `gear`·`speed`·`occupied`·`door`는 Fake 모드 전용이에요.
- **스크립트 단계** (`-Step`):
  - 묶음: `predrive`, `drive`, `demo`, `phone`, `phonedrive`, `phonearrive`
  - 단건: `reset full split console trip trip2 route prepare interlock board depart sleep slow feed wake rest cry comfort undo near arrive lock`
- `everyseat-sim.ps1`은 **반드시 UTF-8 with BOM**으로 저장해요(PowerShell 5.1이 한국어를 깨뜨림). 휴대폰이 같이 연결돼 있으면 `ANDROID_SERIAL`을 에뮬레이터로 지정해요.

### 에뮬레이터에서 실측한 제약 (코드를 고치기 전에 알아 둘 것)
- **D단 좌석 명령 거부:** Vehicle 서비스가 D단에서는 좌석 명령을 거부해요(IllegalArgumentException 50001). 그래서 주행 중 소폭 조절은 콘솔 값만 바꾸고 `StepOutcome.SIMULATED`, 화면에는 "(시연)"으로 표시해요.
- **좌석 위치 읽기:** SDK로 읽는 좌석 위치는 바뀌지 않아요. debug 빌드는 콘솔 `/vehicle/state`에서 위치를 읽어요(`ConsoleSeatPositions`). 콘솔은 마지막 세션 후 약 3초가 지나면 비활성이 되고, 그때는 좌석이 움직이지 않아요. 그래서 2초마다 `keepAlive()`를 보내요.
- **내비:** 위치가 (127.0947, 37.4127)에 고정이라 남은 시간이 줄지 않고 실제 도착 이벤트도 없어요. 도착은 `remain`/`arrive` 명령으로 흉내 내요.
- **문 잠금 이벤트:** 네 문을 한 DoorArea로 등록해야만 들어와요.
- **착좌 값:** 에뮬레이터에서는 반대로 와요(`occupancyInverted` 기본값 켜짐).
- **Gleo LLM SDK:** ON_DEVICE는 꺼져 있어요. SERVER는 Pleos에서 받은 clientId/secret이 필요해요. 앱의 판단 로직에는 LLM을 쓰지 않아요.
- **메모리:** 16GB PC에서 차 에뮬레이터와 휴대폰 에뮬레이터를 같이 켜면 메모리가 모자라요. 휴대폰 에뮬레이터는 `-memory 2048 -no-audio`로 켜요.

## 5. 휴대폰 연결 프로토콜
휴대폰 ↔ PC 중계 서버(`tools/sim/phone-relay.py`, 127.0.0.1:8090, 메모리 저장) ↔ 차. 두 기기 모두 `adb reverse tcp:8090 tcp:8090`으로 접속해요.

| 누가 | 요청 | 내용 |
|---|---|---|
| 차 | `GET /car/inbox?code=&after=` (0.7초마다) | `{connected, latest, messages[{type, seq, at, age, …}]}` |
| 차 | `POST /car/status?code=` | 차 상태 JSON (계획 · 진행 · 아기 · 수유 · 휴게소 · 남은 시간) |
| 휴대폰 | `POST /phone/pair {code}` | `{ok}`, 실패 시 `error: wrong_code` 또는 `car_offline` |
| 휴대폰 | `POST /phone/send {code, type, …}` | type: `trip` `prepare` `baby` `feed` `restRequest` `rest` |
| 휴대폰 | `GET /phone/status?code=` (1초마다) | `{car, status}`, 짝이 없으면 404 |

- **연결 코드:** 차 prefs `phone_link_code`에 고정이에요(6자리).
- **안전 규칙:** 차는 앱 시작 전에 쌓인 메시지를 실행하지 않아요(`latest`로 건너뜀). 20초보다 오래된 메시지도 버려요(특히 `prepare`).
- **중계 주소:** `BuildConfig.PHONE_RELAY_URL`이에요. debug 빌드만 `http://127.0.0.1:8090`이고, release는 빈 값이라 기능이 꺼져요.
- **휴대폰 앱 파일** (`Automatic_seat_phone/.../phone/`):
  - `TripParser.kt`: 한국어 문장을 인원·짐으로 바꾸는 규칙 파서. LLM 없음. 테스트 있음.
  - `PhoneVm.kt`: 상태와 폴링.
  - `Relay.kt`: HTTP.
  - `CarStatus.kt`: 차 상태 파싱.
  - `Ui.kt`: 화면. 디자인 P-1 · P-2 · P-4 · P-8.
- debug 빌드에서는 음성 입력 대신 `am start … --es heard "<문장>"`으로 문장을 넣어 시험할 수 있어요.

## 6. 지켜야 할 규칙
- **좌석 판단:** `SeatPlanner`는 규칙 기반으로 결정적이어야 해요. LLM을 넣지 마세요.
- **안전 장치:** 좌석 이동은 `SeatStepRunner`만 거쳐요. 카운트다운, P단 확인, 착좌·문 확인, 되돌리기를 지우거나 우회하지 마세요.
- **debug 전용 코드:** 시연용 수신기와 LLM 시험 코드는 `src/debug`에만 둬요. release에 외부 노출 컴포넌트를 추가하지 마세요(Gleo 수신기는 예외이며 입력을 검증해요).
- **개인정보:** release 로그에 개인정보를 남기지 마세요. Gleo 콜백(암시적 브로드캐스트)에는 상태 단어만 넣어요.
- **화면 배치:** 내비 옆 분할 화면에는 지도와 함께 봐야 하는 화면(주행 중 화면)만 둬요. 3D, 홈, 설정은 앱 전체 화면에 둬요.
- **문구:** UI 문구는 존댓말 한국어로, 짧고 행동 중심으로 써요.
