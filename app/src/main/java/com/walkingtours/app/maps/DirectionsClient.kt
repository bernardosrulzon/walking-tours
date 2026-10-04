package com.walkingtours.app.maps

import android.content.Context
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.PolyUtil
import com.walkingtours.app.BuildConfig
import com.walkingtours.app.ai.AiException
import com.walkingtours.app.ai.AiHttp
import com.walkingtours.app.ai.AppIdentityHeaders
import com.walkingtours.app.data.db.StopEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches the real walking route through a tour's stops from the Google Routes API.
 *
 * The map can draw straight hops between consecutive stops on its own, but that line is wrong in a
 * way a walker notices immediately: it cuts through buildings and across water. The Routes API
 * returns the polyline a person would actually walk, and this turns it into something the map can
 * draw.
 *
 * It replaces the legacy Directions API, whose endpoint Google now refuses outright with
 * `REQUEST_DENIED ... You're calling a legacy API`. The new endpoint is a POST whose key travels in
 * the `X-Goog-Api-Key` header, never in the query string, and whose response is trimmed to just the
 * encoded polyline by the `X-Goog-FieldMask` header.
 *
 * It is only ever called when a Maps key was baked in at build time, because it needs the same key,
 * and it is deliberately forgiving: on any failure it returns an empty list and the map falls back
 * to the straight line. Nothing here is allowed to throw into the UI.
 *
 * Routes are cached in memory for the life of the process and never written to disk. Google's terms
 * do not permit storing Routes content, so the line is fetched again on the next launch and the
 * app's bundled JSON never carries geometry.
 */
internal object DirectionsClient {

    private const val TAG = "Directions"

    private const val COMPUTE_ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"

    /** Asking for the polyline alone keeps the response small; anything else is discarded anyway. */
    private const val FIELD_MASK = "routes.polyline.encodedPolyline"

    /** Routes accepts up to 25 intermediate waypoints, between a fixed origin and destination. */
    private const val MAX_INTERMEDIATES = 25

    /** Origin + intermediates + destination. Anything past this is dropped before the request. */
    private const val MAX_COORDINATES = MAX_INTERMEDIATES + 2

    /**
     * The route per tour id, for this process only.
     *
     * An empty list is a remembered failure rather than "not fetched": a key that cannot use the
     * Routes API is asked exactly once per launch instead of on every recomposition, and the map
     * simply keeps its straight line.
     */
    private val cache = ConcurrentHashMap<String, List<LatLng>>()

    /**
     * The walking line through [stops], in the order they are walked, or an empty list when no route
     * could be built.
     *
     * @param tourId what the answer is cached under; the same tour must never be fetched twice.
     */
    suspend fun walkingRoute(
        context: Context,
        tourId: String,
        stops: List<StopEntity>,
    ): List<LatLng> {
        cache[tourId]?.let { return it }
        if (tourId.isBlank() || stops.size < 2) return emptyList()

        val points = runCatching { fetchRoute(context, stops) }
            .onFailure { Log.i(TAG, "Routes API request failed for $tourId: ${it.message}") }
            .getOrDefault(emptyList())

        cache[tourId] = points
        return points
    }

    private suspend fun fetchRoute(context: Context, stops: List<StopEntity>): List<LatLng> {
        // A tour longer than the API's coordinate limit is truncated rather than rejected: a route
        // through the first stops still beats no route at all.
        val route = stops.take(MAX_COORDINATES)
        val origin = route.first()
        val destination = route.last()
        val intermediates = route.subList(1, route.size - 1)

        val body = JSONObject().apply {
            put("origin", waypoint(origin))
            put("destination", waypoint(destination))
            put(
                "intermediates",
                JSONArray().apply { intermediates.forEach { put(waypoint(it)) } },
            )
            // WALK, not the legacy "mode=walking": the Routes API spells travel modes in caps.
            put("travelMode", "WALK")
        }

        // An Android-restricted key is only accepted together with the app's own package name and
        // signing certificate, which is what AppIdentityHeaders supplies. The Routes API takes the
        // key in a header; ours is merged last so it always wins over the identity headers.
        val headers = AppIdentityHeaders.build(context) + mapOf(
            "X-Goog-Api-Key" to BuildConfig.GOOGLE_MAPS_API_KEY,
            "X-Goog-FieldMask" to FIELD_MASK,
        )

        // Unlike the legacy API, Routes reports refusals with a real HTTP status and an error body,
        // which AiHttp surfaces as an AiException carrying that status and Google's message.
        val json = try {
            AiHttp.postJson(COMPUTE_ROUTES_URL, body, headers)
        } catch (e: AiException) {
            Log.i(TAG, "Routes API failed with HTTP ${e.httpCode} for ${route.size} stops: ${e.message}")
            return emptyList()
        }

        val encoded = json.optJSONArray("routes")
            ?.optJSONObject(0)
            ?.optJSONObject("polyline")
            ?.optString("encodedPolyline")
            .orEmpty()
        if (encoded.isBlank()) {
            Log.i(TAG, "Routes API returned no route geometry for ${route.size} stops")
            return emptyList()
        }

        val points = PolyUtil.decode(encoded)
        Log.i(TAG, "Routes API: ${points.size} points")
        return points
    }

    /** One stop in the Routes API's nested `waypoint.location.latLng` shape. */
    private fun waypoint(stop: StopEntity): JSONObject = JSONObject().put(
        "location",
        JSONObject().put(
            "latLng",
            JSONObject()
                .put("latitude", stop.lat)
                .put("longitude", stop.lng),
        ),
    )
}
