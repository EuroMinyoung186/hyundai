# CLAUDE.md — Baby Seat (예전 이름: 모두의 자리 / EVERY SEAT)

> 11/15 사용자 결정: 화면 · 앱 이름 · 음성은 **Baby Seat**(Gleo 호출어 "베이비 시트"). 패키지 · 저장 키 · QR `everyseat://` · 시뮬 이름 `everyseat-sim`은 연결이 끊기지 않게 그대로 둔다. 아래 문서의 "모두의 자리"는 Baby Seat를 뜻한다.
> 출발 질문은 경로가 끝나거나 취소되면 다시 한다(같은 목적지로 다시 안내해도 물음). 휴대폰 AI 자세는 구성원 특징(기타: 허리 · 누워 · 멀미 · 잠)을 규칙으로 지키고, 3D와 같은 셈(`trunkFits`)으로 짐이 들어갈 때까지 마지막 줄 좌석을 당긴다.

새 세션은 이 파일을 먼저 읽고, 빌드 · 구조 · 시뮬 · 프로토콜 상세는 `README_for_agent.md`를 읽는다.
사용자와는 **한국어**로 대화한다. UI 문구 · 주석도 한국어(존댓말, 짧고 행동 중심).
git push · 커밋은 사용자가 직접 한다. 시키지 않으면 하지 않는다.

