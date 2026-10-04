package com.walkingtours.app.ui.components

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.util.Log
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView as GoogleMapView
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker as GoogleMarker
import com.google.maps.android.compose.Polyline as GooglePolyline
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.walkingtours.app.BuildConfig
import com.walkingtours.app.WalkingToursApp
import com.walkingtours.app.data.db.StopEntity
import com.walkingtours.app.maps.DirectionsClient
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.tan

/**
 * Testing switch: set to true to force the OpenStreetMap fallback even when a Google Maps key is
 * present and the modern renderer is available, so both engines can be compared on one device.
 * false is the shipping setting: a key plus the modern renderer means Google Maps.
 *
 * A plain val rather than a const so it does not trip constant-condition analysis.
 */
private val FORCE_FALLBACK_MAP = false

/** Zoom used when the route is a single point. */
private const val SINGLE_STOP_ZOOM = 17.0

/** Air left between the outermost pin and the edge of the framed box. */
private const val MARKER_GAP_DP = 6

/**
 * The strip along the top of a stop-page hero that the app's own Photo/Map chips occupy — their
 * 10 dp inset, a chip, and a little air. The fit must not put anything under it.
 *
 * Only a stop page has chips over its map; the tour overview draws a bare map. The map cannot see
 * them for itself, so they are inferred from a focus request, which is exactly what only a stop page
 * makes.
 */
private const val HERO_CHIPS_STRIP_DP = 42

/**
 * The strip along the bottom that the engine's own logo and attribution occupy: Google's logo with
 * its "Map data" line, osmdroid's OpenStreetMap copyright. Both engines draw it over the map, so the
 * fit has to leave it clear too.
 */
private const val ATTRIBUTION_STRIP_DP = 32

/**
 * Only used if the pin drawable ever reports no intrinsic size; it mirrors the numbered pin bitmap
 * in [numberedMarkerIcon], which [rememberFitMargins] otherwise measures for itself.
 */
private const val PIN_FALLBACK_PX = 110

/**
 * Tiles are 256 px square.
 *
 * The base map is the standard OpenStreetMap style. A cleaner, point-of-interest-free style (Esri's
 * Light Gray Canvas, then CARTO Positron) was tried and reverted: CARTO now watermarks every tile
 * with "API KEY REQUIRED", and Esri's style stops at zoom 16, so the two-stop camera fit — which
 * deliberately zooms as far in as both stops allow — ran straight past the last available tile and
 * showed "Map data not yet available" placeholders at the exact zoom the walker most wants to read.
 * OSM renders at every zoom, so it wins despite carrying hotel and restaurant icons.
 *
 * The Google Maps path, when a key is present, is where a clean basemap comes from instead.
 */
private const val TILE_SIZE_PX = 256.0

/**
 * In-memory tile cache size. The default is small, which makes pinch-zoom stutter because tiles
 * dropped from the cache have to be re-fetched while the gesture is still moving.
 */
private const val TILE_CACHE_SIZE = 400

/** Below this accuracy the circle is too small to be meaningful, so it is not drawn at all. */
private const val MIN_ACCURACY_TO_DRAW = 12f

/** How long the dot takes to travel to a new fix. Long enough to hide GPS noise, short enough to feel live. */
private const val POSITION_GLIDE_MS = 900

/** How long the heading cone takes to swing round. Slower than position: compasses are noisier. */
private const val HEADING_GLIDE_MS = 700

/** A circle wider than this swamps the map when the fix is poor, so it is capped. */
private const val MAX_ACCURACY_RADIUS_METERS = 250f

private const val ACCURACY_FILL = 0x1A1A73E8
private const val ACCURACY_STROKE = 0x331A73E8

/** The route blue, shared by both map engines so the line does not change colour with the engine. */
/**
 * The route line.
 *
 * A mid-blue was almost invisible: the fallback map is pale grey and the Google map goes dark with
 * the system theme, so a muted blue sat at nearly the same value as the background in both. A
 * saturated red is distinct from all three marker colours (blue, teal and the amber "you are here"
 * stop) and reads on light and dark tiles alike.
 */
private val ROUTE_COLOR = Color(0xFFE53935)

/**
 * Drawn under the route, wider, so the line has a light edge wherever it crosses dark tiles — the
 * usual casing trick, and the reason routes stay legible over busy map detail.
 */
private val ROUTE_CASING_COLOR = Color(0xFFFFFFFF)

private const val ROUTE_WIDTH = 10f

private const val ROUTE_CASING_WIDTH = 18f

/**
 * Hides Google's own points of interest.
 *
 * This is the Google equivalent of choosing Esri's Light Gray Canvas for the osmdroid map: the
 * default style bakes hotel, restaurant and shop pins into the map, and on a walking tour they
 * compete with the numbered stops, which are the only markers that matter. Streets, water and place
 * names are untouched.
 */
private const val HIDE_POIS_STYLE = """[{"featureType":"poi","stylers":[{"visibility":"off"}]}]"""

/**
 * Holds the osmdroid [MapView] and keeps the gesture stream to itself.
 *
 * These maps live inside a scrolling Compose `LazyColumn`. Without this wrapper the parent
 * scrollable competes for the same drag: the map starts panning, the list decides the gesture is
 * a scroll, and the map snatches back — which is exactly the snagging, inconsistent feel the map
 * had. Asking the parent not to intercept for the duration of the gesture leaves panning and
 * pinching entirely to osmdroid, so the map behaves like any other map.
 */
