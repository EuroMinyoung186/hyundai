package ai.moduui.jari.ui.components

import ai.moduui.jari.ui.theme.MapColors
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 주행 화면 지도 (디자인 C-3 · C-4 · C-5 · C-6 의 지도 그림).
 * 실제 지도는 내비 앱이 그리므로, 앱 안에서는 같은 그림을 창 크기에 맞춰 비율로 그림.
 * 위치는 원본 1200×600 기준 비율, 선 굵기는 dp 고정 (늘려도 찌그러지지 않게).
 */
@Composable
fun MainMap(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(MapColors.ground)
        drawRoundRect(MapColors.block, Offset(w * 0.075f, h * 0.10f), Size(w * 0.25f, h * 0.30f), CornerRadius(10.dp.toPx()))
        drawRoundRect(MapColors.block, Offset(w * 0.683f, h * 0.133f), Size(w * 0.233f, h * 0.333f), CornerRadius(10.dp.toPx()))
        drawOval(MapColors.park, Offset(w * 0.658f, h * 0.633f), Size(w * 0.283f, h * 0.30f))
        val road = Stroke(26.dp.toPx(), cap = StrokeCap.Round)
        drawLine(MapColors.road, Offset(0f, h * 0.55f), Offset(w, h * 0.55f), road.width, StrokeCap.Round)
        drawLine(MapColors.road, Offset(w * 0.25f, 0f), Offset(w * 0.25f, h), road.width, StrokeCap.Round)
        drawLine(MapColors.road, Offset(w * 0.79f, 0f), Offset(w * 0.79f, h * 0.55f), road.width, StrokeCap.Round)
        val route = Path().apply {
            moveTo(w * 0.5f, h)
            lineTo(w * 0.5f, h * 0.5f)
            lineTo(w * 0.533f, 0f)
        }
        drawRoute(route, 44.dp.toPx(), 22.dp.toPx())
        drawCarArrow(Offset(w * 0.5f, h * 0.79f), 1f)
    }
}

/** 오른쪽 400 폭 작은 지도. markers = 경로 위 휴게소 위치(원본 400×600 기준 좌표) */
@Composable
fun MiniMap(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val sx = size.width / 400f
        val sy = size.height / 600f
        drawRect(MapColors.ground)
        val water = Path().apply {
            moveTo(0f, 520f * sy)
            cubicTo(80f * sx, 480f * sy, 130f * sx, 560f * sy, 200f * sx, 520f * sy)
            cubicTo(270f * sx, 480f * sy, 320f * sx, 450f * sy, 400f * sx, 490f * sy)
            lineTo(400f * sx, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(water, MapColors.water)
        drawOval(MapColors.park, Offset(230f * sx, 150f * sy), Size(140f * sx, 100f * sy))
        val road = 6.dp.toPx()
        drawLine(MapColors.road, Offset(0f, 300f * sy), Offset(size.width, 300f * sy), road)
        drawLine(MapColors.road, Offset(130f * sx, 0f), Offset(130f * sx, size.height), road)
        val route = Path().apply {
            moveTo(90f * sx, size.height)
            lineTo(180f * sx, 420f * sy)
            lineTo(250f * sx, 290f * sy)
            lineTo(370f * sx, 40f * sy)
        }
        drawRoute(route, 12.dp.toPx(), 7.dp.toPx())
        drawCircle(Color.White, 14.dp.toPx(), Offset(180f * sx, 420f * sy))
        drawCircle(MapColors.car, 11.dp.toPx(), Offset(180f * sx, 420f * sy))
        drawCircle(Color.White, 11.dp.toPx(), Offset(370f * sx, 40f * sy))
        drawCircle(MapColors.label, 9.dp.toPx(), Offset(370f * sx, 40f * sy))
    }
}

private fun DrawScope.drawRoute(route: Path, outer: Float, inner: Float) {
    drawPath(route, MapColors.routeOuter, style = Stroke(outer, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(route, MapColors.routeInner, style = Stroke(inner, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** 원본: 지름 68 원 + 24 단위 화살표 "M12 2 20 21 12 16 4 21z" 40 크기 */
private fun DrawScope.drawCarArrow(center: Offset, k: Float) {
    drawCircle(MapColors.car.copy(alpha = 0.18f), 34.dp.toPx() * k, center)
    val s = 40.dp.toPx() * k / 24f
    val path = Path().apply {
        fun pt(x: Float, y: Float) = Offset(center.x + (x - 12f) * s, center.y + (y - 12f) * s)
        val a = pt(12f, 2f); moveTo(a.x, a.y)
        val b = pt(20f, 21f); lineTo(b.x, b.y)
        val c = pt(12f, 16f); lineTo(c.x, c.y)
        val d = pt(4f, 21f); lineTo(d.x, d.y)
        close()
    }
    drawPath(path, MapColors.car)
    drawPath(path, Color.White, style = Stroke(1.5f * s, join = StrokeJoin.Round))
}