## 0. 지금 상황 (2026-11-13 기준)
- 현대자동차그룹 **차량용 앱 개발 공모전**(https://appdevchallenge.auto-hmg.io/) 출품작.
  - 접수 마감 **2026-11-15(일) 23:59**. 필수 제출물은 접수양식(기획서)이고, 이메일로 낸다.
  - GitHub 코드 + APK는 선택이지만 가산점이 있다. APK는 `apk/팀명_앱이름_release.apk`에 둔다.
  - README에 실행 방법 · **Pleos Connect 에뮬레이터 버전 · 화면 규격**을 적는다.
  - 저장소는 11/16까지 Public으로 바꾼다.
  - 심사 기준은 차량특화성 · 창의성 · 기술 활용성(Pleos SDK를 핵심 기능에 썼는지) · 사업성.
  - 로그인 · 계정 연동은 요구사항이 아니다.
- 세션을 나눠서 작업한다. **차 앱 세션은 `Automatic_seat/`만 고친다.** 휴대폰 앱(`Automatic_seat_phone/`)은 다른 세션 담당이다.
  - 두 앱이 함께 쓰는 것은 아래 4장의 상태 JSON 계약뿐이다.
  - 이 계약을 바꿀 때는 기존 키를 지우거나 이름을 바꾸지 말고, 추가만 한다.

## 1. UI/UX 기준 (차 앱)
- **디자인 원본:** `C:\Users\omniv\Downloads\moduui_ui_draft.pdf`
  - claude.ai의 "모두의 자리 UI 초안" 디자인 캔버스와 같은 내용이다.
  - 화면 번호(1-A, 1-C, 2-B, C-1a, C-3~C-6, P-1~P-8)는 이 PDF 기준이다.
  - 새 레이아웃을 만들기 전에 PDF에 그 화면이 있는지 먼저 본다. 사용자는 "디자인 있는데 왜 저렇게 해?"라고 지적한 적이 있다.
- **내비 옆 분할 화면 규칙:**
  - `AppRoot.kt`에서 `maxWidth < 1000.dp`이면 `besideNavi`(Pleos 멀티윈도우의 좁은 창)로 본다.
  - 이 창에는 PDF가 지도 옆에 그린 화면만 둔다: 1-A, C-3, 2-B, C-4, C-5, C-6 (= `NaviSideScreen`, 주행 중 화면).
  - 홈 · 3D · 아기는 앱 전체 화면에만 둔다. 사용자 말: "3d simul은 내비옆에 필요없지않나".
  - 주행 중에는 내비 옆 좁은 창이 기본이다(사용자 결정 11/13: "D단이면 항상 내비 옆").
    - 앱이 창을 나누는 공개 API는 없다(FLAG_ACTIVITY_LAUNCH_ADJACENT는 무시됨, 나누기는 system_server의 Pleos ConnectWindow가 처리).
    - 사용자가 ⋮로 한 번 나누면 시스템이 기억해서, 다음부터 앱 아이콘을 눌러도 내비 옆에 뜬다.
    - 주행 중 넓은 창이면 `DrivePanel`이 `SplitHint`(⋮ 끌기 안내)를 크게 보여 준다.
- **화면 구성** (`ui/AppRoot.kt`의 `Screen`, 왼쪽 `NavRail` 탭). 11/13 사용자 결정으로 차 앱은 꼭 필요한 것만 남겼다:

  | 탭 | 화면 | 파일 |
  |---|---|---|
  | 홈 | `HomeScreen`: 3D 좌석 상태(`HomeStage`) · 휴대폰 연결 코드/QR · 목적지(`DestinationCard`) · 좌석 단계 체크리스트(여정이 없으면 `WaitingCard`) | `screens/ParkedScreens.kt`, `ui/three/`, `components/GlassCar.kt` |
  | 아기 | `BabyScreen`: 애칭 · 카시트·방향 · 수유 간격 · 낮잠 · 마지막/다음 수유 · 내 데이터 지우기 | `screens/SettingsScreens.kt` |
  | 주행 중 | `DriveScreen` · `ArriveScreen` · 내비 옆 `NaviSideScreen` (지도 카드 `RouteMap`) | `screens/DrivingScreens.kt`, `components/RouteMap.kt` |
  | 진단 | `DiagnosticsScreen`: 시험 · SDK 진단 · 나이/카시트 허용 각도. **debug 빌드에서만** 레일에 보임 | `screens/SettingsScreens.kt` |

  - 뺀 것: 말로 바꾸기(`TripEditScreen`/`TripCard`, 인원·짐은 휴대폰에서만 받음), 예전 홈 카드(간편 자리 맞춤 등), 사용자용 SDK 진단.
  - **목적지:** 내비에 찍힌 목적지를 보여 주고, 검색어를 넣으면 `NaviHelper.requestNavigationScreen(SearchPlace)`로 내비 검색 화면을 연다(권한 `NAVI_CUSTOM_MAP` 필요). 즐겨찾기 · 최근 목적지(`getBookmarkInfo`/`getRecentDestinationInfo`)를 누르면 `requestRoute`.
  - **앱 안 지도:** 에뮬레이터 내비는 빈 지도 스타일(`connect_empty`)이라 주행 화면에 osmdroid + OpenStreetMap 타일 지도를 넣었다(release에도 INTERNET 권한). NaviHelper가 경로 좌표를 주지 않아 경로 선은 지점끼리 잇는 직선이다.
  - **내비 알림 활용 (11/13, `navi/RoadAhead.kt`)**: NaviHelper가 주행 중 알아서 보내는 `onTBTInfo` · `onCurrentRoadSpeedLimit` · `onCautionPointInfo` · `onCameraAlert` · `onDrivingInfo`를 `NaviState`(`nextTurn` · `speedLimit` · `cautions` · `camera` · `legs`)로 받는다.
    - 주행 화면 `RoadAheadCard`: 다음 회전 · 제한 속도 · 앞쪽 주의 지점. `RouteCard`: 다음 경유지(수유 휴게소)까지 거리 · 시간.
    - `RoadAdviceChip`: 아기가 자는데 과속방지턱 · 철길 · 파손 도로가 500m 안이거나 급회전 · 유턴이 300m 안이면 "○○이가 자요, 천천히" (규칙 기반, 속도는 바꾸지 않음).
      - 같은 내용을 작은 음성으로 지점마다 한 번만 말한다(`TripController.speakRoadAdvice`, 주행 중일 때만).
    - 수유 휴게소를 경유지로 넣었으면, 내비 `onWayPointArrived` 때 "○○ 도착 · 수유가 끝나면 [수유했어요]" 알림 띠 + 음성(`onWaypointArrived`).
    - 시험: `src/test/.../navi/RoadAheadTest.kt` (6개).
    - 에뮬레이터 내비는 위치가 고정이라 알림(Notify)은 오지 않는다. 대신 앱이 화면에 보일 때 `getTBTInfo`를 부르면 답이 와서, 내비 옆 창의 `RoadAheadCard`에 실제 내비 회전 안내("80m 좌회전 · 기업지원허브 방면")가 뜬다(11/14 확인, 앱이 가려져 있을 때 부른 것은 답이 없었음).
    - 내비 옆 분할: 오른쪽 끝 ⋮를 왼쪽으로 끌면(adb `input swipe 2547 717 1700 717 1000`) 내비 853..1694 + 앱 1718..2559로 나뉜다. 가운데 ⋮를 오른쪽으로 끌면 앱이 숨겨진다(`HOME_SCREEN hideTask`). D단 전환만으로는 앱이 숨겨지지 않는다(11/14 확인).
    - 앱이 화면에 안 보이면(내비가 전체 화면) 안드로이드가 앱 프로세스를 얼려서 내비 콜백이 쌓여 있다가 앱이 다시 보일 때 한꺼번에 온다(11/14 확인). 주행 중에는 내비 옆 분할 창으로 늘 보이게 둘 것. 시뮬레이션(`FakeNaviGateway.withFakeRoad`)에서는 6분마다 과속방지턱을 흉내 낸다.
    - **도로 따라 움직이기 (11/15, 시연):** 에뮬레이터 내비가 위치를 못 바꾸는 이유는 에뮬레이터 GPS NMEA 체크섬이 늘 `*47`이라 내비(`GpsLocationSource`)가 전부 버리기 때문(`geo fix` · `geo nmea`로도 안 됨). 내비 지도 배경도 시스템 앱 스타일이라 바꿀 수 없음.
      - 그래서 앱 지도에 실제 도로 경로(`assets/demo_route_gangneung.json`, OSRM에서 한 번 받아 둔 시흥동 → 강릉 193.7km, 494점)를 그리고 차를 움직임. `core/route/RoutePath` · `navi/DemoRoutes`(출발 · 목적지 3km 안일 때만) · `TripController.simulatePosition`.
      - debug 명령 `demoDrive [kmh 100] [x 30] [from 0]` / `demoDrive stop true` (`src/debug/.../DemoDrive.kt`). 끝까지 가면 도착. 시나리오 스크립트 `roll` · `rollstop`, `phonedrive`에 포함.
    - 참고한 샘플(pleos-sample-navi-app)은 API를 하나씩 불러 로그만 보여 주는 시험 앱이다. 화면 디자인은 없다.
  - **왼쪽 차량 패널**(`ai.umos.drivingview`, 안전벨트 · 차 그림 · 음악)은 Pleos 시스템 화면이라 앱이 없앨 수 없다. 앱 창은 시스템이 오른쪽 영역(853..2535)에 고정한다(`--windowingMode 1`로 띄워도 다시 그 자리로 돌아감).
  - 주행 중(`allowedWhileDriving=false`)에는 홈 · 아기 · 진단으로 갈 수 없다.
- **색 · 톤** (`ui/theme/Theme.kt`). **11/14부터 늘 밤 모드**(휴대폰 앱과 같은 다크, `ALWAYS_DARK = true`). false로 바꾸면 예전처럼 차의 주야간 모드(`isSystemInDarkTheme`)를 따른다:
  - **밤 모드 = 휴대폰 앱과 같은 색** (`DarkPalette`, 휴대폰 `Ui.kt`의 `C`):
    - 배경 `#0A0C0F`, 카드 `#14181D`, 선 `#262D35`
    - 글자 `#F2F5F7` / 보조 `#8B95A1`
    - 강조 `#3EC5F0` (진한 쪽 `#1A9FD6`)
    - ok `#3DD68C`, warn `#F5B841`, danger `#FF6B6B`
  - **낮 모드 = 디자인 초안의 일반(흰색) 모드** (`LightPalette`).
  - 3D 카드도 모드를 따른다: `SeatBox3D.kt`의 `D3`가 `Dark3D`(휴대폰 3D와 같음)와 `Light3D` 중 하나를 고른다. 카드 색을 직접 박지 말 것.
  - 에뮬레이터에서 시험: `adb shell cmd uimode night yes` / `no` (Pleos 시스템 화면도 같이 바뀜).
- **안전 UX** (지우거나 우회 금지):
  - 좌석은 `SeatStepRunner`만 움직인다.
  - 움직이기 전 5초 카운트다운과 [취소]가 있다.
  - P단에서만 움직이고, 사람이 앉은 좌석은 건너뛴다. 문이 열리면 즉시 멈춘다.
  - 한 번에 한 좌석만 움직이고, [되돌리기]가 있다.
  - 카시트 · 좌석 판단은 규칙 기반(`SeatPlanner`)이다. LLM 금지.
  - 주행 중 화면은 큰 글씨 · 큰 버튼 · 한 번에 한 가지만 둔다.

## 2. 휴대폰 앱 UX (차 앱과 맞춰야 할 부분)
휴대폰 앱은 갤럭시 A15 실기기(USB)에서 시험 중이다. 에뮬레이터 `EverySeat_Phone`은 차 에뮬레이터와 같이 켜면 메모리가 모자라서 지금은 쓰지 않는다.
- **P-1 시작:**
  - 로고와 [시작하기] 버튼 하나. Google·카카오 로그인은 뺐다(계정 기능 없음).
  - 등록된 차가 있으면 홈으로, 없으면 P-2 연결(차 화면의 6자리 코드 입력)로 간다.
  - 처음 한 번은 그 전에 **아기 설정**(`BabySetupScreen`)을 받는다: 애칭 · 나이 · 키 · 몸무게 · 카시트(있는지 · 방향 · 기울기) · 수유 간격. [나중에 할게요]로 건너뛸 수 있다. 다른 가족은 구성원 탭에서 더한다.
- **아래 탭 5개 (11/14):** 홈 / 구성원 / 3D 시뮬 / 주행 중 / 설정. 히스토리는 설정 안의 "히스토리" 줄에서 연다(‹ · 뒤로 가기 → 설정, 보는 동안 설정 탭에 불).
  - **홈:** 다음 여정(보내기 전 배치도 보임), 출발 준비 상태(보냄 → 차가 받음 → 준비됨), [출발 준비].
  - **주행 중 (P-8, `RoadTab`):** 아기 상태 · [아기 상태 알리기] · 다음 수유 · 휴게소. 늘 탭에 있고, 차가 주행 중(또는 D · R · N)이면 핸들 아이콘에 초록 점. 출발하면 이 탭으로 넘어간다(사용자: "주행중도 아래 탭에").
  - **여정 입력 = 2D 자리 배치** (11/14 사용자 결정: "자리는 사용자가 고르고, AI는 각도만"):
    - 목적지는 받지 않는다. 사용자 결정으로 목적지는 차 내비가 정한다.
    - 위에서 본 차 2D(`SeatLayout.kt`, `TripEditScreen`). 자리를 누르면 구성원(이봄) 또는 종류(어른 · 어르신 · 아이·부스터 · 아기·카시트)를 넣는다. 같은 구성원은 한 자리만(옮겨짐).
    - 카시트 · 부스터 아이는 1열 · 운전석에 넣을 수 없다(`LayoutRules.blocked`). 아기 옆 보호자 없음 · 짐 넘침은 경고만.
    - 트렁크를 누르면 구성원 짐을 넣거나 글로 적는다("24인치 캐리어", `PresetParser.luggageFrom`으로 부피 어림).
    - 여정 수(`TripDraft`)는 배치에서 센다. 배치는 폰에 저장된다(`LayoutStore`).
    - 말하기 입력은 뺐다(`TripParser`는 남아 있음, 디버그 intent `heard`만 씀).
    - [차로 보내기]를 누르면 3D 시뮬 탭으로 넘어간다.
  - **구성원:**
    - [사람 추가] · [짐 추가]를 누르고 한 줄로 적는다. 규칙 파서는 `Members.kt`의 `PresetParser`다.
    - 사람: "지민 5살 110cm", "둘째 18개월", "어머니". 나이 · 키로 카시트 단계(`SeatNeed`: 뒤보기 카시트 / 카시트 / 부스터 / 안전띠만)를 고른다. 기준값은 `SeatRule`.
    - 짐: "24인치 캐리어 18kg", "디럭스 유모차", "아이스박스 60x40x40". 크기(인치 · L · 가로x세로x높이) · 무게로 부피를 어림한다. 종류는 `Bag`.
    - 나이 · 키 · 크기 · 무게는 몰라도 된다. 비우면 종류 기본값을 쓴다.
    - 휴대폰에만 저장한다.
    - 아기 · 아이는 "카시트 있어요/없어요"를 받고, 있으면 방향(후방 · 전방 · 부스터)과 기울기(°)만 받는다.
  - **3D 시뮬:**
    - 차가 보낸 `seats`로 좌석 수 · 줄 수 · 앞뒤 · 등받이 · 착좌 · 전동 여부를 3D로 그린다.
    - 차가 보낸 `trunk`로 트렁크 상자와 짐 상자를 그리고, 용량을 넘는 짐은 주황으로 칠한다.
    - 좌석을 누르면 앞뒤 · 등받이 각도 슬라이더가 나온다. 차가 움직일 수 있는 축만 `seatAdjust`로 보내고, 못 움직이는 축은 폰에 기록만 한다.
    - 등받이 각도 표시는 세로에서 뒤로 기운 각도 **15°(가장 세움, 100) ~ 50°(가장 눕힘, 0)**, 보통 약 24°(75)다.
  - **AI 자세 (폰 안 LLM, Qwen3 0.6B, LiteRT-LM, 11/14부터):** 자리는 사용자가 고르고, LLM은 자리마다 등받이 각도 · 앞뒤만 낸다(`PosturePrompt`). 규칙(`PostureRules`)이 검사한다.
    - 카시트 자리는 LLM 답과 상관없이 20°, 뒤보기 카시트 앞자리는 앞뒤 70 이상. 고친 것은 "규칙이 고침"으로 보여 준다.
    - **자리 한계 (11/14, 프롬프트와 `PostureRules` 둘 다, 차 `SeatPlanner`도 한 번 더):** 조수석 15~30° · 앞뒤 75 이하(에어백), 뒷좌석 15~35°.
      - 운전석은 AI가 정하지 않고 차도 움직이지 않는다(착좌 확인 대상이 아님). 지금 자세가 운전 범위(18~28°, 앞뒤 25~75) 밖이면 범위 안으로 미리보기 + "운전할 수 없는 자세예요 · 직접 맞춰 주세요" 경고(`driverPose`). 에뮬레이터 기본값은 0/0(50°)이라 늘 경고가 뜬다.
    - 모델이 없거나 답을 못 알아들으면 기억 · 규칙(`SeatGuessRules`). 사람마다 지난 자세를 프롬프트에 [평소]로 준다.
    - **RAG (11/14):** `RideMatch.similar`(사람 50 · 짐 20 · 목적지 20 · 거리 10, 55점 이상)로 가장 비슷한 다녀온 여정을 찾고, 그 여정에서 이 사람이 내리기 전 자세(`PastPose`: 같은 자리 · 같은 사람 > 다른 자리 · 같은 사람 > 같은 자리 · 같은 종류)를 프롬프트에 `[비슷한 여정 87%: 24도 앞뒤50]`으로 준다.
      - 출처 우선순위: AI 답 > 비슷한 여정 > 기억 · 규칙. 모델이 없어도 비슷한 여정 자세를 쓴다. 카시트 20° 등 안전 규칙은 그대로 덮어쓴다. "참고한 기억"에 비슷한 여정 점수 · 이유가 보인다.
    - **지난번처럼 앉기 (11/14):** 여정 입력 위에 최근 다녀온 여정 · 보낸 여정 최대 3개(같은 배치는 하나). 누르면 그 배치를 2D에 채운다(`LayoutFromPast`: 이름이 같으면 구성원, "아이2"는 종류, 짐은 수만큼 기본 크기, 안전 규칙에 걸리는 자리는 뺌). 보낸 여정(`TripLog`)에도 자리 배치(`seats`)를 저장한다.
    - 3D에 미리 보이고, [차에 적용]을 누르면 좌석 하나씩 `seatAdjust`를 보낸다. 차가 그 좌석을 다 맞추면(`run.finished`) 다음 좌석. 15초 안에 시작하지 않거나 `allOk=false`(취소 · 문 · 앉은 좌석)면 멈춘다.
    - 예전 "AI가 자리 고르기"(`recommendSeats` · `PlanPrompt` · `PlanRules`)는 화면에서 뺐고 코드만 남아 있다.
  - **히스토리:** 사람별 좌석 기억(자주 앉는 자리 · 마지막 위치) → 다녀온 여정(내리기 직전 상태) → 차로 보낸 여정.
    - 다녀온 여정은 폰이 출발(driving)을 본 뒤 도착(`arrived`) 또는 P단이 되면 저장한다. 1분 미만 주행은 뺀다.
  - **설정:** 차 등록 해제 · 약관 · 버전.

## 3. 시험 환경 (이 PC에서 실측)
- **중계 서버 포트:**
  - PC 포트 8090은 Wondershare(`WsToastNotification.exe`)가 쓰고 있다.
  - 그래서 중계 서버를 **8091**에 띄운다: `python Automatic_seat/tools/sim/phone-relay.py 8091`
  - 모든 기기는 `adb -s <serial> reverse tcp:8090 tcp:8091`로 붙는다. 앱은 그대로 127.0.0.1:8090을 쓴다.
  - README_for_agent.md 5장의 `reverse tcp:8090 tcp:8090`보다 이 설정이 우선한다.
- **기기:**
  - 휴대폰 실기기: `R5CX91LYLWN` (SM-A156L, Android 16)
  - 차 에뮬레이터: `Pleos_Connect_v2.1.0`, 보통 `emulator-5554`. 반드시 `-dns-server 8.8.8.8,168.126.63.1`로 켠다.
  - 기기가 둘 이상이면 adb에 `-s` 또는 `ANDROID_SERIAL`을 꼭 준다.
- **가짜 차:** 차 에뮬레이터 없이 휴대폰만 시험할 때는 scratchpad의 `fake_car.py`(코드 482913)를 썼다. 진짜 차 앱을 붙일 때는 이것을 끈다.
- **adb · Gradle 오류가 간헐적으로 난다:**
  - Winsock 10014 "Bad address"로 adb 데몬이나 Gradle 데몬이 실패할 때가 있다.
  - adb는 잠시 뒤 `adb start-server`로 다시 하면 된다.
  - Gradle은 `-Pkotlin.compiler.execution.strategy=in-process`를 붙이면 잘 된다. PowerShell에서는 이 인자를 따옴표로 감싼다.
- **메모리 (11/13 실측):**
  - 에뮬레이터가 켜진 채 Gradle 데몬으로 빌드하면 PC 남은 메모리가 0.5GB대로 떨어진다.
  - 그러면 에뮬레이터 안의 차량 서비스 · SystemUI가 죽어 화면이 하얗거나 검게 된다.
  - 빌드는 `assembleDebug --no-daemon`으로 하고 `adb install -r`로 설치한다.
  - 에뮬레이터는 부팅 뒤 1분쯤 기다렸다가 앱을 켠다. 바로 켜면 차 앱이 시스템 화면을 덮는 전체 화면으로 뜬다.
  - 에뮬레이터 창은 화면 밖(-409,-226)에 열리므로 매번 가운데로 옮긴다(사용자 요청).
- 에뮬레이터 실측 제약(D단 좌석 거부, SDK 위치 읽기 안 바뀜, 내비 위치 고정 등)은 `README_for_agent.md` 4장 참고.

## 4. 차 → 휴대폰 상태 JSON (`jari/phone/PhoneLink.kt` `statusJson`)
휴대폰의 `CarStatus.parse`가 읽는다. 키를 지우거나 이름을 바꾸면 휴대폰이 깨진다.
- **기존 키:**
  - 상태: `gear` `speed` `driving` `departed` `arrived`
  - 여정 · 계획 · 진행: `trip{…}` `plan{summary,sdk,guide,notes}` `run{reason,done,total,countdown,finished,allOk,steps[]}` `pending`
  - 아기 · 수유 · 휴게소: `baby{state,label,minutes,source}` `minutesToFeed` `lastFeed` `rest{…}`
  - 그 밖: `comfort[]` `quiet` `voice` `remainMin` `babyEtaMin` `rearAlert`
- **`seats[]`** (11/13 추가): `{id,row,side,label,foreAftMove,backrestMove,foreAft?,backrest?,occupied?}`
  - 위치는 0..100이다. 앞뒤는 100 = 맨 앞, 등받이는 100 = 가장 세움.
  - 실차에서는 SDK `getCurrentSeat*Position`(`PleosVehicleGateway.watchSdkPositions`, 1초 폴링)을 읽고, 에뮬레이터에서는 콘솔 값을 읽는다.
  - 모르는 값은 키를 넣지 않는다.
  - **지금 문제 (11/13 확인):** 에뮬레이터에서 차가 모든 좌석을 `foreAft 0` · `backrest 0` · `backrestMove false`로 보낸다. 실제로 읽은 값이 아니면 키를 빼 줄 것.
    - 폰은 임시로 "모든 좌석이 0/0이면 모름"으로 처리한다. 이때 `trunk.slide`(뒤로 가 있어 100L 좁음)도 0 기준이라 폰이 되돌려 보여 준다.
    - **차 세션 답 (11/13):** 0은 에뮬레이터 콘솔이 실제로 돌려준 기본값이다. 차 화면에서 운전석을 맞추자 콘솔 값이 0 → 25로 바뀌어 그대로 왔다. 그래서 차는 0을 계속 보낸다.
      - `backrestMove false`는 이 에뮬레이터가 1열 등받이 전동을 지원 목록에 안 올려서다(`seatViews`가 `SeatFeature.BACKREST_MOVE == READ_WRITE`로 정함).
- **`trunk`** (11/13 추가): `{liters,used,rows,slide,folded[],basis}`
  - 계산은 `core/seat/TrunkEstimator.kt`가 한다. SDK에 트렁크 API가 없어서 어림값이다.
  - 2열 차 600L, 3열 차는 `TrunkMode`(500/850/1200L)를 기준으로, 마지막 줄 좌석 앞뒤 위치에 따라 ±100L를 더한다.
  - `SeatPlanner`는 아직 위치 보정 없이 `TrunkMode` 고정값으로 판단한다(바꿀 후보).
- **휴대폰 → 차 메시지 type:** `trip`(목적지는 빈 값) `prepare` `baby` `feed` `restRequest` `rest`. 상세는 README_for_agent.md 5장.
  - `feed` 선택 키 `minutesAgo` (11/15 추가): 휴대폰 [수유했어요] → 휠에서 "지금 ~ 4시간 전"을 고름. 차는 `TripController.logFeedAgo`로 그 시각을 마지막 수유로 기록(0 · 없으면 지금, 최대 720분).
- **`seatAdjust`** (11/13 휴대폰이 보내기 시작, **차 앱 처리 완료 11/13**): `{type:"seatAdjust", seat:"R2_RIGHT", foreAft?:0..100, backrest?:0..100}`
  - 차: `PhoneLink.handle` → `TripController.adjustSeat` → `SeatStepRunner.run`(5초 카운트다운). 진행은 기존 `run{…}` 키로 휴대폰에 간다. 에뮬레이터에서 실제로 움직이는 것을 확인했다.
  - 차 홈 3D 화면에서 좌석을 눌러 맞추는 것도 같은 길이다.
  - 3D에서 좌석을 눌러 직접 맞춘 목표 위치다. 차가 움직일 수 있다고 알린 축(`foreAftMove`/`backrestMove`)만 온다.
  - 반드시 `SeatStepRunner`로 실행한다: P단 · 5초 카운트다운 · [취소] · 앉은 좌석 건너뛰기 · 문 열림 정지를 그대로 거친다.
- **`babyProfile`** (11/13 휴대폰이 보내기 시작, 차 앱은 아직 처리 안 함 → 지금은 "unknown message" 로그만 남음):
  `{type:"babyProfile", name, ageMonths?, heightCm?, weightKg?, hasCarSeat?, carSeatDir?:"REAR"|"FRONT"|"BOOSTER", carSeatAngle?, feedEveryMin?}`
  - 차 연결을 마칠 때 한 번 보낸다. 차의 아기 화면(애칭 · 카시트 · 방향 · 수유 간격)에 채워 주면 사용자가 두 번 입력하지 않아도 된다.
- **`baby` 확장 — 아기 상태 알리기** (11/14 휴대폰이 보내기 시작, 차 앱은 아직 `state`만 읽음):
  `{type:"baby", state?:"ASLEEP"|"AWAKE"|"CRYING", label, emoji, custom, actions:[{do, value?}]}`
  - 휴대폰: 주행 중이면 탭 위 "주행 중 · 아기 상태 알리기" 띠 → `CareScreen`에서 버튼을 하나 고르고 [보내기] (`BabyCare.kt`).
  - 기본 버튼은 잠들었어요 · 깼어요 · 울어요(`state` 있음). 보호자가 버튼을 더할 수 있다("똥 쌌어요" 등, `state` 없음 · `custom:true`).
  - `actions`의 `do`: `NAVI_QUIET`(내비 안내 소리 작게) `NAVI_NORMAL`(원래대로) `MUSIC`(value=노래 종류) `MUSIC_OFF` `TEMP_UP`/`TEMP_DOWN`(실내 1도) `NEARBY`(value=장소 · 예: 기저귀 갈 곳 · 수유실 · 소아과). 최대 3개, 반대 일은 같이 오지 않는다.
  - 할 일은 폰 LLM(Qwen3)이 추천하고 목록 밖 답은 버린다. 모델이 없으면 낱말 규칙(`CareRules`). 보호자가 직접 바꿀 수 있다.
  - **차 세션 답 (11/14, 처리 완료):** `PhoneLink` → `TripController.onPhoneBaby`.
    - `state`가 있으면 `reportBaby`. `label`이나 `actions`가 있으면 할 일을 실행하고 내비 위 알림 카드(`📱 emoji label` + 한 일)를 띄운다.
    - `NAVI_QUIET`/`NAVI_NORMAL`: **앱 안내 음성 크기만** 바뀐다. 내비 자체 음량을 바꾸는 공개 API는 Pleos SDK · AAOS 모두 없다(시스템 권한 필요).
    - `MUSIC`: value에 자장 · 잔잔 · 백색 · 수면이 있으면 자장가(작게), 아니면 동요. 앱이 직접 만든 가락을 미디어 오디오로 튼다(`audio/CabinMusic.kt`). `MUSIC_OFF`는 끈다.
    - `TEMP_UP`/`TEMP_DOWN`: Pleos HVAC `setHvacTemperature`로 앞줄 좌우를 1도씩 바꾼다(권한 `CAR_CLIMATE` · `CONTROL_CAR_CLIMATE`).
    - `NEARBY`: 수유 → 수유실 휴게소, 기저귀 · 화장실 · 정차 · 휴게 → 가까운 휴게소를 찾아 휴게소 알림 카드(1번 · 2번 경유 · 괜찮아요). 경로를 모르면 내비 검색("휴게소"/"수유실"). 소아과 · 약국 같은 그 밖의 값은 내비 장소 검색을 연다.
- **차가 보내기 시작한 키 (11/13):** `naviDestination` · `routeKm` (아래 요청분), 그리고 `road{speedLimit?, turn?, turnM?, turnText?, caution?, cautionM?, waypoint?, waypointM?, waypointMin?, advice?, urgent?}` (경로 안내 중일 때만, 모르는 값은 키 없음). 폰 주행 화면에 앞길 안내로 쓰면 된다.
- **새 선택 키 (차 → 휴대폰, 보내 주면 여정 기억이 좋아짐):**
  - `routeKm`: 내비 경로 총 거리(km). 비슷한 지난 여정을 찾을 때 거리를 비교한다.
  - `naviDestination`: 내비 목적지 이름. 없으면 `trip.destination`을 쓴다.
  - `arrived`를 도착 때 정확히 true로 보내 주면, 폰이 그때 "내리기 전 좌석 상태"를 저장한다(지금은 P단 전환으로도 잡음).
- **`trip` 선택 키** (11/13 휴대폰이 보내기 시작, 차 앱은 아직 안 읽음 → 차 세션에서 받으면 좋음):
  - `luggageLiters`: 적어 둔 크기로 셈한 짐 부피 합(L). 빈틈 계산 전 값이라, 차에서 `PACKING_EFFICIENCY`로 나눠 쓰면 된다. 지금 차는 개수 × `LuggageType.liters`로만 센다.
  - `seatNeeds`: 넣은 아이들의 카시트 단계 배열. 값은 `REAR_SEAT` · `CAR_SEAT` · `BOOSTER` · `BELT`.
    - 차의 `infants`/`children` 칸 매핑: 뒤보기 · 카시트 → `infants`(카시트), 부스터 → `children`, 안전띠만 → `adults`.
    - 뒤보기 카시트 아이는 앞좌석 에어백 앞에 두면 안 된다. 이 규칙을 차의 `SeatPlanner`에 넣을지 검토할 것.
  - `seatPlan` (11/14, **차 앱 처리 완료**): `[{seat:"R2_RIGHT", label:"이봄", who:"BABY", need?:"REAR_SEAT", foreAft?:0..100, backrest?:0..100}]`. 보호자가 2D에서 고른 자리 + 휴대폰 AI 자세다. 운전석은 넣지 않는다.
    - 차: `PhoneLink.seatPlanOf` → `TripRequest.seatPlan`(`PlannedSeat`) → `SeatPlanner.placeFromPhone`가 그 자리를 따른다. 카시트 · 부스터 1열은 거절하고 노트를 남긴다. 아기 옆 같은 줄 어른 한 명은 보호자로 본다.
    - 자세는 규칙 단계(카시트 뒤로 · 등받이 세우기 · 후방 카시트 앞자리 · 3열 다리 공간)가 이미 정한 축에는 쓰지 않고, 나머지 축만 "○○ 자세 · 휴대폰" SDK 단계로 더한다. 시험: `SeatPlannerPhoneTest`.
    - 휴대폰 [차로 보내기] 한 번에: AI 자세 계산 → `trip`(+`seatPlan`) → 바로 `prepare`. 차는 5초 카운트다운 · [취소] · P단 · 앉은 좌석 · 문 확인을 그대로 거친다(11/14 사용자: "누르면 그냥 해줘").
- **차 좌석 실행 고침 (11/14, 에뮬레이터에서 확인):**
  - 앱을 켤 때 등받이 이동 지원 확인이 빈 결과로 굳어 휴대폰에 `backrestMove false`가 갔다 → 실패하거나 이동 축이 하나도 없으면 5초 간격 5번 다시 확인(`PleosVehicleGateway.refreshCapabilities`).
  - 목표를 지나침(65 → 75) → 멈춘 뒤 0.8초 기다려 읽고, 잰 속도로 남은 거리의 60%씩 짧게 끊어 맞춘다(`SeatStepRunner.fineTune`). 시험: 35/80 · 55/70 · 40/60 그대로 맞음.
  - 차량 서비스가 잠깐 다시 붙는 사이 멈춤 명령이 닿지 않아 좌석이 끝까지 간 일이 있었다 → 멈춤은 실패하면 4번까지 다시 보낸다(`stopSafely`).

## 5. 조사한 참고값 (2026-11-13, 출처는 휴대폰 `Members.kt`의 `SeatRule` · `Bag` 주석)
- **카시트 단계:**
  - 6세 미만은 법정 의무다(도로교통법 제11조 1항 · 제50조 1항).
  - 15개월 미만 또는 76cm 미만은 뒤보기다.
  - 76~105cm는 하네스 카시트, 105~145cm는 부스터, 145cm부터 안전띠만 쓴다.
  - 13세 미만은 뒷좌석을 권장한다.
- **짐 부피 (L, 접은 외곽 기준):**
  - 캐리어: 20인치 40, 24인치 60, 26인치 70, 28인치 90, 30인치 120
  - 유모차: 휴대용 55, 절충형 125, 디럭스 240(분리하면 약 160), 쌍둥이 310
  - 기저귀 가방 20, 아이스박스 40~70, 휴대용 아기침대 40
- **실차 트렁크 (VDA 기준):**
  - 아반떼 CN7 479, 쏘나타 480, 투싼 NX4 546~620
  - 싼타페 MX5 725(5인승) / 628(6·7인승), 3열을 쓸 때 값은 미확인
  - 팰리세이드 LX3: 3열 뒤 317, 3열 접으면 729. 다른 자료는 348이라 어느 쪽이 맞는지 미확인
  - 카니발 7인승: 3열 뒤 627
  - 아이오닉5 527 (2열 접으면 1,587), EV9: 3열 뒤 333, 3열 접으면 828
  - `TrunkEstimator` · `TrunkMode`의 고정값(500/850/1200, 2열 600)을 차종별 값으로 바꿀 때 쓴다.
  - VDA는 1L 블록 기준이라 실제로 실을 수 있는 양은 더 적다. 박스 부피 합이 VDA 용량의 약 80% 이하일 때 들어간다고 보는 건 추정이다.

## 5-1. 내비 위 알림 · 카메라 옵션 (11/14, 차 앱)
- **출발 알림 (11/14)** (`AlertKind.PLAN`, `TripController.askFeedTime`/`askMealTime`): 내비 길안내가 새 목적지로 시작되면 오른쪽 위 카드로 한 번에 하나씩 묻는다.
  - 1) "🍼 다음 수유는 언제쯤이에요?" [30분 뒤][1시간 뒤][2시간 뒤][그대로/모름] → `TripSession.feedDueOverride`(수유를 기록하면 지움). 30분 전에 수유실 휴게소 제안.
  - 2) "🍚 밥은 언제 먹을까요?" [1시간 뒤][2시간 뒤][3시간 뒤][안 먹어요] → `TripSession.mealAt`. 20분 전에 휴게소 제안(🍚, 남은 주행이 짧으면 안 함). 도착하면 지움.
  - 휴대폰 상태에 `nextFeedAt` · `mealAt`(HH:mm) · `minutesToMeal` 키를 더했다. 시험: `FeedMealTest`.