private class MapTouchGuard(context: Context) : FrameLayout(context) {

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        return super.onInterceptTouchEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                parent?.requestDisallowInterceptTouchEvent(true)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)

            else -> Unit
        }
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * The Google Maps twin of [MapTouchGuard]: the same "this gesture is mine" request, made from
 * inside the map, because maps-compose has already wrapped the [GoogleMapView] and gives no way to
 * put an Android container around it.
 *
 * Compose renders an `AndroidView` through a `PointerInteropFilter`, which watches for any Compose
 * pointer-input node that consumes a move inside the same gesture. When it sees one it concludes
 * that Compose has claimed the drag, sends the Android view an ACTION_CANCEL, and stops delivering
 * events until every finger has lifted — so one consumed move kills the rest of the pan, and the
 * second finger of a pinch never arrives at all. The box in `StopHero` consumes single-pointer
 * moves deliberately, to stop the pager and the list behind it stealing a pan, and that is exactly
 * the signal that was cancelling every Google Maps gesture a few pixels in.
 *
 * osmdroid never hit this: [MapTouchGuard] asks its parent not to intercept, which sets the interop
 * filter's disallow-intercept flag, and a filter in that state hands the whole stream to the view
 * instead of cancelling it. Asking for the same thing from inside the map makes Google Maps behave
 * like the fallback: the gesture is not cancelled, moves keep arriving, and a second finger still
 * reaches the map.
 */
private class GestureClaimingMapView(context: Context, options: GoogleMapOptions) :
    GoogleMapView(context, options) {

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                parent?.requestDisallowInterceptTouchEvent(true)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)

            else -> Unit
        }
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * The itinerary map.
 *
 * Which engine draws it is decided at build time, not at runtime. With no Google Maps key compiled
 * into the app — the normal case — this is OpenStreetMap via osmdroid: no key, no billing account
 * and no Play Services, which keeps the app free to run and usable on any Android device, and tiles
 * osmdroid has cached on disk keep a city working without a connection. When
 * `BuildConfig.GOOGLE_MAPS_API_KEY` is non-blank the same map is drawn by Google Maps instead, with
 * the real walking line through the stops when Directions allows it.
 *
 * Both paths draw the same numbered pins, the same blue dot with its heading cone, and frame the
 * same stops, so nothing above this composable has to know which engine is in use.
 *
 * @param stops the full ordered route, used for both the polyline and the numbered pins.
 * @param visitedIds stops already reached, drawn with a tick.
 * @param userLat/userLng the walker's current fix, shown as a blue dot when present.
 * @param selectedStopId pin to highlight, so tapping a stop in the list moves the map.
 */
