# 모두의 자리 · 외부 시뮬레이터 (Pleos Connect Emulator v2.1.0+)
# 차량 상태는 Pleos Virtual Console REST(에뮬레이터 :8088)로, 차에 없는 입력(아기 · 수유 · 여정 · 내비 경로)은 앱 디버그 명령으로 보냄.
# 앱은 실제 SDK 모드로 그 상태를 읽기만 함. 앱은 디버그 빌드로 설치되어 있어야 함.
#
#   powershell -ExecutionPolicy Bypass -File everyseat-sim.ps1 -Step predrive   # 주행 전 시연 (앱 전체 창)
#   powershell -ExecutionPolicy Bypass -File everyseat-sim.ps1 -Step drive      # 주행 중 시연 (내비 옆)
#   powershell -ExecutionPolicy Bypass -File everyseat-sim.ps1 -Step demo       # 주행 전 + 주행 중 + 도착
#
#   powershell -ExecutionPolicy Bypass -File everyseat-sim.ps1 -Step phone      # 휴대폰 앱 연결 (중계 서버 · USB · 설치)
#   powershell -ExecutionPolicy Bypass -File everyseat-sim.ps1 -Step phonedrive # 휴대폰 시연: 내비 옆 + 경로 + 출발
#   powershell -ExecutionPolicy Bypass -File everyseat-sim.ps1 -Step phonearrive # 휴대폰 시연: 도착 4분 전 → 도착 → 문 잠금
#
# 한 단계씩:
#   reset full split console phone
#   trip trip2 route prepare interlock board
#   depart sleep slow feed wake rest cry comfort undo near arrive lock
#
# 콘솔 탭(http://127.0.0.1:8088)을 앞에 열어 두면 탭이 속도를 다시 계산해 자동 가속이 덮어써짐.
# 자동 시연은 탭을 닫거나 뒤로 두고, 콘솔 페달로 직접 몰 때만 탭을 앞에 둘 것.
#
# 주행 중 좌석 소폭 조절: Pleos 차량 서비스가 P단이 아니면 좌석 명령을 거부함.
# 그래서 D단에서는 앱이 콘솔 값만 바꾸고 화면에 "시연"으로 표시함 (P단 이동은 실제 SDK 명령).
param([string]$Step = "demo", [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")

# 휴대폰이 같이 연결돼 있어도 에뮬레이터로만 보냄
$emulator = & $Adb devices | Select-String '^(emulator-\d+)\s+device' | Select-Object -First 1
if (-not $emulator) { Write-Host "실행 중인 에뮬레이터를 찾지 못했어요"; exit 1 }
$env:ANDROID_SERIAL = $emulator.Matches[0].Groups[1].Value

$B = "http://127.0.0.1:8088"
$AppPkg = "ai.moduui.jari"
$NaviPkg = "ai.umos.maps.android.navigation.app"

# ---- 도구 ------------------------------------------------------------------------------------
function Forward { & $Adb forward tcp:8088 tcp:8088 | Out-Null }
function Beat { try { Invoke-WebRequest -UseBasicParsing -Method POST "$B/browser/session?client=everyseat-sim" | Out-Null } catch {} }
function Put([string]$path) { Beat; Invoke-WebRequest -UseBasicParsing -Method PUT "$B$path" | Out-Null }
function Wait([int]$sec) { for ($i = 0; $i -lt $sec; $i++) { Beat; Start-Sleep 1 } }
function Say([string]$m) { Write-Host ("[{0:HH:mm:ss}] {1}" -f (Get-Date), $m) }
function App([string[]]$kv) {
    $a = @("shell", "am", "broadcast", "-a", "ai.moduui.jari.debug.CMD", "-n", "$AppPkg/.debug.DebugCommandReceiver")
    for ($i = 0; $i -lt $kv.Length; $i += 2) { $a += @("--es", $kv[$i], $kv[$i + 1]) }
    & $Adb @a | Out-Null
}

# ---- 차량 상태 (Virtual Console) ---------------------------------------------------------------
function Gear([string]$g) { Put "/vehicle/gear?gear=$g" }
function Speed([int]$kmh) { Put "/vehicle/controls?speed=$kmh&accelerator=$([Math]::Min(100,[int]($kmh/3)))&brake=0&steering=0" }
function Door([string]$area, [bool]$open) { Put "/vehicle/body?$area=$($open.ToString().ToLower())" }   # door_fl door_fr door_rl door_rr
function DoorsLocked([bool]$on) { Put "/vehicle/body?doors_locked=$($on.ToString().ToLower())" }
function Occupied([string]$area, [bool]$on) { Put "/vehicle/seat?kind=occupied&area=$area&value=$($on.ToString().ToLower())" }  # driver passenger rear_left rear_right
function SeatPos([string]$control, [string]$area, [int]$v) { Put "/vehicle/seat?kind=position&control=$control&area=$area&value=$v" }  # slide recline

# 콘솔 탭이 앞에 열려 있으면 탭의 물리 계산이 속도를 0.14초마다 다시 써서 스크립트 속도가 덮어써짐.
# 이 스크립트 말고 다른 세션이 계속 있으면 탭이 열린 것. 세션은 3초 지나면 사라지므로 3초 넘게 지켜봄
# (뒤로 숨은 탭도 가끔 세션을 보내는데, 그 탭은 속도를 건드리지 않으므로 열린 것으로 치지 않음).
function ConsoleOpen {
    for ($i = 0; $i -lt 3; $i++) {
        Beat
        try { $n = ((Invoke-WebRequest -UseBasicParsing "$B/vehicle/state").Content | ConvertFrom-Json).browser.clients } catch { return $false }
        if ($n -lt 2) { return $false }
        if ($i -lt 2) { Start-Sleep -Milliseconds 1700 }
    }
    return $true
}

# ---- 창 배치 -----------------------------------------------------------------------------------
function Frames { @(& $Adb shell "dumpsys window windows | grep -oE '($AppPkg|$NaviPkg)/[A-Za-z.]+Activity[^,]*, frame=\[[0-9,]+\]' | sort -u") }
# 두 창이 다 잡히고 둘 다 좁아야 나란히 뜬 것 (창이 바뀌는 순간에는 넓은 창 둘이 같이 잡힘)
function IsSplit {
    $lines = Frames
    if ($lines.Count -lt 2) { return $false }
    foreach ($l in $lines) {
        if ($l -match 'frame=\[(\d+),\d+,(\d+),' -and ([int]$Matches[2] - [int]$Matches[1]) -ge 1000) { return $false }
    }
    return $true
}
function Split {
    if (IsSplit) { Say "내비와 앱이 이미 나란히 떠 있어요"; return }
    Say "내비 + 앱 나란히 (Pleos 멀티 윈도우)"
    & $Adb shell am start -n "$AppPkg/.ui.MainActivity" 2>$null | Out-Null; Start-Sleep 8   # 처음 뜰 때 오래 걸림
    & $Adb shell am start -n "$NaviPkg/.MainActivity" 2>$null | Out-Null; Start-Sleep 5
    for ($i = 0; $i -lt 3; $i++) {
        & $Adb shell input swipe 2550 717 1700 717 800   # 화면 오른쪽 끝 손잡이를 끌어 두 번째 창을 엶
        Start-Sleep 3
        if (IsSplit) { return }
    }
    Say "나란히 띄우지 못했어요. 화면 오른쪽 끝 손잡이를 직접 끌어 주세요"
}
# 앱만 넓게: 가운데 손잡이를 내비 쪽으로 끌면 분할이 풀림
function Full {
    if (IsSplit) {
        $app = (Frames | Where-Object { $_ -match $AppPkg } | Select-Object -First 1)
        $appLeft = ($app -match 'frame=\[853,')
        if ($appLeft) { & $Adb shell input swipe 1705 717 2500 717 800 } else { & $Adb shell input swipe 1705 717 1100 717 800 }
        Start-Sleep 3
    }
    & $Adb shell am start -n "$AppPkg/.ui.MainActivity" 2>$null | Out-Null
    Start-Sleep 3
}

# ---- 단계 --------------------------------------------------------------------------------------
function Reset {
    Say "초기화: P · 0km/h · 문 닫힘 · 좌석 가운데"
    Speed 0; Gear "Park"; DoorsLocked $false
    foreach ($d in "door_fl", "door_fr", "door_rl", "door_rr") { Door $d $false }
    foreach ($a in "passenger", "rear_left", "rear_right") { SeatPos "slide" $a 50; SeatPos "recline" $a 50; Occupied $a $false }
    Occupied "driver" $true
    # 내비가 나란히 떠 있을 때만 경로 취소 (앱만 넓게 떠 있으면 취소 요청이 내비를 앞으로 가져옴)
    if (IsSplit) { App @("cmd", "naviCancel") }
    App @("cmd", "allowance", "value", "0")
    App @("cmd", "feed", "minutesAgo", "60")
    App @("cmd", "baby", "state", "AWAKE", "source", "monitor")
}
function Trip { Say "여정: 강릉 · 할머니 1 · 아기 1 · 유모차 · 캐리어 2 (휴대폰에서 받은 것과 같음)"; App @("cmd", "trip", "destination", "강릉", "seniors", "1", "infants", "1", "strollers", "1", "suitcases", "2") }
function Trip2 { Say "여정 (짐 많은 날): 강릉 · 할머니 1 · 아기 1 · 유모차 · 캐리어 3 · 아기 짐가방 2 → 3열 접기 안내"; App @("cmd", "trip", "destination", "강릉", "seniors", "1", "infants", "1", "strollers", "1", "suitcases", "3", "babyBags", "2") }
function Route { Say "기본 내비에 강릉 경로 요청 (NaviHelper.requestRoute)"; App @("cmd", "naviRoute", "lat", "37.7519", "lng", "128.8761", "name", "강릉") }
function Prepare { Say "휴대폰 [출발 준비]: 5초 카운트다운 뒤 좌석을 맞춰요"; App @("cmd", "prepare") }
function Interlock {
    Say "안전 시연: 좌석이 움직이는 중 같은 쪽 문을 열면 바로 멈춰요"
    App @("cmd", "prepare"); Wait 7
    Door "door_rr" $true; Wait 4; Door "door_rr" $false
}
function Board {
    Say "탑승: 2열 오른쪽 문을 열면 출발 준비 화면이 자동으로 떠요"
    Door "door_rr" $true; Wait 4; Door "door_rr" $false
    Occupied "rear_left" $true; Occupied "rear_right" $true; Wait 2
}
function Depart {
    if (ConsoleOpen) {
        Gear "Drive"
        Say "출발: D 로 바꿨어요. 콘솔 탭이 앞에 열려 있어서 속도는 콘솔의 가속 페달로 직접 올려 주세요 (자동 가속은 탭을 닫고 실행)"
        return
    }
    Say "출발: D · 0→100km/h"; Gear "Drive"; foreach ($v in 20, 40, 60, 80, 100) { Speed $v; Wait 2 }
}
function BabyAsleep { Say "아기 잠듦 (카메라) → 안내 음성 작게 · 3초 뒤 보호자 자리 소폭 조절 · 속도 제안"; App @("cmd", "baby", "state", "ASLEEP", "source", "monitor"); Wait 7 }  # Sleep 은 Start-Sleep 별칭이라 못 씀
function Slow { if (-not (ConsoleOpen)) { Say "운전자가 속도를 80km/h 로 낮췄다고 가정 (앱은 차 속도를 바꿀 수 없음)"; Speed 80; Wait 3 } }
function Feed { Say "수유 15분 전 → 자는 중이면 제안을 미루고, 깨어 있으면 수유실 휴게소 제안"; App @("cmd", "feed", "minutesAgo", "165"); Wait 6 }
function Wake { Say "아기 깸 → 미뤄 둔 수유 휴게소 제안"; App @("cmd", "baby", "state", "AWAKE", "source", "monitor"); Wait 6 }
function Rest { Say "1번 휴게소 [경유하기] → 실제 내비에 경유지 추가"; App @("cmd", "rest", "index", "0"); Wait 6; App @("cmd", "drive"); Wait 3 }
function Cry { Say "아기 울음 (마이크) → 가까운 정차 지점 음성 안내"; App @("cmd", "baby", "state", "CRYING", "source", "monitor"); Wait 6 }
function Comfort { Say "[옮기기] 동승 보호자 자리를 아기 쪽으로 조금"; App @("cmd", "comfort"); Wait 6; App @("cmd", "drive"); Wait 3 }
function Near { Say "도착 4분 전 (에뮬레이터 내비는 시간이 줄지 않아 상황을 만듦) → 정차하면 조수석을 앞으로"; App @("cmd", "remain", "minutes", "4"); Wait 5 }
# 에뮬레이터 내비는 위치가 고정이라 스스로 도착하지 않고, 경로 취소 요청을 받아도 안내를 계속함.
# 그래서 도착은 앱에만 알리고, 내비 화면은 도착 뒤에도 안내 중으로 남음.
# ([안내 종료]를 대신 누르면 Pleos 가 내비와 앱을 함께 내리고 홈으로 돌아가므로 누르지 않음)
function Arrive {
    Say "도착: 내비 도착 → 감속 → P → 하차 준비 좌석 이동"
    App @("cmd", "arrive", "name", "강릉"); Start-Sleep 1
    if (-not (ConsoleOpen)) { foreach ($v in 60, 30, 0) { Speed $v; Wait 2 } } else { Speed 0 }
    Gear "Park"; Wait 12
}
function Lock { Say "문 잠금 → 카시트에 아기가 있으면 경고"; DoorsLocked $true; Wait 5; DoorsLocked $false }

function Predrive {
    Say "===== 주행 전 ====="
    Full; Reset; Wait 2
    Trip; Wait 5
    Prepare; Wait 28
    Board
    Say "주행 전 끝: 3D 세팅 화면에서 결과를 볼 수 있어요"
}
function DriveDemo {
    Say "===== 주행 중 ====="
    Split
    Route; Wait 6
    App @("cmd", "allowance", "value", "5")   # 시연용: 카시트 허용 각도 5%. 끝에서 0 으로 되돌림
    Depart; Wait 3
    BabyAsleep; Slow
    Feed
    Wake; Rest
    Cry; Comfort
    BabyAsleep; Near
    Arrive; Lock
    App @("cmd", "allowance", "value", "0")
    Say "주행 중 · 도착 끝"
}

# ---- 휴대폰 앱 연결 ------------------------------------------------------------------------------
# 휴대폰과 에뮬레이터가 둘 다 USB(adb reverse)로 PC 의 중계 서버 127.0.0.1:8090 에 닿게 함. Wi-Fi · 방화벽 설정이 필요 없음.
$Relay = "http://127.0.0.1:8090"
function RelayUp { try { Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 "$Relay/" | Out-Null; return $true } catch { return $false } }
function PhoneLink {
    if (RelayUp) {
        Say "중계 서버가 이미 켜져 있어요"
    } else {
        Say "중계 서버를 켜요 (최소화된 창 · 시연이 끝나면 닫으세요)"
        $env:PYTHONIOENCODING = "utf-8"
        Start-Process python -ArgumentList "`"$PSScriptRoot\phone-relay.py`"" -WindowStyle Minimized
        Start-Sleep 2
        if (-not (RelayUp)) { Say "중계 서버를 켜지 못했어요. python 설치를 확인하세요"; return }
    }
    & $Adb reverse tcp:8090 tcp:8090 | Out-Null
    Say "에뮬레이터 차 앱 연결 준비 끝"
    $list = & $Adb devices
    if ($list -match 'unauthorized') { Say "휴대폰 화면에서 [USB 디버깅 허용]을 눌러 주세요. 그다음 이 단계를 다시 실행하세요" }
    $phones = $list | Select-String '^(\S+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value } | Where-Object { $_ -notlike 'emulator-*' }
    if (-not $phones) { Say "연결된 휴대폰이 없어요. USB 로 연결하고 USB 디버깅을 켠 뒤 이 단계를 다시 실행하세요"; return }
    $apk = Join-Path $PSScriptRoot "..\..\..\Automatic_seat_phone\build\outputs\apk\debug\Automatic_seat_phone-debug.apk"
    foreach ($p in $phones) {
        & $Adb -s $p reverse tcp:8090 tcp:8090 | Out-Null
        if (Test-Path $apk) {
            Say "휴대폰($p)에 앱을 설치해요"
            & $Adb -s $p install -r "$apk" | Out-Null
            if ($LASTEXITCODE -ne 0) { Say "설치 실패. 휴대폰 화면에 허용 창이 떴는지 확인하세요"; continue }
        } else {
            Say "APK 가 없어요. 먼저 실행: .\gradlew.bat :Automatic_seat_phone:assembleDebug"
        }
        & $Adb -s $p shell am start -n "ai.moduui.jari.phone/.MainActivity" | Out-Null
        Say "휴대폰($p) 준비 끝: 차 화면 오른쪽 위 6자리 코드를 입력하세요"
    }
}

# ---- 실행 --------------------------------------------------------------------------------------
Forward
# 화면이 꺼져 있으면 (에뮬레이터 전원 버튼) 화면 조작이 모두 실패하므로 먼저 깨움
if ((& $Adb shell dumpsys power) -match 'mWakefulness=Asleep') {
    Say "에뮬레이터 화면이 꺼져 있어 깨워요"
    & $Adb shell input keyevent KEYCODE_WAKEUP; Start-Sleep 3
}
switch ($Step) {
    "reset"     { Reset }
    "full"      { Full }
    "split"     { Split }
    "console"   { Start-Process "$B/" }
    "phone"     { PhoneLink }
    # 휴대폰 앱 시연: 여정 · 출발 준비 · 아기 상태는 휴대폰에서 누르고, 차가 달리는 것만 스크립트가 함
    "phonedrive"  { Split; Route; Depart; Say "이제 휴대폰 [주행 중] 탭에서 아기 상태를 눌러 보세요" }
    "phonearrive" { BabyAsleep; Near; Arrive; Lock; Say "휴대폰 [주행 중] 탭에 도착 · 뒷좌석 경고가 떠요" }
    "trip"      { Trip }
    "trip2"     { Trip2 }
    "route"     { Route }
    "prepare"   { Prepare }
    "interlock" { Interlock }
    "board"     { Board }
    "depart"    { Depart }
    "sleep"     { BabyAsleep }
    "slow"      { Slow }
    "feed"      { Feed }
    "wake"      { Wake }
    "rest"      { Rest }
    "cry"       { Cry }
    "comfort"   { Comfort }
    "undo"      { Say "조치 되돌리기"; App @("cmd", "undoComfort") }
    "near"      { Near }
    "arrive"    { Arrive }
    "lock"      { Lock }
    "predrive"  { Predrive }
    "drive"     { DriveDemo }
    "demo"      { Predrive; DriveDemo }
    default     { Write-Host "unknown step: $Step" }
}