- **내비 오른쪽 패널 (11/15, `alert/NaviSidePanel.kt`)**: 사용자 요청 "완전 다른 앱 같은 거 말고 내비 일부처럼 오른쪽에".
  - 앱이 가려져 있고(`AppGraph.appVisible`, MainActivity onStart/onStop) 경로 안내 중이면, 창 나누기(⋮) 없이 내비 위 오른쪽에 붙어 뜸. 다른 앱 위에 그리기(오버레이 카드와 같은 권한). 앱이 보이면 숨김.
  - 내비 카드 모양(흰 카드 · 검은 머리띠): 아기 상태 · 다음 수유 · 남은 시간 · 실제 도로 지도(차 따라감) · 휴게소 제안 [1 · 2 · 괜찮아요] · [앱 열기]. [접기 ›]로 작은 탭이 됨.
  - 크기: 폭 600 · 높이 1180, 내비 오른쪽 버튼 줄 왼쪽(오른쪽 160)에. 글씨 28~32sp · 버튼 84dp.
  - 패널이 펼쳐져 있으면 휴게소 알림 카드는 띄우지 않고(패널에 같은 내용), 다른 카드는 패널 폭으로 패널 위에(`OverlayAlerts.relayout`), 노래 카드는 패널 왼쪽.
- **영상 시연 방식 (11/15, 사용자 결정)**: "이동은 시뮬, 이벤트 순간은 내비". 내비 화살표는 GPS 체크섬 때문에 못 움직이므로:
  - **내비 위 시연 지도** (`src/debug/.../NaviMapLayer.kt`): 내비 안내 화면의 빈 지도 자리에 실제 지도(OSM) + 붉은 경로 + 내비 모양 파란 화살표(진행 방향으로 돎). 회전 안내판 · 도착 카드 · [2D] · 음량/설정 자리는 투명하게 뚫어 내비 것이 보임(좌표는 2560x1440 안내 화면 기준).
    - 안드로이드 12+는 터치를 넘기는 오버레이를 강제로 alpha 0.8로 만들어 → 지도가 보일 때는 터치를 받는 불투명 창, 숨길 때만 터치를 내비로 넘김.
  - **화면 전환기** (`DemoDirector`, debug 명령 `demo on true [reroute 8] [info 7]`): D → 시연 지도 / 들를 곳 제안 → 오른쪽 패널(지도 위) / [경유] → 지도를 걷고 내비의 경유지 재탐색 화면 8초 → 지도 / 휴게소 도착 → P단 + "○○ 도착 · 정차 중" 알림(D로 바꾸면 사라지고 출발) / 그 밖의 일 → 오른쪽 위 알림.
  - **오른쪽 패널** (`alert/NaviSidePanel.kt`, main): 들를 곳은 실제 내비처럼(흰 카드 · 검은 머리띠 · [경유], 줄을 누르면 주소 · 도착 예정 · 벗어나는 거리). 그 밖의 일(잠듦 · 깸 · 노래 · 음량 · 좌석)은 오른쪽 위 작은 알림(앱 밤 톤) → 누르면 "지금 이렇게 맞춰 두었어요" 상세. Gleo "1번 휴게소 들러 줘"(`AddNursingStop(choice)`)도 됨.
  - NaviHelper `Foreground` 요청은 내비가 받기만 하고 앞으로 오지 않음 → 시연에서는 내비 실행 인텐트로 엶.
  - 스크립트: `everyseat-sim.ps1 -Step film`(강릉 경로 → 지도 켜기 → 출발) 뒤 `feed` · `sleep` · `depart`. `rollstop`으로 끔.