@Composable
fun TourMap(
    stops: List<StopEntity>,
    modifier: Modifier = Modifier,
    visitedIds: Set<String> = emptySet(),
    userLat: Double? = null,
    userLng: Double? = null,
    /**
     * When set, the camera frames these stops instead of the whole route. A stop page uses it to
     * show the stop you are looking at together with the one you walk to next, which is far easier
     * to navigate by than the whole fourteen-stop route.
     */
    focusStops: List<StopEntity>? = null,

    /** Degrees clockwise from north, from the compass. Draws the heading cone when present. */
    userHeading: Float? = null,
    /** GPS accuracy in metres, drawn as the translucent circle around the dot. */
    userAccuracyMeters: Float? = null,
    selectedStopId: String? = null,
    onStopClick: (StopEntity) -> Unit = {},
) {
    // Google Maps needs its key in the manifest at build time, and the README's promise is that the
    // app is fully usable without one. A blank key therefore means "draw the osmdroid map", never
    // "draw a grey square": there is no half-configured Google map.
    //
    // Logged, because the fallback is silent by design and "why is Google Maps not showing?" is
    // otherwise indistinguishable from a broken map.
    if (!FORCE_FALLBACK_MAP &&
        BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank() &&
        WalkingToursApp.modernMapsRendererAvailable
    ) {
        Log.i(TAG, "Map: Google Maps (key present, modern renderer loaded)")
        GoogleTourMap(
            stops = stops,
            modifier = modifier,
            visitedIds = visitedIds,
            userLat = userLat,
            userLng = userLng,
            focusStops = focusStops,
            userHeading = userHeading,
            userAccuracyMeters = userAccuracyMeters,
            selectedStopId = selectedStopId,
            onStopClick = onStopClick,
        )
        return
    }
    Log.i(
        TAG,
        when {
            FORCE_FALLBACK_MAP ->
                "Map: OpenStreetMap fallback - forced by FORCE_FALLBACK_MAP for gesture testing"

            BuildConfig.GOOGLE_MAPS_API_KEY.isBlank() ->
                "Map: OpenStreetMap fallback - no google.maps.apiKey in local.properties"

            else ->
                "Map: OpenStreetMap fallback - Play Services offers only the legacy " +
                    "Maps renderer, which cannot run at targetSdk 37"
        },
    )

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapView(context).apply {
            // A larger tile cache is the cheapest way to make pinch-zoom smooth: zooming in and back
            // out reuses tiles instead of re-fetching them mid-gesture. MapTileProviderBase exposes
            // no cache setter, so grow the one it creates.
            runCatching {
                val provider = MapTileProviderBasic(context)
                provider.tileCache.ensureCapacity(TILE_CACHE_SIZE)
                setTileProvider(provider)
            }
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // osmdroid's own zoom buttons clutter a Compose layout; pinch and double tap remain.
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(16.0)
        }
    }

    /** Actual laid-out size of the map. Used to gate the one-time zoom-to-fit. */
    var mapSize by remember { mutableStateOf(IntSize.Zero) }

    /** Route signature already framed, so the camera is only fitted once per route. */
    var fittedRouteKey by remember { mutableStateOf<String?>(null) }

    /** Accuracy circle overlay, kept so it can be replaced rather than accumulating. */
    var accuracyOverlay by remember { mutableStateOf<Polygon?>(null) }

    // Marker icons are pure functions of (order, visited, selected), so build each one once.
    val iconCache = remember { mutableMapOf<String, android.graphics.drawable.Drawable>() }
    // Two variants. A cone always points "up" in the bitmap, so with no heading to rotate it by it
    // would sit there claiming the walker faces north. With no heading, draw the dot alone.
    val userDotWithCone = remember { userLocationDot(context, withCone = true) }
    val userDotPlain = remember { userLocationDot(context, withCone = false) }
    val userDot = if (userHeading != null) userDotWithCone else userDotPlain

    // The two overlay layers are tracked separately so a GPS fix only moves one marker instead of
    // tearing down and rebuilding the whole map. Rebuilding everything once a second was a visible
    // hitch, and it fought any gesture in progress.
    val routeOverlays = remember { mutableListOf<Overlay>() }
    var userMarker by remember { mutableStateOf<Marker?>(null) }

    // osmdroid pauses tile loading when the map is not visible; hook it into the Compose lifecycle
    // so it stops fetching tiles in the background and resumes correctly on return.
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    // Route layer: polyline plus the numbered pins. Rebuilt only when the route or progress changes.
    DisposableEffect(stops, visitedIds, selectedStopId) {
        mapView.overlays.removeAll(routeOverlays)
        routeOverlays.clear()

        if (stops.isNotEmpty()) {
            val casing = Polyline(mapView).apply {
                setPoints(stops.map { GeoPoint(it.lat, it.lng) })
                outlinePaint.color = ROUTE_CASING_COLOR.toArgb()
                outlinePaint.strokeWidth = ROUTE_CASING_WIDTH
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
            }
            routeOverlays += casing
            mapView.overlays.add(casing)

            val polyline = Polyline(mapView).apply {
                setPoints(stops.map { GeoPoint(it.lat, it.lng) })
                outlinePaint.color = ROUTE_COLOR.toArgb()
                outlinePaint.strokeWidth = ROUTE_WIDTH
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
            }
            routeOverlays += polyline
            mapView.overlays.add(polyline)

            stops.forEach { stop ->
                val visited = stop.id in visitedIds
                val selected = stop.id == selectedStopId
                val fill = when {
                    selected -> AndroidColor.parseColor("#FFC8892F")
                    visited -> AndroidColor.parseColor("#FF0F6E6E")
                    else -> AndroidColor.parseColor("#FF1B5E8C")
                }
                val marker = Marker(mapView).apply {
                    position = GeoPoint(stop.lat, stop.lng)
                    title = "${stop.order}. ${stop.name}"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = iconCache.getOrPut("${stop.order}-$visited-$selected") {
                        numberedMarkerIcon(context, stop.order, fill, visited)
                    }
                    setOnMarkerClickListener { _, _ ->
                        onStopClick(stop)
                        true
                    }
                }
                routeOverlays += marker
                mapView.overlays.add(marker)
            }
        }

        // This effect re-runs whenever a stop is reached or the selection changes, and it re-adds
        // the stop markers. osmdroid draws overlays in list order, so without moving the walker's
        // dot back to the end it ends up buried under the numbered circles — which is exactly what
        // happened: the dot was on the map, just invisible.
        userMarker?.let { marker ->
            mapView.overlays.remove(marker)
            mapView.overlays.add(marker)
        }

        mapView.invalidate()
        onDispose { }
    }

    // Walker position, drawn the way Google Maps draws it: a long, soft heading cone, a solid blue
    // dot on a white ring, and a translucent accuracy circle.
    //
    // The marker is created once and then *moved*, rather than being torn down and rebuilt on every
    // fix. Rebuilding it made the dot jump from one position to the next; animating the position and
    // the rotation makes it glide, which is what makes a map feel smooth even though the underlying
    // fixes are noisy and only arrive about once a second.
    val hasFix = userLat != null && userLng != null
    val animatedLat = remember { Animatable(0f) }
    val animatedLng = remember { Animatable(0f) }
    val animatedHeading = remember { Animatable(0f) }

    DisposableEffect(hasFix, userDot) {
        if (hasFix) {
            if (userMarker == null) {
                val marker = Marker(mapView).apply {
                    position = GeoPoint(userLat, userLng)
                    title = "You are here"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = userDot
                    // Tapping your own dot should not open a bubble over the map.
                    setOnMarkerClickListener { _, _ -> false }
                }
                mapView.overlays.add(marker)
                userMarker = marker
            }
        } else {
            userMarker?.let { mapView.overlays.remove(it) }
            accuracyOverlay?.let { mapView.overlays.remove(it) }
            userMarker = null
            accuracyOverlay = null
        }
        mapView.invalidate()
        onDispose { }
    }

    // The accuracy circle is rebuilt per fix rather than per frame: it is a hundred-point polygon and
    // redrawing it sixty times a second would cost far more than it is worth.
    DisposableEffect(hasFix, userLat, userLng, userAccuracyMeters) {
        accuracyOverlay?.let { mapView.overlays.remove(it) }
        accuracyOverlay = null
        val accuracy = userAccuracyMeters
        if (hasFix && accuracy != null && accuracy > MIN_ACCURACY_TO_DRAW) {
            val circle = Polygon(mapView).apply {
                points = Polygon.pointsAsCircle(
                    GeoPoint(userLat, userLng),
                    accuracy.coerceAtMost(MAX_ACCURACY_RADIUS_METERS).toDouble(),
                )
                // Use the paint directly: the fillColor setter is deprecated in osmdroid 6.1.x.
                fillPaint.color = ACCURACY_FILL
                outlinePaint.color = ACCURACY_STROKE
                outlinePaint.strokeWidth = 3f
            }
            // Under the dot, so the dot stays readable on top of it.
            mapView.overlays.add(0, circle)
            accuracyOverlay = circle
        }
        mapView.invalidate()
        onDispose { }
    }

    LaunchedEffect(userLat, userLng) {
        val lat = userLat ?: return@LaunchedEffect
        val lng = userLng ?: return@LaunchedEffect
        if (userMarker == null) return@LaunchedEffect
        if (animatedLat.value == 0f && animatedLng.value == 0f) {
            // First fix: appear where the walker actually is instead of sliding in from the origin.
            animatedLat.snapTo(lat.toFloat())
            animatedLng.snapTo(lng.toFloat())
        } else {
            // Both axes together, so the dot travels in a straight line rather than an L.
            launch { animatedLat.animateTo(lat.toFloat(), tween(POSITION_GLIDE_MS)) }
            animatedLng.animateTo(lng.toFloat(), tween(POSITION_GLIDE_MS))
        }
    }

    LaunchedEffect(userHeading) {
        val target = userHeading ?: return@LaunchedEffect
        if (userMarker == null) return@LaunchedEffect
        // Rotate the short way round: 350 to 10 degrees should move 20 degrees forward, not 340 back.
        val current = animatedHeading.value
        var delta = (target - current) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        animatedHeading.animateTo(current + delta, tween(HEADING_GLIDE_MS))
    }

    // One collector drives the marker from both animations, so a fix and a compass sample landing in
    // the same frame cannot fight over the marker.
    LaunchedEffect(userMarker) {
        if (userMarker == null) return@LaunchedEffect
        snapshotFlow {
            Triple(animatedLat.value, animatedLng.value, animatedHeading.value)
        }.collect { (lat, lng, heading) ->
            val marker = userMarker ?: return@collect
            marker.position = GeoPoint(lat.toDouble(), lng.toDouble())
            marker.rotation = heading
            mapView.invalidate()
        }
    }

    // Frame the route exactly once per route, and only after the map has a real size.
    //
    // NOTE: osmdroid's MapView.zoomToBoundingBox is deliberately NOT used here. In 6.1.20 it routes
    // through Projection.getCloserPixel, a binary search that only terminates when the projected
    // pixel lands exactly on the bisection point. With a real multi-stop route it can fail to
    // converge and spin the main thread until Android raises an ANR — confirmed from device ANR
    // traces. The camera is fitted with a few lines of Web Mercator arithmetic instead, which
    // cannot loop.
    val fitTargets = focusStops?.takeIf { it.isNotEmpty() } ?: stops
    // The fit key includes the measured viewport, not just the route. The first size the map reports
    // can be smaller than the size it finally lays out at, and a guard keyed on the route alone would
    // lock in a zoom computed for that too-small viewport — which framed the whole route as a clump
    // of markers in the middle of a far wider map.
    // What has to stay clear around the framed box, measured per axis. The same value drives both the
    // fit and the camera, so a margin can no longer be a magic number that silently costs a zoom
    // level on one axis while the other axis never needed it.
    val margins = rememberFitMargins(focusStops)
    val routeKey = remember(fitTargets, mapSize, margins) {
        fitTargets.joinToString("|") { "${it.lat},${it.lng}" } +
            "@${mapSize.width}x${mapSize.height}+${margins.horizontalPx}x${margins.verticalPx}"
    }
    LaunchedEffect(routeKey, mapSize) {
        if (fitTargets.isEmpty()) return@LaunchedEffect
        if (mapSize.width <= 0 || mapSize.height <= 0) return@LaunchedEffect
        if (fittedRouteKey == routeKey) return@LaunchedEffect
        fittedRouteKey = routeKey

        runCatching {
            // osmdroid measures its zoom against the map view in *pixels*, so this engine fits in
            // pixels and only the Google path converts to dp. See [fitZoomFor].
            val fit = cameraFitFor(
                targets = fitTargets,
                width = mapSize.width.toFloat(),
                height = mapSize.height.toFloat(),
                horizontalMargin = margins.horizontalPx.toFloat(),
                verticalMargin = margins.verticalPx.toFloat(),
            )
            Log.i(
                TAG,
                "OSM fit: view=${mapSize.width}x${mapSize.height} " +
                    "padX=${margins.horizontalPx} padY=${margins.verticalPx} " +
                    "targets=${fitTargets.size} -> ${fit.lat},${fit.lng} z=${fit.zoom}",
            )
            mapView.controller.setZoom(fit.zoom)
            mapView.controller.setCenter(GeoPoint(fit.lat, fit.lng))
            Log.i(
                TAG,
                "OSM camera now: z=${mapView.zoomLevelDouble} c=${mapView.mapCenter}",
            )
        }
    }

    AndroidView(
        factory = { ctx ->
            MapTouchGuard(ctx).apply {
                // Defensive: a recycled AndroidView can hand back a MapView that is still attached.
                (mapView.parent as? ViewGroup)?.removeView(mapView)
                addView(
                    mapView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { mapSize = it },
    )
}

/**
 * The same itinerary map, drawn by Google Maps, used only when a Maps key was compiled in.
 *
 * The look is deliberately identical to the osmdroid path: the same numbered pins from the shared
 * icon helper, the same blue dot with its heading cone, and the same one-time fit of a stop page's
 * two stops rather than the whole route. The difference is the line: when the key can use the
 * Directions API it is the walk a person would actually take, and until that arrives — or forever,
 * if it never does — it is straight hops between consecutive stops, which is the same line the
 * osmdroid map draws.
 */
@Composable
@OptIn(com.google.maps.android.compose.MapsComposeExperimentalApi::class)
private fun GoogleTourMap(
    stops: List<StopEntity>,
    modifier: Modifier,
    visitedIds: Set<String>,
    userLat: Double?,
    userLng: Double?,
    focusStops: List<StopEntity>?,
    userHeading: Float?,
    userAccuracyMeters: Float?,
    selectedStopId: String?,
    onStopClick: (StopEntity) -> Unit,
) {
    val context = LocalContext.current
    val cameraPositionState = rememberCameraPositionState()
    // The device density, used only to hand Google a viewport in the density-independent units its
    // zoom is defined against. See the fit below.
    val density = LocalDensity.current

    // Straight hops, shown until the real route arrives and left in place if it never does. A
    // straight line between stops is a poor route but a much better map than no line at all.
    val directPoints = remember(stops) { stops.map { LatLng(it.lat, it.lng) } }

    /** The real walking line, empty until Directions answers. */
    var routePoints by remember(stops) { mutableStateOf(emptyList<LatLng>()) }
    LaunchedEffect(stops) {
        val tourId = stops.firstOrNull()?.tourId.orEmpty()
        if (tourId.isBlank() || stops.size < 2) return@LaunchedEffect
        routePoints = DirectionsClient.walkingRoute(context, tourId, stops)
    }
    val linePoints = if (routePoints.size >= 2) routePoints else directPoints

    /** Actual laid-out size of the map. Used to gate the one-time camera fit. */
    var mapSize by remember { mutableStateOf(IntSize.Zero) }

    /** Route signature already framed, so the camera is only fitted once per route. */
    var fittedRouteKey by remember { mutableStateOf<String?>(null) }

    // Same fit targets as the osmdroid map: a stop page asks for the stop you are on and the one you
    // walk to next; everywhere else the whole route is framed.
    val fitTargets = focusStops?.takeIf { it.isNotEmpty() } ?: stops
    // The same clearances as the osmdroid map, translated into the units Google's camera is defined
    // in rather than shared with it. See [fitZoomFor] for why that translation has to happen at all.
    val margins = rememberFitMargins(focusStops)
    val densityScale = density.density
    val horizontalMarginDp = margins.horizontalPx / densityScale
    val verticalMarginDp = margins.verticalPx / densityScale
    // The fit key includes the measured viewport, not just the route. The first size the map reports
    // can be smaller than the size it finally lays out at, and a guard keyed on the route alone would
    // lock in a zoom computed for that too-small viewport — which framed the whole route as a clump
    // of markers in the middle of a far wider map. The margins belong in the key for the same reason:
    // a stop page and an overview frame the same route differently.
    val routeKey = remember(fitTargets, mapSize, margins) {
        fitTargets.joinToString("|") { "${it.lat},${it.lng}" } +
            "@${mapSize.width}x${mapSize.height}+${margins.horizontalPx}x${margins.verticalPx}"
    }
    // The strips Google itself has to keep its logo and attribution out of. maps-compose takes these
    // in dp and converts them with Density.roundToPx before calling GoogleMap.setPadding, which is
    // Google's own way of being told that part of its map is obscured. osmdroid has no equivalent —
    // its copyright overlay stays pinned to the corner — so there the whole clearance lives in the
    // fit margins instead. These are the *real* strips, the chips above and the logo below, not the
    // symmetric fit margin: that one also carries a pin's own height, and pushing Google's logo that
    // far into the map would be needless.
    val contentPadding = PaddingValues(
        top = (margins.topStripPx / densityScale).dp,
        bottom = (margins.bottomStripPx / densityScale).dp,
    )

    // Applied through MapEffect rather than by assigning cameraPositionState.position from a
    // LaunchedEffect. MapEffect only runs once the map object actually exists, so the camera is
    // guaranteed to be there to move; assigning the state earlier could be dropped, which left the
    // camera at Google's default and made the framing look like it was ignoring the requested
    // current + next stops.
    // Reuses the osmdroid path's bitmap: the cone is only drawn when there is a compass bearing to
    // rotate it by, otherwise it would sit there claiming the walker faces north. These are plain
    // drawables and are safe to build here; they only become Google icons inside the map below.
    val coneDot = remember { userLocationDot(context, withCone = true) }
    val plainDot = remember { userLocationDot(context, withCone = false) }

    val walker = rememberWalkerDot(userLat, userLng, userHeading)

    GoogleMap(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { mapSize = it },
        cameraPositionState = cameraPositionState,
        properties = MapProperties(mapStyleOptions = MapStyleOptions(HIDE_POIS_STYLE)),
        // The inset the fit is computed against, handed to Google so its own camera padding agrees
        // with ours. It keeps the pins clear of the "Google" logo at the bottom left and of the
        // Photo/Map chips the stop hero draws over the top right, and it is Google-specific because
        // only Google's camera has a padding of its own to keep in step. maps-compose converts this
        // to pixels with Density.roundToPx before calling GoogleMap.setPadding, so it is given in dp.
        contentPadding = contentPadding,
        // osmdroid's zoom buttons are hidden and the app draws its own position dot, so Google's
        // equivalents — including the "open in Google Maps" toolbar — would be new clutter. Every
        // gesture is left at its default of enabled.
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false,
            myLocationButtonEnabled = false,
            mapToolbarEnabled = false,
        ),
        // The map claims its own gestures, the way MapTouchGuard does for osmdroid. Without this,
        // the box in StopHero that swallows single-pointer pans cancels the gesture instead.
        mapViewFactory = { mapContext, options -> GestureClaimingMapView(mapContext, options) },
    ) {
        // Runs once the map object exists, so the camera is there to be moved. Placed inside the
        // map content because MapEffect belongs to the map's own composable scope.
        MapEffect(routeKey, mapSize, contentPadding) { map ->
            if (fitTargets.isEmpty()) return@MapEffect
            if (mapSize.width <= 0 || mapSize.height <= 0) return@MapEffect
            if (fittedRouteKey == routeKey) return@MapEffect
            fittedRouteKey = routeKey

            runCatching {
                // The viewport is converted from measured pixels into dp *here*, and only for Google.
                // The Maps SDK sizes its zoom against the density-independent viewport, so feeding it
                // raw pixels makes every device's fit depend on its screen density: the same 280 dp
                // hero is 840 px on a 480 dpi phone and 735 px on a 420 dpi emulator, and the pixel
                // form of the same formula picked zoom 15 for the first and 14 for the second — and
                // the phone's 15 then cropped fourteen stops down to the eight that fitted. In dp the
                // two devices agree.
                val fit = cameraFitFor(
                    targets = fitTargets,
                    width = mapSize.width / densityScale,
                    height = mapSize.height / densityScale,
                    horizontalMargin = horizontalMarginDp,
                    verticalMargin = verticalMarginDp,
                    // A fixed zoom *number* has to be translated too: Google's 17 shows a third of the
                    // ground osmdroid's 17 shows. See [fitZoomFor].
                    singleStopZoom = SINGLE_STOP_ZOOM - densityZoomOffset(densityScale),
                )
                Log.i(
                    TAG,
                    "Google fit: view=${mapSize.width}x${mapSize.height} " +
                        "(${mapSize.width / densityScale}x${mapSize.height / densityScale} dp) " +
                        "padX=${horizontalMarginDp}dp padY=${verticalMarginDp}dp " +
                        "targets=${fitTargets.size} -> ${fit.lat},${fit.lng} z=${fit.zoom}",
                )
                map.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(fit.lat, fit.lng), fit.zoom.toFloat()),
                )
                // What the engine actually rendered at, rather than what was asked for: the visible
                // region is ground truth for whether the whole box really landed on screen.
                val bounds = map.projection.visibleRegion.latLngBounds
                Log.i(
                    TAG,
                    "Google camera now: ${map.cameraPosition.target} z=${map.cameraPosition.zoom}" +
                        " visible=${bounds.southwest}..${bounds.northeast}",
                )
            }
        }

        if (linePoints.size >= 2) {
            GooglePolyline(
                points = linePoints,
                color = ROUTE_CASING_COLOR,
                width = ROUTE_CASING_WIDTH,
                zIndex = 1f,
            )
            GooglePolyline(
                points = linePoints,
                color = ROUTE_COLOR,
                width = ROUTE_WIDTH,
                zIndex = 2f,
            )
        }

        // A plain for loop, not forEach: the map content lambda is @GoogleMapComposable and the
        // target marker has to stay on the call site for the marker composables to be legal here.
        for (stop in stops) {
            val visited = stop.id in visitedIds
            val selected = stop.id == selectedStopId
            val fill = when {
                selected -> AndroidColor.parseColor("#FFC8892F")
                visited -> AndroidColor.parseColor("#FF0F6E6E")
                else -> AndroidColor.parseColor("#FF1B5E8C")
            }
            GoogleMarker(
                // The icon helper is a pure function of (order, visited, selected), so each marker
                // keeps its own bitmap for as long as that combination holds.
                state = rememberUpdatedMarkerState(position = LatLng(stop.lat, stop.lng)),
                contentDescription = "${stop.order}. ${stop.name}",
                title = "${stop.order}. ${stop.name}",
                // The pin sits in the middle of a square bitmap, so anchoring the bitmap's bottom
                // centre puts the marker exactly where osmdroid's ANCHOR_CENTER/ANCHOR_BOTTOM does.
                anchor = Offset(0.5f, 1.0f),
                icon = remember(stop.order, visited, selected) {
                    numberedMarkerIcon(context, stop.order, fill, visited).toBitmapDescriptor()
                },
                zIndex = if (selected) 3f else 2f,
                onClick = {
                    onStopClick(stop)
                    true
                },
            )
        }

        if (walker.hasFix) {
            val fix = LatLng(walker.lat.toDouble(), walker.lng.toDouble())

            // The accuracy circle goes under the dot: it is added first so it draws beneath it.
            val accuracy = userAccuracyMeters
            if (accuracy != null && accuracy > MIN_ACCURACY_TO_DRAW) {
                Circle(
                    center = fix,
                    radius = accuracy.coerceAtMost(MAX_ACCURACY_RADIUS_METERS).toDouble(),
                    fillColor = Color(ACCURACY_FILL),
                    strokeColor = Color(ACCURACY_STROKE),
                    strokeWidth = 3f,
                    zIndex = 2.5f,
                )
            }

            GoogleMarker(
                state = rememberUpdatedMarkerState(position = fix),
                contentDescription = "You are here",
                anchor = Offset(0.5f, 0.5f),
                // Built here, inside the map's content, rather than above it: the Maps SDK only has
                // a bitmap factory once the map has started, and asking for a descriptor earlier
                // crashes with "IBitmapDescriptorFactory is not initialized".
                icon = remember(userHeading) {
                    (if (userHeading != null) coneDot else plainDot).toBitmapDescriptor()
                },
                rotation = walker.heading,
                zIndex = 4f,
                // Tapping your own dot should not open a bubble over the map.
                onClick = { false },
            )
        }
    }
}

