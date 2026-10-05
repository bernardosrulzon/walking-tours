package com.walkingtours.app.ui.components

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.util.Log
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
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
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker as GoogleMarker
import com.google.maps.android.compose.Polyline as GooglePolyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.walkingtours.app.BuildConfig
import com.walkingtours.app.WalkingToursApp
import com.walkingtours.app.data.db.StopEntity
import com.walkingtours.app.maps.DirectionsClient
import kotlinx.coroutines.delay
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
import kotlin.math.pow
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

/**
 * Zoom a multi-stop map is created with, before its viewport is measured.
 *
 * It is only ever the starting camera of a map that is fitted during layout, so it is deliberately
 * between the two extremes a fit produces here: about 13 for the whole fourteen-stop route and about
 * 16 for a stop page's current-and-next pair.
 */
private const val INITIAL_ROUTE_ZOOM = 15.0

/** Air kept clear on both axes when the whole route is framed, in device pixels. */
/**
 * Air around the framed box, per axis and in viewport units.
 *
 * Horizontal is the larger of the two because it is also what keeps a badge from being clipped:
 * a badge is anchored centrally across its width, so it reaches half a badge sideways but its whole
 * height upwards. Vertical air is small; [MARKER_SIZE_PX] already reserves the top.
 */
private const val FIT_PADDING_X_PX = 60
private const val FIT_PADDING_Y_PX = 16

/**
 * Air kept clear on both axes when a stop page frames the current stop and the next one, in device
 * pixels. Larger than [FIT_PADDING_X_PX] because the hero map is a much shorter viewport and carries
 * the Photo/Map chips over its top corner.
 */
private const val FOCUS_PADDING_X_PX = 60
private const val FOCUS_PADDING_Y_PX = 16

/**
 * Google's own margins, in **dp**, deliberately separate from the two above.
 *
 * The engines do not share a zoom definition. osmdroid ties zoom to the map view in device pixels;
 * the Google Maps Android SDK ties it to the density-independent viewport. Feeding Google the pixel
 * figures made its camera render a screen-density factor closer — on a 480 dpi phone the
 * fourteen-stop overview cropped to eight stops. Measuring Google's viewport and margins in dp puts
 * the fit in the same space the SDK defines its zoom in, which is why these are not shared.
 */
private const val GOOGLE_FIT_PADDING_X_DP = 32
private const val GOOGLE_FIT_PADDING_Y_DP = 10

/** See [GOOGLE_FIT_PADDING_X_DP]. Larger because a stop-page hero is a much shorter viewport. */
private const val GOOGLE_FOCUS_PADDING_X_DP = 32
private const val GOOGLE_FOCUS_PADDING_Y_DP = 16

/**
 * How long a map waits before its engine is built, in milliseconds.
 *
 * A little longer than the host's transitions take (300 ms), so that the heaviest thing on these
 * screens is never built while the screen is moving. The map's place is held by an empty panel of
 * its own size in the meantime.
 */
private const val MAP_SETTLE_MS = 340L

/**
 * How long the Google map has to attach before it is shown regardless.
 *
 * The map is held back until then because the SDK's own view is on screen for the frames it spends
 * starting up. If it never attaches — no Play Services, a key the SDK refuses — that never happens,
 * and a permanently blank hero would be worse than the map the SDK would eventually have drawn.
 */
private const val MAP_REVEAL_FALLBACK_MS = 1_500L

/**
 * The fit never treats an axis as narrower than this fraction of itself. Without it, padding wider
 * than a short viewport would make the usable span negative and the logarithm undefined.
 */
private const val MIN_USABLE_FRACTION = 0.25

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
 * The stop badge bitmap's edge length, in device pixels, matching [numberedMarkerIcon].
 *
 * Badges are anchored by their bottom edge, so every one of them hangs this far above the
 * coordinate it names. The fit has to reserve that height or the top badge overflows the viewport
 * while an equal-looking gap is left empty at the bottom.
 */
private const val MARKER_SIZE_PX = 80f

/** Equatorial circumference, for converting zoom levels to ground distances. */
private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686