- **출발 알림 바뀜 (11/15 사용자 결정)**: 길안내를 시작하면 "👶 모두의 자리 기능을 사용하시겠습니까?" [예] [아니요 (10)] — 10초 타이머는 [아니요]에서 돌고 0이면 아니요(`TripController.askUseApp`). Gleo "예"(`ACCEPT_CAR_SUGGESTION`, choice 없음)도 [예].
  - [예] → "마지막 수유 H:mm" [−1시간][−10분][+10분][+1시간][확인] → 그 시각 + 설정의 수유 간격으로 다음 수유. [아니요] → 이번 여정은 수유 · 밥 휴게소 제안 끔(`TripSession.assistOn`).
  - 예전 "다음 수유는 언제쯤이에요?" · "밥은 언제 먹을까요?" 카드는 안 씀(코드는 남음).
- **아기 밥(이유식) 시간 (11/15)**: 어느 정도 크면 수유 대신 밥 → `BabyProfile.mealTimes`(차 아기 탭 "밥 · 아침/점심/저녁" 켜기 + 30분 단위). 주행 중 다음 밥 시각 20분 전에 휴게소 제안(`TripSession.nextMealAt`). 시험: `MealTimesTest`.
  - 휴대폰 설정 탭 "○○ · 수유 · 밥" 카드에서도 수유 간격 · 밥 시간을 바꾸고 `babyProfile`로 차에 보냄(새 키 `mealTimes: ["12:00","18:00"]`).
  - 휴대폰 [수유했어요]는 시 · 분 휠로 실제 시각을 고름(지금보다 뒤면 어제).