/**
 * A camera fit in engine-neutral terms: where to look, and how close. osmdroid turns it into a
 * `GeoPoint` and a zoom, Google Maps into a `CameraPosition`.
 */
private data class CameraFit(val lat: Double, val lng: Double, val zoom: Double)

/**
 * Room the fit has to leave clear, per axis, in device pixels.
 *
 * Per axis, because what has to stay clear is not the same on each. A numbered pin is a square
 * bitmap anchored at its bottom centre, so the whole pin stands *above* the stop it marks and the top
 * of the view needs a pin's height, while the bottom of the view only needs room for the engine's own
 * logo and attribution. Horizontally the pin needs half its width either side, and nothing else is in
 * the way.
 *
 * One margin for both axes is what broke this map. The 200 px the overview used was small enough for
 * the width — which was never the limit — and still large enough to cost the overview a whole zoom
 * level on the height; the 420 px the stop page used to clear its chips ate more than the entire
 * height of the hero, which is what forced a 25% floor on the usable height to stop the fit
 * collapsing. A clamp that hides a constraint is worse than the constraint itself: that floor let the
 * width alone decide the stop page's zoom. Sizing each axis to what is actually in the way removes
 * the need for any floor.
 */
private data class FitMargins(
    /** Per side, left and right. */
    val horizontalPx: Int,
    /**
     * Per side, top and bottom — the larger of the two real needs, used on both sides. One camera
     * centre cannot be offset for two different margins, so the box stays centred instead, which is
     * one thing both engines agree on.
     */
    val verticalPx: Int,
    /** The strip the app's own chips actually cover, or 0 where nothing is drawn over the map. */
    val topStripPx: Int,
    /** The strip the engine's logo and attribution actually cover. */
    val bottomStripPx: Int,
)

