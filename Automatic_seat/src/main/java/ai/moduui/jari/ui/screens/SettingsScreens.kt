package ai.moduui.jari.ui.screens

import ai.moduui.core.gleo.GleoContract
import ai.moduui.core.model.BabyProfile
import ai.moduui.core.model.BabyState
import ai.moduui.core.model.CarSeatDirection
import ai.moduui.core.model.SeatId
import ai.moduui.core.model.StateSource
import ai.moduui.core.model.TimeWindow
import ai.moduui.jari.BuildConfig
import ai.moduui.jari.control.UiState
import ai.moduui.jari.ui.MainViewModel
import ai.moduui.jari.ui.Screen
import ai.moduui.jari.ui.components.BigButton
import ai.moduui.jari.ui.components.DesignToggle
import ai.moduui.jari.ui.components.GhostButton
import ai.moduui.jari.ui.components.GradientButton
import ai.moduui.jari.ui.components.Panel
import ai.moduui.jari.ui.components.SegmentedChoice
import ai.moduui.jari.ui.components.SettingRow
import ai.moduui.jari.ui.components.Stepper
import ai.moduui.jari.ui.components.TitleMont
import ai.moduui.jari.ui.components.TwoPane
import ai.moduui.jari.ui.components.designCard
import ai.moduui.jari.ui.hm
import ai.moduui.jari.ui.theme.LocalPalette
import ai.moduui.jari.vehicle.SeatFeature
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalTime
import kotlin.math.roundToInt

/** 아기 (디자인 C-7 의 아기 프로필): 애칭 · 카시트 · 수유 · 낮잠. 정차 중에만 열림. */
@Composable
fun BabyScreen(ui: UiState, vm: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(start = 24.dp, top = 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        TitleMont("BABY", 34.sp)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth >= 1100.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    SettingsColumn(Modifier.weight(1f)) { ProfileSection(ui, vm) }
                    SettingsColumn(Modifier.weight(1f)) {
                        FeedSection(ui, vm)
                        OptionSection(ui, vm)
                        PrivacySection(vm)
                    }
                }
            } else {
                SettingsColumn(Modifier.fillMaxSize()) {
                    ProfileSection(ui, vm)
                    FeedSection(ui, vm)
                    OptionSection(ui, vm)
                    PrivacySection(vm)
                }
            }
        }
    }
}

/** 개발용 시험 · SDK 진단. debug 빌드에서만 레일에 보임 */
@Composable
fun DiagnosticsScreen(ui: UiState, vm: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(start = 24.dp, top = 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        TitleMont("DIAGNOSTICS", 34.sp)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth >= 1100.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    SettingsColumn(Modifier.weight(1f)) { TestSection(ui, vm) }
                    SettingsColumn(Modifier.weight(1f)) { DiagnosticsSection(ui, vm) }
                }
            } else {
                SettingsColumn(Modifier.fillMaxSize()) {
                    TestSection(ui, vm)
                    DiagnosticsSection(ui, vm)
                }
            }
        }
    }
}

@Composable
private fun SettingsColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = LocalPalette.current.sub, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().designCard(LocalPalette.current, 24.dp).padding(horizontal = 20.dp), content = content)
}