- **시연 버튼 (debug)**: 내비 화면 아래 가운데 [↺ 처음부터] [P 정차] [D 출발] (`DemoReset`, 앱 시작 때 `DebugHooks`). 처음부터 = 경유지 빼기 → 경로 취소(경유지가 있으면 내비가 취소를 거부하고 멈춤, NOT_CANCEL_ABLE_TRIP 507) · 제안/알림 지움 · P · 아기 깸 · 수유 1시간 전.
- **알림은 내비 위 카드**(`alert/OverlayAlerts.kt`): Pleos는 시스템 앱 알림만 헤드업으로 띄운다(로그 `NOTIFICATION_INJECTOR isSystemImportantNotification privileged=false`). 그래서 앱이 다른 앱 위에 그리기(`SYSTEM_ALERT_WINDOW`)로 직접 카드를 그린다. 허용이 없으면 알림 센터에만 남는다(`SystemAlerts`).
  - 에뮬레이터에서 허용: `adb shell appops set --user 10 ai.moduui.jari SYSTEM_ALERT_WINDOW allow`. 실차에서 이 권한을 받을 수 있는지(심사 · 설정 화면)는 아직 모른다.
  - 색은 앱 밤 톤(카드 `#14181D`, 테두리 · 버튼 `#3EC5F0`). 주행 화면 지도 위 칩(`MapColors`)도 같은 톤으로 바꿨다(사용자 요청 "색상 디자인은 앱처럼").