/**
 * Measures, once per density, how much room the fit has to leave clear. See [FitMargins].
 *
 * @param focusStops the stops a stop page asked to frame, or null for the whole-route overview. A
 *   focus request is also the signal that this is the map inside `StopHero`, with the Photo/Map chips
 *   drawn over its top corner — the only view that has anything over the map besides the engine's own
 *   chrome.
 */
@Composable
private fun rememberFitMargins(focusStops: List<StopEntity>?): FitMargins {
    val context = LocalContext.current
    val density = LocalDensity.current
    // The pin is a fixed-size bitmap with no density of its own, so its clearance is in device pixels
    // — and it is measured from the icon rather than restated, so redrawing the icon moves the margin
    // with it.
    val pinPx = remember(context) {
        numberedMarkerIcon(context, 1, AndroidColor.BLACK, false)
            .intrinsicWidth
            .takeIf { it > 0 } ?: PIN_FALLBACK_PX
    }
    val focused = focusStops != null
    return remember(pinPx, density.density, focused) {
        val gapPx = with(density) { MARKER_GAP_DP.dp.roundToPx() }
        val attributionPx = with(density) { ATTRIBUTION_STRIP_DP.dp.roundToPx() }
        val chipsPx = if (focused) with(density) { HERO_CHIPS_STRIP_DP.dp.roundToPx() } else 0
        // Top: the pin stands its whole height above its stop, and on a stop page it stands under the
        // chips. Bottom: the pin sits *on* its stop, so only the logo and attribution are down there.
        val abovePx = chipsPx + pinPx
        FitMargins(
            horizontalPx = pinPx / 2 + gapPx,
            verticalPx = maxOf(abovePx, attributionPx) + gapPx,
            topStripPx = chipsPx,
            bottomStripPx = attributionPx,
        )
    }
}