@Composable
private fun ColumnScope.ProfileSection(ui: UiState, vm: MainViewModel) {
    val p = LocalPalette.current
    val profile = ui.profile
    var nickname by remember(profile) { mutableStateOf(profile.nickname) }
    var age by remember(profile) { mutableIntStateOf(profile.ageMonths) }
    var dir by remember(profile) { mutableIntStateOf(profile.seatDirection.ordinal) }
    var allowance by remember(profile) { mutableIntStateOf(profile.carSeatReclineAllowance) }
    var interval by remember(profile) { mutableIntStateOf(profile.feedIntervalMin) }
    val nap = profile.napWindows.firstOrNull()
    var napStart by remember(profile) { mutableIntStateOf(nap?.start?.hour ?: 13) }
    var napEnd by remember(profile) { mutableIntStateOf(nap?.end?.hour ?: 15) }

    SectionLabel("아기 · 기기 안에 암호화 저장")
    SettingsCard {
        SettingRow("애칭", "차 화면과 음성 안내에서 이 이름으로 불러요") {
            BasicTextField(
                value = nickname,
                onValueChange = { nickname = it.take(10) },
                singleLine = true,
                textStyle = TextStyle(color = p.text, fontSize = 16.sp, textAlign = TextAlign.End),
                cursorBrush = SolidColor(p.accent),
                modifier = Modifier
                    .width(150.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.chip)
                    .border(1.dp, p.chipBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            )
        }
        // 카시트 종류와 방향: 뒤보기(후방) · 앞보기(전방) · 부스터
        SettingRow("카시트 · 방향", "뒤보기 카시트는 조수석 에어백 앞에 두지 않아요") {
            SegmentedChoice(CarSeatDirection.entries.map { it.labelKo }, dir, { dir = it }, Modifier.width(230.dp))
        }
        SettingRow("수유 간격", "수유 30분 전에 수유실 휴게소를 찾아 알림으로 알려 줘요") {
            Stepper("${interval / 60}시간 ${interval % 60}분", { interval = (interval - 30).coerceAtLeast(90) }, { interval = (interval + 30).coerceAtMost(300) }, "수유 간격", interval > 90, interval < 300)
        }
        SettingRow("낮잠 시작") {
            Stepper("${napStart}시", { napStart = (napStart - 1).coerceAtLeast(6) }, { napStart = (napStart + 1).coerceAtMost(napEnd - 1) }, "낮잠 시작", napStart > 6, napStart < napEnd - 1)
        }
        SettingRow("낮잠 끝", last = true) {
            Stepper("${napEnd}시", { napEnd = (napEnd - 1).coerceAtLeast(napStart + 1) }, { napEnd = (napEnd + 1).coerceAtMost(21) }, "낮잠 끝", napEnd > napStart + 1, napEnd < 21)
        }
    }
    GradientButton(
        "저장",
        {
            vm.controller.updateProfile(
                BabyProfile(
                    nickname = nickname.ifBlank { "아기" },
                    ageMonths = age,
                    seatDirection = CarSeatDirection.entries[dir],
                    feedIntervalMin = interval,
                    napWindows = listOf(TimeWindow(LocalTime.of(napStart, 0), LocalTime.of(napEnd, 0))),
                    carSeatReclineAllowance = allowance,
                    cameraWatch = profile.cameraWatch,
                ),
            )
        },
        Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ColumnScope.FeedSection(ui: UiState, vm: MainViewModel) {
    SectionLabel("수유")
    SettingsCard {
        SettingRow("마지막 수유", ui.lastFeedAt.hm()) {
            GhostButton("지금 수유했어요", { vm.controller.logFeed() }, height = 44.dp)
        }
        SettingRow("다음 수유", ui.nextFeedAt?.hm() ?: "-", last = true) {}
    }
}

/**
 * 선택 기능. 카메라: 차에 연결된 카메라가 아기 잠듦 · 깸을 알아채면 내비 위 알림으로 묻고,
 * [적용](또는 Gleo 에 "적용")을 받으면 안내 음성 크기를 바꾸고 노래를 틀어요.
 */
@Composable
private fun ColumnScope.OptionSection(ui: UiState, vm: MainViewModel) {
    SectionLabel("선택 기능")
    SettingsCard {
        SettingRow(
            "카메라로 아기 살피기",
            "차에 연결된 카메라가 아기가 잠들거나 깨면 알림으로 알려 줘요. [적용]을 누르거나 '적용'이라고 말하면 잠들면 안내 음성을 작게 하고 자장가를, 깨면 동요를 틀어요.",
            last = true,
        ) {
            DesignToggle(ui.profile.cameraWatch, { on -> vm.controller.updateProfile(ui.profile.copy(cameraWatch = on)) }, description = "카메라로 아기 살피기")
        }
    }
}

@Composable
private fun ColumnScope.PrivacySection(vm: MainViewModel) {
    SectionLabel("개인정보")
    SettingsCard {
        SettingRow("내 데이터 모두 지우기", "아기 정보 · 수유 기록 · 여정 기록은 이 차 안에만 암호화해 저장해요. 서버로 보내지 않아요.", last = true) {
            GhostButton("지우기", { vm.controller.wipePersonalData() }, height = 44.dp)
        }
    }
}

@Composable
private fun ColumnScope.TestSection(ui: UiState, vm: MainViewModel) {
    val p = LocalPalette.current
    var occInverted by remember { mutableStateOf(vm.graph.occupancyInverted) }
    SectionLabel("시험")
    SettingsCard {
        SettingRow("착좌 값 반대로 읽기", "에뮬레이터 실측 보정 · 실차에서는 끄고 확인") {
            DesignToggle(occInverted, {
                occInverted = it
                vm.graph.occupancyInverted = it
            }, description = "착좌 값 반대로 읽기")
        }
        // 시뮬레이션은 앱 밖에서: Pleos Virtual Console 이 바꾼 차량 상태를 앱이 실제 SDK 로 읽음
        SettingRow(
            "시뮬레이션",
            if (ui.simulation) "Pleos 차량 서비스가 없어 앱 안의 가짜 차량으로 동작 중"
            else "Pleos Virtual Console(PC 브라우저 127.0.0.1:8088)과 tools/sim 시나리오로 차량 상태를 바꿔요",
            last = true,
        ) {
            Text(if (ui.simulation) "앱 내장" else "외부 콘솔", color = if (ui.simulation) p.warn else p.accentText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
    // 화면에서 뺀 값: 나이 · 카시트 허용 각도 (저장된 값을 그대로 씀)
    SectionLabel("아기 (개발용)")
    SettingsCard {
        val profile = ui.profile
        SettingRow("나이", "${profile.ageMonths}개월") {}
        SettingRow("카시트 허용 각도", "제조사 설명서 기준. 0% 면 아기가 탄 좌석을 움직이지 않아요", last = true) {
            val a = profile.carSeatReclineAllowance
            Stepper("$a%", { vm.controller.setCarSeatAllowance(a - 1) }, { vm.controller.setCarSeatAllowance(a + 1) }, "카시트 허용 각도", a > 0, a < 10)
        }
    }
}

@Composable
private fun ColumnScope.DiagnosticsSection(ui: UiState, vm: MainViewModel) {
    val p = LocalPalette.current
    SectionLabel("SDK 진단")
    SettingsCard {
        DiagRow("실행 모드", if (ui.simulation) "시뮬레이션" else "Pleos 실차 SDK", if (ui.simulation) p.warn else p.accentText)
        DiagRow("Vehicle SDK ${BuildConfig.VEHICLE_SDK_VERSION}", if (ui.vehicle.connected) "연결됨" else "연결 안 됨 ${ui.vehicle.lastError ?: ""}")
        DiagRow("NaviHelper ${BuildConfig.NAVI_SDK_VERSION}", if (ui.navi.connected) "연결됨 ${ui.navi.navigationStatus ?: ""}" else "연결 안 됨 ${ui.navi.lastError ?: ""}")
        DiagRow("주행 상태", "${ui.driving.source} · ${ui.driving.gear ?: "-"}${ui.driving.speedKmh?.let { " · ${it.roundToInt()}km/h" } ?: ""}")
        DiagRow("음성 안내", ui.voiceEngine)
        DiagRow("Gleo 함수", GleoContract.functions.joinToString { it.name.lowercase() }, last = true)
    }
    GhostButton("지원 기능 다시 확인", { vm.controller.refreshCapabilities() }, Modifier.fillMaxWidth(), height = 48.dp, fontSize = 15.sp)
    SettingsCard {
        val seats = listOf(SeatId.R1_RIGHT, SeatId.R2_LEFT, SeatId.R2_RIGHT, SeatId.R3_RIGHT)
        seats.forEachIndexed { i, seat ->
            Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(seat.labelKo, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(SeatFeature.entries.joinToString("  ") { f -> "${f.labelKo} ${ui.vehicle.support(seat, f).labelKo}" }, color = p.sub, fontSize = 12.sp)
            }
            if (i < seats.lastIndex) androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
        }
        Text("앞뒤 이동 메서드: ${ui.vehicle.foreAftMethods.ifEmpty { listOf("찾지 못함") }.joinToString()}", color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
    }
}

@Composable
private fun DiagRow(key: String, value: String, valueColor: androidx.compose.ui.graphics.Color? = null, last: Boolean = false) {
    val p = LocalPalette.current
    SettingRow(key, last = last) {
        Text(value, color = valueColor ?: p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End, maxLines = 3, modifier = Modifier.width(240.dp))
    }
}