- **수유 30분 전**(`TripSession.FEED_LEAD_MINUTES = 30`): 수유실 휴게소를 찾아 휴게소 알림 카드로 띄운다. 자는 중이어도 미루지 않는다(음성만 작게).
- **카메라 옵션**(아기 탭 > 선택 기능 "카메라로 아기 살피기", `BabyProfile.cameraWatch`, 기본 꺼짐):
  - 켜면 카메라(`StateSource.BABY_MONITOR`)가 본 잠듦 · 깸을 "📷 ○○이가 잠들었어요 [적용][괜찮아요]" 카드로 묻는다. 끄면 카메라 상태는 쓰지 않는다.
  - [적용] 또는 Gleo에 "적용" → 잠듦: 안내 음성 작게 + 자장가 작게, 깸: 안내 음성 원래대로 + 동요.
  - Gleo 함수는 앱당 3개까지라 `ADD_NURSING_STOP`을 **`ACCEPT_CAR_SUGGESTION`**("지금 제안한 것을 받아들임": 카메라 [적용] 또는 수유실 휴게소 경유)으로 바꿨다(`schema.json` · 매니페스트 같이).
- **앱이 가려져 있어도 하는 일은 화면에 보이게** (11/14 사용자: "화면으로 보여야지"):
  - 노래가 나오는 동안 오른쪽 아래(내비 음량 버튼 왼쪽)에 "🎵 자장가 트는 중 [끄기]" 작은 카드(`AlertKind.MUSIC`, 노래가 멈출 때까지 남음).
  - 도착하면 "🏁 강릉 도착 · 뒷좌석 아기 확인 [앱 열기][확인]"(`AlertKind.ARRIVE`). 문 잠금 때 아기가 있으면 "🚨 카시트에 아기가 있어요".
  - 위쪽 카드는 내비 [2D] 버튼을 가리지 않게 130dp 아래부터. 이미 경유지로 넣은 휴게소는 다시 권하지 않는다.
  - 휴게소 후보는 `assets/rest_areas_demo.json`이다. NaviHelper가 경로 위 휴게소 목록을 주지 않는다.
    - 11/15부터 실제 휴게소 3곳(OpenStreetMap): 경기광주(광주원주고속도로 원주 방향) · 횡성 · 평창(영동고속도로 강릉 방향). 시연 경로 위 **강릉 방향 쪽**만 넣음(반대쪽을 넣으면 내비가 유턴 경로를 만듦). 수유실 여부는 미확인이라 `verified=false`.
    - (11/15 사용자 결정) [경유]를 고르면 경로 전체 보기는 다시 띄우지 않고 바로 이어서 달림. 시연에서는 오른쪽 위 "☕ ○○ 경유로 바꿨어요" 알림만. `showRouteOverview()`는 코드에 남아 있음. 예전에는 [경유] 2.5초 뒤 `requestNavigationScreen(OverviewRoute)`를 불렀음. 에뮬레이터에서 내비가 "경유지 1개 포함 · 2시간 8분 · 193km"로 다시 찾고, 도착 카드가 "경기광주휴게소 27분 · 33km"로 바뀌는 것을 확인.
    - 내비 차 화살표는 못 움직임(GPS 체크섬). 영상은 "내비는 장면마다(목적지 · 경유지 추가) + 주행은 앱 지도 시뮬"로 찍기로 함.