/**
 * The one camera fit both engines use: the centre of the box [targets] occupy, and the highest
 * integer zoom at which that whole box still fits inside the viewport.
 *
 * This is shared on purpose. The two engines each had their own fit, and a fit that only one of
 * them tightened is how a stop page ends up showing a different view on Google Maps than on the
 * fallback. With one calculation, "the current stop and the next one, as close as both allow"
 * means the same thing whichever map draws it — and with no routing through osmdroid's
 * `zoomToBoundingBox`, neither engine can hit the ANR that fit caused.
 *
 * The calculation is the same, but the *units* are not, and that is the caller's business: osmdroid
 * passes pixels, Google passes dp. See [fitZoomFor] for why that has to be so.
 *
 * A single stop has no box to fit, so it is centred at a fixed walking zoom instead — and being a
 * fixed zoom *number*, that value is one of the few things that still needs translating per engine.
 *
 * @param targets stops to frame; must not be empty — callers check before fitting.
 * @param width viewport width in the engine's own zoom units.
 * @param height viewport height in the engine's own zoom units.
 * @param horizontalMargin margin to keep clear per side, left and right, in those same units.
 * @param verticalMargin margin to keep clear per side, top and bottom, in those same units.
 * @param singleStopZoom the level a one-stop view opens at, in the engine's own units.
 */
