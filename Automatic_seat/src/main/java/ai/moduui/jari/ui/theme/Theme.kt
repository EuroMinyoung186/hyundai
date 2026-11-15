package ai.moduui.jari.ui.theme

import ai.moduui.jari.R
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * 주야간 두 가지 색. 차의 주야간 모드(시스템 설정)를 따름.
 * 밤 = 휴대폰 앱과 같은 어두운 색, 낮 = 디자인 초안(moduui_ui_draft.pdf)의 일반(흰색) 모드.
 */
data class Palette(
    val bgTop: Color,
    val bgBottom: Color,
    /** 카드: 위→아래 그라데이션 (일반 모드는 흰색 단색 + 그림자) */
    val cardTop: Color,
    val cardBottom: Color,
    val card: Color,
    val cardBorder: Color,
    val cardShadow: Color,
    val text: Color,
    val sub: Color,
    val muted: Color,
    /** 버튼 그라데이션 위/아래 */
    val accent: Color,
    val accentDark: Color,
    /** 강조 글자 (휴대폰에서 보냄, 선택된 탭) */
    val accentText: Color,
    val accentRing: Color,
    val onAccent: Color,
    /** 원형 버튼 꺼짐/켜짐 (radial: 위쪽 30% 밝음) */
    val knobOffTop: Color,
    val knobOffBottom: Color,
    val knobOffRing: Color,
    val knobOnTop: Color,
    val knobOnBottom: Color,
    val knobOnRing: Color,
    val glow: Color,
    val ok: Color,
    val warn: Color,
    val danger: Color,
    val chip: Color,
    val chipBorder: Color,
    val chipText: Color,
    val railBg: Color,
    val railBorder: Color,
    val ghostTop: Color,
    val ghostBottom: Color,
    val divider: Color,
    /** 3D 유리 차체 선/면 */
    val glassLine: Color,
    val glassFill: Color,
    val isDark: Boolean,
)

/** 밤 모드: 휴대폰 앱(Ui.kt 의 C)과 같은 색. 거의 검은 바탕 + 어두운 회청색 카드 + 시안 포인트 */
val DarkPalette = Palette(
    bgTop = Color(0xFF0E1115),
    bgBottom = Color(0xFF0A0C0F),
    cardTop = Color(0xFF14181D),
    cardBottom = Color(0xFF14181D),
    card = Color(0xFF14181D),
    cardBorder = Color(0xFF262D35),
    cardShadow = Color(0x00000000),
    text = Color(0xFFF2F5F7),
    sub = Color(0xFF8B95A1),
    muted = Color(0xFF6E7884),
    accent = Color(0xFF3EC5F0),
    accentDark = Color(0xFF1A9FD6),
    accentText = Color(0xFF7FD3EE),
    accentRing = Color(0x5A3EC5F0),
    onAccent = Color(0xFF06222E),
    knobOffTop = Color(0xFF232A31),
    knobOffBottom = Color(0xFF14181D),
    knobOffRing = Color(0xFF262D35),
    knobOnTop = Color(0xFF123540),
    knobOnBottom = Color(0xFF0B1418),
    knobOnRing = Color(0xBF3EC5F0),
    glow = Color(0xFF1A9FD6),
    ok = Color(0xFF3DD68C),
    warn = Color(0xFFF5B841),
    danger = Color(0xFFFF6B6B),
    chip = Color(0xFF1C2228),
    chipBorder = Color(0xFF262D35),
    chipText = Color(0xFFE3E8EC),
    railBg = Color(0xFF14181D),
    railBorder = Color(0xFF262D35),
    ghostTop = Color(0xFF1C2228),
    ghostBottom = Color(0xFF1A1F25),
    divider = Color(0xFF262D35),
    glassLine = Color(0xD996D7F0),
    glassFill = Color(0x143EC5F0),
    isDark = true,
)