- 에뮬레이터 창은 Android Studio에 가려지므로 시연할 때는 맨 위 고정(user32 `SetWindowPos HWND_TOPMOST`)으로 둔다.
- **웹캠 아기 모니터 (11/15, 시험 도구)**: `D:\venvs\babycam\Scripts\python.exe Automatic_seat/tools/sim/baby-cam.py [--cam 0]`
  - C 드라이브가 좁아 D 드라이브 가상환경(mediapipe 0.10.14 · onnxruntime)에 설치했다. 시스템 파이썬에 mediapipe를 넣으면 numpy · OpenCV가 바뀌니 넣지 말 것.
  - 얼굴은 YOLO ONNX(`yolov8n-face`)로 찾는다. 눈은 MediaPipe Face Mesh 눈꺼풀 점으로 EAR을 잰다.
    - 처음 3초 눈 뜬 모습을 기준으로 삼고, 그 65% 아래면 감음이다. 고개를 많이 돌린 장은 판정에서 뺀다.
    - YOLO 눈 모델 · OpenCV 눈 분류기는 뜬 눈을 감음으로 봐서 뺐다.
  - 1.5초 다수결로 감음이 5초 이어지면 ASLEEP, 뜸이 2초면 AWAKE를 debug 명령 `baby source monitor`로 보낸다(차의 "📷 잠들었어요 [적용]").
  - 얼굴 모델은 처음 실행 때 `tools/sim/models/`로 받는다(gitignore). 카메라 1번은 OBS 가상 카메라다.
  - 카메라는 D단 · 주행 중에만 켠다(11/15 사용자 결정). 스크립트가 1초마다 debug 명령 `cameraState`로 물어서 "on"일 때만 웹캠을 연다. 차도 `TripController.cameraOn()`이 false면 카메라 판정을 버린다.