private fun cameraFitFor(
    targets: List<StopEntity>,
    width: Float,
    height: Float,
    horizontalMargin: Float,
    verticalMargin: Float,
    singleStopZoom: Double = SINGLE_STOP_ZOOM,
): CameraFit {
    if (targets.size == 1) {
        return CameraFit(targets[0].lat, targets[0].lng, singleStopZoom)
    }

    val box = BoundingBox.fromGeoPoints(targets.map { GeoPoint(it.lat, it.lng) })
    return CameraFit(
        lat = (box.latNorth + box.latSouth) / 2.0,
        lng = (box.lonEast + box.lonWest) / 2.0,
        zoom = fitZoomFor(
            north = box.latNorth,
            south = box.latSouth,
            east = box.lonEast,
            west = box.lonWest,
            width = width,
            height = height,
            horizontalMargin = horizontalMargin,
            verticalMargin = verticalMargin,
        ),
    )
}

/** Where to draw the walker's dot, already glided to hide GPS noise. */
private data class WalkerDot(
    val hasFix: Boolean,
    val lat: Float,
    val lng: Float,
    val heading: Float,
)

/**
 * Animates the walker's fix and compass bearing the way the osmdroid map does: the dot glides to
 * each new fix rather than jumping to it, and the heading cone swings the short way round.
 */
@Composable
private fun rememberWalkerDot(
    userLat: Double?,
    userLng: Double?,
    userHeading: Float?,
): WalkerDot {
    val animatedLat = remember { Animatable(0f) }
    val animatedLng = remember { Animatable(0f) }
    val animatedHeading = remember { Animatable(0f) }

    LaunchedEffect(userLat, userLng) {
        val lat = userLat ?: return@LaunchedEffect
        val lng = userLng ?: return@LaunchedEffect
        if (animatedLat.value == 0f && animatedLng.value == 0f) {
            // First fix: appear where the walker actually is instead of sliding in from the origin.
            animatedLat.snapTo(lat.toFloat())
            animatedLng.snapTo(lng.toFloat())
        } else {
            // Both axes together, so the dot travels in a straight line rather than an L.
            launch { animatedLat.animateTo(lat.toFloat(), tween(POSITION_GLIDE_MS)) }
            animatedLng.animateTo(lng.toFloat(), tween(POSITION_GLIDE_MS))
        }
    }

    LaunchedEffect(userHeading) {
        val target = userHeading ?: return@LaunchedEffect
        // Rotate the short way round: 350 to 10 degrees should move 20 degrees forward, not 340 back.
        val current = animatedHeading.value
        var delta = (target - current) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        animatedHeading.animateTo(current + delta, tween(HEADING_GLIDE_MS))
    }

    if (userLat == null || userLng == null) {
        return WalkerDot(hasFix = false, lat = 0f, lng = 0f, heading = 0f)
    }
    return WalkerDot(
        hasFix = true,
        lat = animatedLat.value,
        lng = animatedLng.value,
        heading = animatedHeading.value,
    )
}

/**
 * Google draws markers from a [BitmapDescriptor], osmdroid from a [Drawable]. Both icons are still
 * built by the shared helpers, so wrapping their bitmap is what keeps the two maps looking alike.
 *
 * This is called from inside the map's content, which is the only place the Maps SDK's bitmap
 * factory is guaranteed to exist. A drawable that is not backed by a bitmap, or a factory that is
 * somehow still missing, yields no icon — Google then draws its default pin rather than the screen
 * dying over a marker.
 */
private fun Drawable.toBitmapDescriptor(): BitmapDescriptor? = runCatching {
    val bitmap = (this as? BitmapDrawable)?.bitmap ?: return null
    BitmapDescriptorFactory.fromBitmap(bitmap)
}.getOrNull()

/** Normalised Web Mercator X in [0, 1]. */
private fun mercatorX(lonDegrees: Double): Double = (lonDegrees + 180.0) / 360.0

