package ai.moduui.jari.ui

import ai.moduui.jari.control.Focus
import ai.moduui.jari.ui.components.Ic
import ai.moduui.jari.ui.components.RailTab
import ai.moduui.jari.ui.screens.ArriveScreen
import ai.moduui.jari.ui.screens.BoardingScreen
import ai.moduui.jari.ui.screens.DriveScreen
import ai.moduui.jari.ui.screens.HomeScreen
import ai.moduui.jari.ui.screens.NaviSideScreen
import ai.moduui.jari.ui.screens.SettingsScreen
import ai.moduui.jari.ui.screens.TripEditScreen
import ai.moduui.jari.ui.theme.LocalPalette
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 왼쪽 레일 탭 4개 (디자인 CarHome: 홈 · 세팅 · 주행 중 · 설정) */
enum class Tab(val labelKo: String, val icon: String) {
    HOME("홈", Ic.HOME),
    SETUP("세팅", Ic.SEAT),
    DRIVE("주행 중", Ic.DRIVE),
    SETTINGS("설정", Ic.GEAR),
}

enum class Screen(val tab: Tab, val allowedWhileDriving: Boolean) {
    HOME(Tab.HOME, false),
    TRIP(Tab.HOME, false),
    BOARDING(Tab.SETUP, false),
    DRIVE(Tab.DRIVE, true),
    ARRIVE(Tab.DRIVE, true),
    SETTINGS(Tab.SETTINGS, false),
}

private fun Tab.screen() = when (this) {
    Tab.HOME -> Screen.HOME
    Tab.SETUP -> Screen.BOARDING
    Tab.DRIVE -> Screen.DRIVE
    Tab.SETTINGS -> Screen.SETTINGS
}

@Composable
fun AppRoot(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val p = LocalPalette.current
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }

    LaunchedEffect(ui.focusSeq) {
        when (ui.focus) {
            Focus.BOARDING -> if (!ui.isDriving) screen = Screen.BOARDING
            Focus.DRIVE, Focus.REST_STOP -> screen = Screen.DRIVE
            Focus.ARRIVE -> screen = Screen.ARRIVE
            Focus.NONE -> Unit
        }
    }
    // 주행 중에는 주행 화면(큰 버튼·짧은 글)만. 입력·설정 화면은 막음 (Pleos 주행 제한 정책)
    LaunchedEffect(ui.isDriving) {
        if (ui.isDriving && !screen.allowedWhileDriving) screen = Screen.DRIVE
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to p.bgTop, 0.6f to p.bgBottom)),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 창이 좁으면 내비 옆(Pleos 멀티 윈도우 세로 창): 내비에 필요한 화면만, 레일 없이.
            // 넓히면(가운데 ⋮ 를 왼쪽으로) 앱 전체: 홈 · 3D 세팅 · 설정.
            val besideNavi = maxWidth < 1000.dp
            if (besideNavi) {
                NaviSideScreen(ui, vm)
            } else {
                Row(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    NavRail(screen.tab, ui.isDriving) { tab -> screen = tab.screen() }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (screen) {
                            Screen.HOME -> HomeScreen(ui, vm) { screen = it }
                            Screen.TRIP -> TripEditScreen(ui, vm) { screen = it }
                            Screen.BOARDING -> BoardingScreen(ui, vm) { screen = it }
                            Screen.DRIVE -> DriveScreen(ui, vm)
                            Screen.ARRIVE -> ArriveScreen(ui, vm) { screen = Screen.HOME }
                            Screen.SETTINGS -> SettingsScreen(ui, vm) { screen = it }
                        }
                    }
                }
            }
            // 주행 · 도착 화면은 칩으로 알림을 보여 주므로, 그 밖의 화면에서만 아래 알림 띠
            val banner = ui.banner
            val showsOwnMessages = if (besideNavi) ui.isDriving || ui.departed || ui.arrived else screen.tab == Tab.DRIVE
            if (banner != null && !showsOwnMessages) {
                // 시뮬레이션 지도 화면(말로 바꾸기)은 아래 경로 띠 위로 올림
                val lifted = !besideNavi && screen == Screen.TRIP && ui.simulation
                BannerBar(banner, Modifier.align(Alignment.BottomCenter).padding(bottom = if (lifted) 104.dp else 28.dp)) { vm.controller.clearBanner() }
            }
        }
    }
}

@Composable
private fun NavRail(current: Tab, driving: Boolean, onSelect: (Tab) -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(48.dp)
    Column(
        Modifier
            .width(96.dp)
            .fillMaxHeight()
            .then(if (p.isDark) Modifier.shadow(18.dp, shape, ambientColor = p.bgBottom, spotColor = p.bgBottom) else Modifier.shadow(18.dp, shape, ambientColor = p.cardShadow, spotColor = p.cardShadow))
            .clip(shape)
            .background(p.railBg)
            .border(1.dp, p.railBorder, shape),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        for (tab in Tab.entries) {
            RailTab(tab.labelKo, tab.icon, selected = tab == current, enabled = !driving || tab == Tab.DRIVE) { onSelect(tab) }
        }
    }
}

@Composable
private fun BannerBar(text: String, modifier: Modifier = Modifier, onClose: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier
            .widthIn(max = 900.dp)
            .height(56.dp)
            .shadow(16.dp, shape)
            .clip(shape)
            .background(if (p.isDark) p.ghostTop else p.card)
            .border(1.dp, p.accentRing, shape)
            .clickable(role = Role.Button, onClick = onClose)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(text, color = p.text, fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        Text("닫기", color = p.accentText, fontSize = 15.sp)
    }
}