- **휴대폰 AI 자세 속도 (11/15)**: `SeatLlm.askOnce`가 지시문별 대화를 미리 계산(prefill, KV 캐시)해 두고 복제해서 쓴다. 엔진 준비 때 예전 자리 고르기 지시문은 계산하지 않는다. CPU 스레드 최대 6, 문맥 1024토큰. 에뮬레이터 실측: 엔진 48초, 답 77초(고치기 전, CPU만 쓰는 x86 에뮬레이터).
- **좌석 값 우선순위 (11/15 사용자 결정)**: 가장 최근에 보낸 값이 기준이다.
  - 휴대폰 `seatPlan` 자세(AI 또는 보호자가 고친 값)는 같은 좌석 · 축의 차 규칙 단계(카시트 뒤로 · 등받이 세우기 등)를 대신한다. 카시트 20°는 휴대폰 AI 규칙이 지킨다.
  - `seatAdjust` · 차 3D로 직접 맞춘 값은 `TripController.manual`에 남아 이후 [적용] 때도 그대로 쓴다. 새 여정을 받으면 지운다.
  - 차가 계속 지키는 것: 주행 중 한계(조수석 앞뒤 75 · 30°, 뒷좌석 35°), 뒤보기 카시트 앞좌석 공간(규칙 값보다 뒤로 안 감), 운전석 고정, SeatStepRunner 안전 장치.
  - 차 3D의 [적용]은 한 번 누르면 5초 카운트다운 뒤 맞춘다(예전 "누르고 있으면").
- 시험 명령(debug): `camera value true|false` · `careApply` · `stopMusic` · `diaper`(휴대폰 "💩 똥 쌌어요" + `NEARBY 기저귀 갈 곳`과 같음). 시나리오 스크립트에 `diaper` 단계를 더했다.
- 앱이 가려져 있으면(내비 전체 화면, 여정 전이라 포그라운드 서비스도 없음) 안드로이드가 앱을 얼려 내비 경로를 늦게 받는다. 여정 중에는 `TripMonitorService`가 켜져 있어 괜찮다.

## 6. 남은 일 · 후보 (차 앱 쪽)
- 실차 연결은 아직 안 했다. 실차는 USB reverse를 쓸 수 없어서, 중계 서버를 공개 주소(https)로 올리고 `PHONE_RELAY_URL`을 바꿔야 한다. 외부에 서버를 여는 일이라 사용자 확인이 필요하다.
- 차 에뮬레이터 + 휴대폰 실기기로 실제 연결 시연(1단계)은 아직 하지 않았다. 다음에 할 일로 잡혀 있다.
- 3열 차는 에뮬레이터에 3열 좌석이 없어서 화면으로 확인하지 못했다(SDK 값으로는 ROW_3도 있다).
- 디버그 도구: `src/debug/.../SeatProbe.kt`
  - `seatCaps`는 축별 지원 좌석을 보여 주고, `seatMove`는 좌석 하나를 지정 시간 동안 움직인다.
  - `DebugCommandReceiver`로 부른다. 2열 전동(앞뒤 · 등받이 · 높이 · 기울기)이 에뮬레이터에서 실제로 움직이는 것을 확인했다.