/** Normalised Web Mercator Y in [0, 1], north to south. */
private fun mercatorY(latDegrees: Double): Double {
    val lat = latDegrees.coerceIn(-85.05112878, 85.05112878)
    val radians = Math.toRadians(lat)
    return (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0
}

/**
 * The zoom levels a Google camera sits below osmdroid's for the same ground scale on this device:
 * `log2(density)`, and zero on osmdroid. Only fixed zoom *numbers* need it — the box fit is already
 * computed in the engine's own units. See [fitZoomFor].
 */
private fun densityZoomOffset(density: Float): Double = ln(density.toDouble()) / ln(2.0)

/**
 * Largest integer zoom at which the given latitude/longitude box fits inside the viewport.
 *
 * This replaces osmdroid's `zoomToBoundingBox`, which can spin the main thread indefinitely: every
 * value here is derived arithmetically, so there is no loop that can fail to terminate.
 *
 * Both engines draw Web Mercator with 256-unit tiles, so this one formula serves both — but they do
 * not count the same units, and that is the whole reason the two maps disagreed. osmdroid's zoom is
 * a function of the map view in *device pixels*, so at zoom z its world is `256 * 2^z` px. The Google
 * Maps Android SDK ties zoom to *density-independent* pixels, so at the same zoom its world is
 * `256 * 2^z * density` px — 1.585 levels closer on the owner's 480 dpi phone, which is three times
 * the ground scale.
 *
 * That is measured, not assumed. Two stops 0.001628° of longitude apart render 113.7 px apart on
 * that phone at camera zoom 15, where `256 * 2^15 * 3 * 0.001628 / 360 = 113.8`, and 151.0 px apart
 * on the 420 dpi emulator's osmdroid map at zoom 17, where `256 * 2^17 * 0.001628 / 360 = 151.7`.
 * Feeding Google a level computed in osmdroid's pixels therefore asked for a view 1.585 levels too
 * close: the fourteen-stop box needs 372 px of height at zoom 15 in osmdroid's pixels, so 1116 px
 * under Google on that phone, inside an 840 px hero — and the outer stops fell off the map. That is
 * the reported "too zoomed in, not all stops showing".
 *
 * So the caller converts instead of sharing: osmdroid hands this function pixels and a pixel margin,
 * Google hands it dp and a dp margin. One formula, two unit systems, no constant pretending that the
 * two engines measure the same thing.
 *
 * @param width viewport width in the caller's units.
 * @param height viewport height in the caller's units.
 * @param horizontalMargin margin to keep clear per side, in the same units.
 * @param verticalMargin margin to keep clear per side, in the same units.
 */
private fun fitZoomFor(
    north: Double,
    south: Double,
    east: Double,
    west: Double,
    width: Float,
    height: Float,
    horizontalMargin: Float,
    verticalMargin: Float,
): Double {
    // A one-unit floor keeps the logarithms finite should a caller ever pass a margin wider than its
    // viewport. It is not a clamp on the fit: the margins are sized to real pins and real chrome, so
    // they cannot come close, and the 25% floor this replaces is exactly what hid the vertical
    // constraint on the stop page and let the width alone choose its zoom.
    val usableWidth = (width - 2 * horizontalMargin).coerceAtLeast(1f)
    val usableHeight = (height - 2 * verticalMargin).coerceAtLeast(1f)

    val spanX = abs(mercatorX(east) - mercatorX(west)).coerceAtLeast(1e-9)
    val spanY = abs(mercatorY(south) - mercatorY(north)).coerceAtLeast(1e-9)

    val zoomForWidth = ln(usableWidth / (spanX * TILE_SIZE_PX)) / ln(2.0)
    val zoomForHeight = ln(usableHeight / (spanY * TILE_SIZE_PX)) / ln(2.0)

    val zoom = floor(min(zoomForWidth, zoomForHeight))
    return zoom.coerceIn(2.0, 19.0)
}

/**
 * The "you are here" dot, drawn the way map apps do it: a soft accuracy halo, a white ring, a solid
 * blue centre, and a translucent cone pointing straight up.
 *
 * The cone points up because the marker is rotated by `Marker.setRotation` using the compass
 * bearing, so "up" in this bitmap becomes "the way you are facing" on the map.
 */
private fun userLocationDot(
    context: android.content.Context,
    withCone: Boolean,
): android.graphics.drawable.Drawable {
    // The bitmap is mostly cone, so it is much larger than the dot. The anchor is the bitmap centre,
    // which is also the dot, so rotating the marker spins the cone about the dot rather than about
    // the middle of a long wedge.
    val size = 400
    val centre = size / 2f
    val dotRadius = 18f
    val ringRadius = 24f

    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    // Heading cone: apex at the dot, flaring to about seventy degrees, fading along its length and
    // blurred at the edges so it reads as a direction of travel rather than a wedge stuck to the dot.
    val coneLength = 150f
    val coneHalfWidth = 100f
    val cone = android.graphics.Path().apply {
        moveTo(centre, centre - 10f)
        lineTo(centre - coneHalfWidth, centre - coneLength)
        lineTo(centre + coneHalfWidth, centre - coneLength)
        close()
    }
    paint.shader = android.graphics.LinearGradient(
        centre, centre - 10f, centre, centre - coneLength,
        intArrayOf(
            android.graphics.Color.argb(150, 26, 115, 232),
            android.graphics.Color.argb(96, 26, 115, 232),
            android.graphics.Color.argb(0, 26, 115, 232),
        ),
        floatArrayOf(0f, 0.5f, 1f),
        android.graphics.Shader.TileMode.CLAMP,
    )
    // Blurred while drawing into this software bitmap, so the result is baked in and osmdroid can
    // draw it on a hardware canvas, which does not support mask filters.
    if (withCone) {
        paint.maskFilter = android.graphics.BlurMaskFilter(20f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        canvas.drawPath(cone, paint)
        paint.maskFilter = null
    }
    paint.shader = null

    // A soft shadow lifts the dot off the map, the way a map app's dot sits above the tiles.
    paint.color = android.graphics.Color.argb(56, 0, 0, 0)
    paint.maskFilter = android.graphics.BlurMaskFilter(9f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    canvas.drawCircle(centre, centre + 2f, ringRadius + 3f, paint)
    paint.maskFilter = null

    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(centre, centre, ringRadius, paint)
    paint.color = android.graphics.Color.parseColor("#FF1A73E8")
    canvas.drawCircle(centre, centre, dotRadius, paint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

private const val TAG = "TourMap"
