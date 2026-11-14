package ai.moduui.jari.ui.components

import ai.moduui.core.model.GeoPoint
import ai.moduui.jari.ui.theme.MapColors
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import kotlin.math.ceil
import org.osmdroid.util.GeoPoint as OsmPoint

/** 지도 위 번호 표시 (디자인 C-4: 수유실 휴게소 후보). primary = 첫 번째 추천 */
data class MapStop(val number: Int, val label: String, val point: GeoPoint, val primary: Boolean)

/**
 * 앱 안 지도 (OpenStreetMap 타일, osmdroid).
 * Pleos 에뮬레이터 내비는 빈 지도 스타일(connect_empty)을 쓰므로, 주행 화면 지도를 앱이 직접 그림.
 * 현재 위치 · 들를 휴게소 · 목적지를 잇는 선을 그리고, 모두 보이게 맞춤.
 * NaviHelper 는 경로 모양(좌표 목록)을 주지 않으므로 선은 지점끼리 잇는 개략선임.
 * 운전 중 조작을 줄이려고 손으로 움직이지 않게 막음.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun RouteMap(
    here: GeoPoint?,
    destination: GeoPoint?,
    modifier: Modifier = Modifier,
    destinationName: String? = null,
    via: List<GeoPoint> = emptyList(),
    stops: List<MapStop> = emptyList(),
) {
    val context = LocalContext.current
    val map = remember {
        initOsm(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            isTilesScaledToDpi = true
            setMultiTouchControls(false)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 5.0
            maxZoomLevel = 17.0
            setOnTouchListener { _, _ -> true }
            overlays.add(CopyrightOverlay(context))
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, map) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) map.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            map.onDetach()
        }
    }
    val content = MapContent(here, destination, destinationName, via, stops)
    AndroidView(factory = { map }, modifier = modifier, update = { v ->
        // 화면은 1초마다 다시 그려지므로, 내용이 바뀔 때만 표시를 새로 놓음
        if (v.tag != content) {
            v.tag = content
            draw(v, content)
        }
    })
}

private data class MapContent(
    val here: GeoPoint?,
    val destination: GeoPoint?,
    val destinationName: String?,
    val via: List<GeoPoint>,
    val stops: List<MapStop>,
)

/** 위치를 아직 못 받았을 때 보여 줄 곳 (서울시청) */
private val FALLBACK = OsmPoint(37.5665, 126.9780)

private fun draw(map: MapView, c: MapContent) {
    val ctx = map.context
    map.overlays.removeAll { it !is CopyrightOverlay }

    val line = listOfNotNull(c.here) + c.via + listOfNotNull(c.destination)
    if (c.destination != null && line.size >= 2) {
        val d = ctx.resources.displayMetrics.density
        listOf(MapColors.routeOuter to 12f, MapColors.routeInner to 6f).forEach { (color, width) ->
            map.overlays.add(
                Polyline(map).apply {
                    setPoints(line.map { it.osm() })
                    outlinePaint.color = color.toArgb()
                    outlinePaint.strokeWidth = width * d
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    infoWindow = null
                },
            )
        }
    }
    c.stops.forEach { s ->
        map.overlays.add(
            marker(map, s.point, pill(ctx, s.label, if (s.primary) MapColors.green else MapColors.overlayCard, Color.White, number = s.number, ring = if (s.primary) MapColors.mint.copy(alpha = 0.7f) else null), 0.5f, 0.5f),
        )
    }
    c.destination?.let { dest ->
        map.overlays.add(marker(map, dest, dot(ctx, MapColors.label), 0.5f, 0.5f))
        map.overlays.add(marker(map, dest, pill(ctx, "🏁 ${c.destinationName ?: "목적지"}", Color.White, MapColors.label), 0.5f, 1.35f))
    }
    c.here?.let { map.overlays.add(marker(map, it, car(ctx), 0.5f, 0.5f)) }
    map.invalidate()

    val pts = line + c.stops.map { it.point }
    fit(map, pts)
}

/** 모든 지점이 보이게. 지점이 하나뿐이면 그곳을 가운데 두고 확대 */
private fun fit(map: MapView, pts: List<GeoPoint>) {
    val apply = {
        when {
            pts.isEmpty() -> {
                map.controller.setZoom(11.0)
                map.controller.setCenter(FALLBACK)
            }
            pts.size == 1 || spanTooSmall(pts) -> {
                map.controller.setZoom(15.0)
                map.controller.setCenter(pts.first().osm())
            }
            else -> {
                // 이름표가 점 위로 올라가므로 가장자리 여백을 넉넉히
                val pad = (72 * map.context.resources.displayMetrics.density).toInt()
                map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts.map { it.osm() }), false, pad)
            }
        }
    }
    if (map.width > 0 && map.height > 0) apply() else map.addOnFirstLayoutListener { _, _, _, _, _ -> apply() }
}