/** Metres in one degree of latitude; the geodesic variation is immaterial at city scale. */
private const val METRES_PER_DEGREE_LAT = 111_320.0

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
    // Nothing to frame yet — the tour's stops are still loading — so no engine is chosen and no map
    // is created, whichever engine it would have been.
    //
    // What the caller's modifier carries is the map's size, so its place is held with an empty panel
    // of exactly those dimensions: nothing loads, nothing flashes, and the page beneath does not move
    // when the stops arrive a moment later. The alternative on the Google path was a map created
    // without a camera, which paints the whole planet at (0, 0) until one reaches it.
    val fitTargets = focusStops?.takeIf { it.isNotEmpty() } ?: stops

    // And held back until the screen has stopped moving.
    //
    // Building a map — a Google map above all — is the heaviest work on these screens, and doing it
    // inside a navigation was measured here at double the jank and quarter-second hitches: the work
    // lands in the middle of the slide, which is exactly when a dropped frame is visible. The
    // routes into these screens take 300 ms (see WalkingToursNavHost), so the engine is built a
    // little after that, and the empty panel holds its place in the meantime. Nothing is lost by
    // waiting; the movement stays smooth.
    var mapSettled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(MAP_SETTLE_MS)
        mapSettled = true
    }

    if (fitTargets.isEmpty() || !mapSettled) {
        Box(modifier.clipToBounds())
        return
    }

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

    // The stops this map frames — already established above; a stop page asks for the stop you are
    // on and the one you walk to next, everywhere else the whole route.
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
            // A camera in the right city before the viewport is known. osmdroid starts at (0, 0), so
            // a map drawn even once before its fit shows the Gulf of Guinea — and in a pager that is
            // a page full of maps, it did that once per swipe. The exact fit lands during layout,
            // below; this is only so that nothing can ever catch the default.
            controller.setZoom(initialZoomFor(fitTargets))
            fitTargets.midpoint()?.let { (lat, lng) -> controller.setCenter(GeoPoint(lat, lng)) }
        }
    }

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
    val lifecycleOwner = LocalLifecycleOwner.current
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

    // Frame the route once per route, as soon as its viewport is known.
    //
    // The fit runs during layout — from `onSizeChanged`, before the map is drawn — rather than from a
    // LaunchedEffect after it. A map that is created and then waits a frame for its camera is drawn
    // at osmdroid's default of (0, 0), and in a pager that meant the whole world flashing up once per
    // swipe between stops.
    //
    // NOTE: osmdroid's MapView.zoomToBoundingBox is deliberately NOT used here. In 6.1.20 it routes
    // through Projection.getCloserPixel, a binary search that only terminates when the projected
    // pixel lands exactly on the bisection point. With a real multi-stop route it can fail to
    // converge and spin the main thread until Android raises an ANR — confirmed from device ANR
    // traces. The camera is fitted with a few lines of Web Mercator arithmetic instead, which
    // cannot loop.
    //
    // Keyed on the targets and the viewport, not on every layout pass: after the first fit the camera
    // is the walker's to pan and zoom.
    val routeKey = remember(fitTargets) {
        fitTargets.joinToString("|") { "${it.lat},${it.lng}" }
    }
    var fittedKey by remember { mutableStateOf<String?>(null) }
    fun fitToViewport(size: IntSize) {
        val key = "$routeKey:${size.width}x${size.height}"
        if (key == fittedKey) return
        if (fitTargets.isEmpty() || size.width <= 0 || size.height <= 0) return
        fittedKey = key

        runCatching {
            // osmdroid measures its zoom against the map view in device pixels, which is exactly what
            // the map reports here, so the raw measured viewport is what the fit is given.
            val fit = cameraFitFor(
                targets = fitTargets,
                widthPx = size.width.toFloat(),
                heightPx = size.height.toFloat(),
                paddingX = (if (focusStops != null) FOCUS_PADDING_X_PX else FIT_PADDING_X_PX).toFloat(),
                paddingY = (if (focusStops != null) FOCUS_PADDING_Y_PX else FIT_PADDING_Y_PX).toFloat(),
                topInsetPx = MARKER_SIZE_PX,
            )
            mapView.controller.setZoom(fit.zoom)
            mapView.controller.setCenter(GeoPoint(fit.lat, fit.lng))
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
            .onSizeChanged { fitToViewport(it) },
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

    // Read here rather than inside the fit: this is a composable read and cannot live in a
    // runCatching block. Google's camera is defined in dp, so the fit must be too.
    val density = LocalDensity.current.density

    // Same fit targets as the osmdroid map: a stop page asks for the stop you are on and the one you
    // walk to next; everywhere else the whole route is framed.
    val fitTargets = focusStops?.takeIf { it.isNotEmpty() } ?: stops
    val routeKey = remember(fitTargets) {
        fitTargets.joinToString("|") { "${it.lat},${it.lng}" }
    }

    val initialCamera = fitTargets.midpoint()?.let { (lat, lng) ->
        CameraPosition.fromLatLngZoom(
            LatLng(lat, lng),
            initialZoomFor(fitTargets).toFloat(),
        )
    }

    // The camera the map is created with. Google Maps starts at (0, 0) at world zoom, so a map drawn
    // even once before its fit shows the planet — and in a page full of maps, that is a flash per
    // swipe between stops. Setting the position here means the first camera move the SDK makes is
    // already to the right city; the exact fit follows during layout, before anything is drawn.
    //
    // Keyed on the route so the starting camera always belongs to the route being framed: a state
    // handed back by a page that was left while its route was still unknown would otherwise restore
    // the default it was saved with and put the planet back on screen.
    val cameraPositionState = rememberCameraPositionState(key = routeKey) {
        initialCamera?.let { position = it }
    }

    // Held out of sight until the map object exists.
    //
    // Even with the camera above, a Google map is not obliged to paint it first: the SDK's own view
    // is on screen for the few frames it spends starting up. That is the whole-planet view the
    // introduction opened on. Google draws nothing worth seeing until then anyway, so the map stays
    // invisible until it is attached and looking where it was told to — which MapEffect below marks,
    // as soon as the map object exists, rather than waiting for tiles to arrive.
    var mapReady by remember { mutableStateOf(false) }
    LaunchedEffect(routeKey) {
        delay(MAP_REVEAL_FALLBACK_MS)
        mapReady = true
    }

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

    // Set the exact camera from the measured viewport, during layout.
    //
    // Assigning cameraPositionState.position settles the camera either way: before the map object
    // exists it is stored and applied the moment it does, and afterwards it moves the camera
    // directly. So the fit can run here, synchronously, rather than from a MapEffect a frame later —
    // which is what left one frame of the world map on screen per page.
    //
    // Keyed on the targets and the viewport, not on every layout pass: after the first fit the camera
    // is the walker's to pan and zoom.
    var fittedKey by remember { mutableStateOf<String?>(null) }
    fun fitToViewport(size: IntSize) {
        val key = "$routeKey:${size.width}x${size.height}"
        if (key == fittedKey) return
        if (fitTargets.isEmpty() || size.width <= 0 || size.height <= 0) return
        fittedKey = key

        runCatching {
            // The raw measured viewport, in device pixels, exactly as the osmdroid path uses it.
            // In dp, not pixels: see GOOGLE_FIT_PADDING_X_DP. The viewport and the margin must both
            // be in the space the SDK's zoom is defined in.
            val fit = cameraFitFor(
                targets = fitTargets,
                widthPx = size.width / density,
                heightPx = size.height / density,
                paddingX = (
                    if (focusStops != null) GOOGLE_FOCUS_PADDING_X_DP else GOOGLE_FIT_PADDING_X_DP
                    ).toFloat(),
                paddingY = (
                    if (focusStops != null) GOOGLE_FOCUS_PADDING_Y_DP else GOOGLE_FIT_PADDING_Y_DP
                    ).toFloat(),
                // The badge bitmap is 110 device pixels tall; Google measures in dp.
                topInsetPx = MARKER_SIZE_PX / density,
            )
            cameraPositionState.position = CameraPosition.fromLatLngZoom(
                LatLng(fit.lat, fit.lng),
                fit.zoom.toFloat(),
            )
        }
    }

    // Reuses the osmdroid path's bitmap: the cone is only drawn when there is a compass bearing to
    // rotate it by, otherwise it would sit there claiming the walker faces north. These are plain
    // drawables and are safe to build here; they only become Google icons inside the map below.
    val coneDot = remember { userLocationDot(context, withCone = true) }
    val plainDot = remember { userLocationDot(context, withCone = false) }

    val walker = rememberWalkerDot(userLat, userLng, userHeading)

    GoogleMap(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { fitToViewport(it) }
            .graphicsLayer { alpha = if (mapReady) 1f else 0f },
        cameraPositionState = cameraPositionState,
        // The camera goes into the options as well as the state. A map created without one opens on
        // Google's own default — the whole planet at (0, 0) — and paints it for as long as it takes
        // the camera to arrive. Given it here, the very first frame the SDK draws is already the
        // route's own city.
        googleMapOptionsFactory = {
            GoogleMapOptions().apply { initialCamera?.let { camera(it) } }
        },
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
        // The map object exists from here on, and maps-compose has already moved it to the camera
        // this composable set. Nothing is gained by leaving it hidden any longer.
        MapEffect(Unit) { mapReady = true }

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
 * Mean position of [targets], or null when there are none.
 *
 * The centre of the box a fit would frame, available before the viewport is known, so a map can be
 * created somewhere sensible instead of at the engine's default of (0, 0).
 */
private fun List<StopEntity>.midpoint(): Pair<Double, Double>? =
    if (isEmpty()) null else (sumOf { it.lat } / size) to (sumOf { it.lng } / size)

/**
 * The zoom to create a map of [targets] with.
 *
 * Only ever the starting camera of a map that is fitted as soon as it is measured, so it needs to be
 * a sensible walking zoom rather than an exact one.
 */
private fun initialZoomFor(targets: List<StopEntity>): Double =
    if (targets.size == 1) SINGLE_STOP_ZOOM else INITIAL_ROUTE_ZOOM

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
 * A single stop has no box to fit, so it is centred at a fixed walking zoom instead.
 *
 * @param targets stops to frame; must not be empty — callers check before fitting.
 * @param widthPx viewport width in device pixels.
 * @param heightPx viewport height in device pixels.
 * @param paddingPx air to keep clear on both axes, in device pixels.
 */
private fun cameraFitFor(
    targets: List<StopEntity>,
    widthPx: Float,
    heightPx: Float,
    paddingX: Float,
    paddingY: Float,
    topInsetPx: Float = 0f,
): CameraFit {
    if (targets.size == 1) {
        val lat = targets[0].lat +
            northFor(topInsetPx / 2f, SINGLE_STOP_ZOOM, targets[0].lat)
        return CameraFit(lat, targets[0].lng, SINGLE_STOP_ZOOM)
    }

    val box = BoundingBox.fromGeoPoints(targets.map { GeoPoint(it.lat, it.lng) })
    val zoom = fitZoomFor(
        box = box,
        widthPx = widthPx,
        heightPx = heightPx,
        paddingX = paddingX,
        paddingY = paddingY,
        topInsetPx = topInsetPx,
    )
    // Reserving the badge height at the top pushes the box down the screen, so the point the camera
    // centres on belongs half a badge north of the box centre. That is what puts the badges
    // themselves — not the bare coordinates — in the middle of what you see.
    val boxLat = (box.latNorth + box.latSouth) / 2.0
    return CameraFit(
        lat = boxLat + northFor(topInsetPx / 2f, zoom, boxLat),
        lng = (box.lonEast + box.lonWest) / 2.0,
        zoom = zoom,
    )
}

/**
 * How far north a viewport distance of [units] reaches at [zoom], in degrees of latitude.
 *
 * [units] is in whatever the caller measures its viewport in — device pixels for osmdroid, dp for
 * Google — which is also the unit its zoom is defined against, so the same formula serves both.
 */
private fun northFor(units: Float, zoom: Double, atLat: Double): Double {
    val metresPerUnit = EARTH_CIRCUMFERENCE_M / (TILE_SIZE_PX * 2.0.pow(zoom)) *
        cos(Math.toRadians(atLat))
    return units.toDouble() * metresPerUnit / METRES_PER_DEGREE_LAT
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
 * Largest integer zoom at which the given latitude/longitude box fits inside the viewport, less
 * [paddingPx] of air on both axes.
 *
 * This replaces osmdroid's `zoomToBoundingBox`, which can spin the main thread indefinitely: every
 * value here is derived arithmetically, so there is no loop that can fail to terminate.
 *
 * The viewport and the padding are in device pixels, which is the unit osmdroid's zoom is defined
 * against and what the map view measures itself in.
 *
 * @param widthPx viewport width in device pixels.
 * @param heightPx viewport height in device pixels.
 * @param paddingPx air to keep clear on both axes, in device pixels.
 */
private fun fitZoomFor(
    north: Double,
    south: Double,
    east: Double,
    west: Double,
    widthPx: Float,
    heightPx: Float,
    paddingX: Float,
    paddingY: Float,
    topInsetPx: Float = 0f,
): Double {
    val usableWidth = (widthPx - 2 * paddingX).coerceAtLeast(widthPx * MIN_USABLE_FRACTION.toFloat())
    // The top carries the padding plus the badge height: badges hang upwards, so reserving nothing
    // above the topmost coordinate lets the badge overflow the edge while the bottom margin, which
    // no badge occupies, reads as uneven padding.
    val usableHeight = (heightPx - 2 * paddingY - topInsetPx)
        .coerceAtLeast(heightPx * MIN_USABLE_FRACTION.toFloat())

    val spanX = abs(mercatorX(east) - mercatorX(west)).coerceAtLeast(1e-9)
    val spanY = abs(mercatorY(south) - mercatorY(north)).coerceAtLeast(1e-9)

    val zoomForWidth = ln(usableWidth / (spanX * TILE_SIZE_PX)) / ln(2.0)
    val zoomForHeight = ln(usableHeight / (spanY * TILE_SIZE_PX)) / ln(2.0)

    val zoom = floor(min(zoomForWidth, zoomForHeight))
    return zoom.coerceIn(2.0, 19.0)
}

/** Convenience overload of [fitZoomFor] for a box that has already been built. */
private fun fitZoomFor(
    box: BoundingBox,
    widthPx: Float,
    heightPx: Float,
    paddingX: Float,
    paddingY: Float,
    topInsetPx: Float = 0f,
): Double = fitZoomFor(
    north = box.latNorth,
    south = box.latSouth,
    east = box.lonEast,
    west = box.lonWest,
    widthPx = widthPx,
    heightPx = heightPx,
    paddingX = paddingX,
    paddingY = paddingY,
    topInsetPx = topInsetPx,
)

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