/** 낮 모드: 디자인 초안의 일반(흰색) 모드 그대로 */
val LightPalette = Palette(
    bgTop = Color(0xFFFFFFFF),
    bgBottom = Color(0xFFF3F5F7),
    cardTop = Color(0xFFFFFFFF),
    cardBottom = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0x0F000000),
    cardShadow = Color(0x24141E28),
    text = Color(0xFF15191D),
    sub = Color(0xFF5E666E),
    muted = Color(0xFF6B737B),
    accent = Color(0xFF3CC8EC),
    accentDark = Color(0xFF0093C0),
    accentText = Color(0xFF00739A),
    accentRing = Color(0x6600739A),
    onAccent = Color(0xFF04121A),
    knobOffTop = Color(0xFFFFFFFF),
    knobOffBottom = Color(0xFFE8ECEF),
    knobOffRing = Color(0x14000000),
    knobOnTop = Color(0xFFEEFAFE),
    knobOnBottom = Color(0xFFCDEFF8),
    knobOnRing = Color(0x990082AA),
    glow = Color(0xFF0082AA),
    ok = Color(0xFF047857),
    warn = Color(0xFFB45309),
    danger = Color(0xFFB91C1C),
    chip = Color(0xFFF2F4F6),
    chipBorder = Color(0x0F000000),
    chipText = Color(0xFF2E343A),
    railBg = Color(0xFFFFFFFF),
    railBorder = Color(0x0F000000),
    ghostTop = Color(0xFFFFFFFF),
    ghostBottom = Color(0xFFEEF1F4),
    divider = Color(0x0F000000),
    glassLine = Color(0xB3007BA6),
    glassFill = Color(0x1A0093C0),
    isDark = false,
)

/**
 * 주행 화면의 지도와 지도 위 칩. 디자인에서 주행 화면은 다크/일반 모드 모두 밝은 지도 + 어두운 칩.
 * 칩 · 버튼 색은 휴대폰 앱과 같은 톤 (11/14 사용자 요청 "색상 디자인은 앱처럼"): 어두운 회청색 카드 + 시안 강조.
 */
object MapColors {
    val ground = Color(0xFFECEEE8)
    val block = Color(0xFFE2E4DE)
    val park = Color(0xFFCFE5C4)
    val water = Color(0xFFB9D7EE)
    val road = Color(0xFFFFFFFF)
    val routeOuter = Color(0xFFF6D88A)
    val routeInner = Color(0xFF2BB673)
    val car = Color(0xFF2563EB)
    val overlay = Color(0xE614181D)
    val overlaySoft = Color(0xE01C2228)
    val overlayText = Color(0xFFFFFFFF)
    val overlaySub = Color(0xFFB4BDC7)
    // 불투명: 지도 없는 내비 옆 창에서 카드 그림자가 비쳐 띠처럼 보이지 않게
    val overlayCard = Color(0xFF14181D)
    val green = Color(0xFF0E7FAE)
    val mint = Color(0xFF3EC5F0)
    val mintText = Color(0xFFBFEFFC)
    val tile = Color(0xFF1C2228)
    val tileButton = Color(0xFF262D35)
    val label = Color(0xFF111827)
    val sleep = Color(0xFF6EE7B7)
    val awake = Color(0xFFFDE68A)
    val cry = Color(0xFFFCA5A5)
}

/** 영문 제목(WELCOME · BOARDING · SETTINGS)과 숫자. 한글 본문은 시스템 글꼴(Noto Sans CJK). */
val Montserrat = FontFamily(
    Font(R.font.montserrat, FontWeight.ExtraLight),
    Font(R.font.montserrat, FontWeight.Light),
    Font(R.font.montserrat, FontWeight.Normal),
    Font(R.font.montserrat, FontWeight.Medium),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** 배경을 늘 휴대폰 앱 같은 다크 모드로 (false 로 바꾸면 차의 주야간 모드를 따름) */
const val ALWAYS_DARK = true

@Composable
fun ModuuiTheme(dark: Boolean = ALWAYS_DARK || isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // 11/14 사용자 결정: 차 낮 모드여도 휴대폰 앱과 같은 밤 톤으로 고정 (ALWAYS_DARK). 낮 팔레트는 남겨 둠
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(primary = p.accent, onPrimary = p.onAccent, background = p.bgBottom, surface = p.card, onSurface = p.text, onBackground = p.text)
    } else {
        lightColorScheme(primary = p.accentDark, onPrimary = Color.White, background = p.bgTop, surface = p.card, onSurface = p.text, onBackground = p.text)
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