private fun spanTooSmall(pts: List<GeoPoint>): Boolean {
    val lat = pts.maxOf { it.lat } - pts.minOf { it.lat }
    val lng = pts.maxOf { it.lng } - pts.minOf { it.lng }
    return lat < 0.002 && lng < 0.002
}

private fun GeoPoint.osm() = OsmPoint(lat, lng)

private fun marker(map: MapView, at: GeoPoint, bmp: Bitmap, ax: Float, ay: Float) = Marker(map).apply {
    position = at.osm()
    icon = BitmapDrawable(map.context.resources, bmp)
    setAnchor(ax, ay)
    infoWindow = null
    setOnMarkerClickListener { _, _ -> true }
}

private fun initOsm(ctx: Context) {
    val c = Configuration.getInstance()
    if (c.userAgentValue == ctx.packageName) return
    // 타일 캐시는 앱 전용 캐시 폴더에 (외부 저장소 권한 없이)
    val base = File(ctx.cacheDir, "osmdroid")
    c.osmdroidBasePath = base
    c.osmdroidTileCache = File(base, "tiles")
    // OSM 타일 이용 정책: 앱을 알아볼 수 있는 User-Agent 필요
    c.userAgentValue = ctx.packageName
}

// ───────────────────────────── 표시 그림 ─────────────────────────────

private fun aa(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

/** 차 위치: 파란 원 + 흰 테두리 + 옅은 원 */
private fun car(ctx: Context): Bitmap {
    val d = ctx.resources.displayMetrics.density
    val size = (44 * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = size / 2f
    c.drawCircle(r, r, r, aa(MapColors.car.copy(alpha = 0.22f).toArgb()))
    c.drawCircle(r, r, 12 * d, aa(android.graphics.Color.WHITE))
    c.drawCircle(r, r, 9 * d, aa(MapColors.car.toArgb()))
    return bmp
}

private fun dot(ctx: Context, color: Color): Bitmap {
    val d = ctx.resources.displayMetrics.density
    val size = (22 * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = size / 2f
    c.drawCircle(r, r, r, aa(android.graphics.Color.WHITE))
    c.drawCircle(r, r, r - 3 * d, aa(color.toArgb()))
    return bmp
}

/** 둥근 이름표. number 가 있으면 왼쪽에 흰 원 번호 (디자인 C-4 의 휴게소 표시와 같은 모양) */
private fun pill(ctx: Context, text: String, bg: Color, fg: Color, number: Int? = null, ring: Color? = null): Bitmap {
    val d = ctx.resources.displayMetrics.density
    val tp = aa(fg.toArgb()).apply { textSize = 15 * d; typeface = Typeface.DEFAULT_BOLD }
    val h = 40 * d
    val ringW = if (ring != null) 4 * d else 0f
    val circle = if (number != null) 30 * d else 0f
    val gap = if (number != null) 6 * d else 0f
    val left = if (number != null) 5 * d else 12 * d
    val w = ringW * 2 + left + circle + gap + tp.measureText(text) + 12 * d
    val bmp = Bitmap.createBitmap(ceil(w).toInt(), ceil(h + ringW * 2).toInt(), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    if (ring != null) c.drawRoundRect(RectF(0f, 0f, w, h + ringW * 2), h / 2 + ringW, h / 2 + ringW, aa(ring.toArgb()))
    c.drawRoundRect(RectF(ringW, ringW, w - ringW, h + ringW), h / 2, h / 2, aa(bg.toArgb()))
    val cy = ringW + h / 2
    var x = ringW + left
    if (number != null) {
        c.drawCircle(x + circle / 2, cy, circle / 2, aa(android.graphics.Color.WHITE))
        val np = aa(bg.toArgb()).apply { textSize = 15 * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        c.drawText("$number", x + circle / 2, cy - (np.descent() + np.ascent()) / 2, np)
        x += circle + gap
    }
    c.drawText(text, x, cy - (tp.descent() + tp.ascent()) / 2, tp)
    return bmp
}
