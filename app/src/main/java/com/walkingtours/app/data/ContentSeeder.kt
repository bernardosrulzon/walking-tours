package com.walkingtours.app.data

import android.content.Context
import android.util.Log
import com.walkingtours.app.data.db.StopEntity
import com.walkingtours.app.data.db.TourDao
import com.walkingtours.app.data.db.TourEntity
import org.json.JSONObject
import java.io.IOException

/**
 * Loads the bundled tour content into the database on first launch.
 *
 * Content lives as JSON in `assets/tours/` rather than as SQL in the APK, which means new tours can
 * be authored, reviewed and diffed as plain text, and a future release can ship the same JSON from
 * a server without touching this class.
 *
 * Seeding is idempotent: it runs only when the tours table is empty, so user progress survives
 * every subsequent launch.
 */
class ContentSeeder(
    private val context: Context,
    private val dao: TourDao,
) {

    suspend fun seedIfEmpty() {
        // Seed any tour whose id is not in the database yet, so a bundle that adds a new tour file
        // is picked up on the next launch even though the app has run before. Already-seeded tours
        // are left untouched: progress and settings keyed by tour/stop id keep working.
        val assetNames = try {
            context.assets.list(ASSET_DIR)?.filter { it.endsWith(".json") }.orEmpty()
        } catch (e: IOException) {
            Log.e(TAG, "Could not list bundled tours", e)
            emptyList()
        }

        if (assetNames.isEmpty()) {
            Log.w(TAG, "No bundled tours found in assets/$ASSET_DIR")
            return
        }

        for (name in assetNames.sorted()) {
            try {
                val json = context.assets.open("$ASSET_DIR/$name")
                    .bufferedReader()
                    .use { it.readText() }
                val root = JSONObject(json)
                val tourId = root.getJSONObject("tour").getString("id")
                if (dao.getTour(tourId) != null) continue
                seedFromJson(root)
            } catch (e: Exception) {
                // One malformed tour must not stop the others from loading.
                Log.e(TAG, "Failed to seed tour from $name", e)
            }
        }
    }

    private suspend fun seedFromJson(root: JSONObject) {
        val t = root.getJSONObject("tour")
        val tourId = t.getString("id")

        val tour = TourEntity(
            id = tourId,
            title = t.getString("title"),
            city = t.getString("city"),
            country = t.getString("country"),
            summary = t.getString("summary"),
            overviewText = t.getString("overviewText"),
            distanceKm = t.getDouble("distanceKm"),
            totalWalkMinutes = t.getInt("totalWalkMinutes"),
            difficulty = t.getString("difficulty"),
            bestTimeOfDay = t.getString("bestTimeOfDay"),
            heroImage = t.optString("heroImage").ifBlank { null },
            imageCredits = t.optString("imageCredits", ""),
        )

        val stopArray = root.getJSONArray("stops")
        val stops = ArrayList<StopEntity>(stopArray.length())
        for (i in 0 until stopArray.length()) {
            val s = stopArray.getJSONObject(i)
            stops += StopEntity(
                id = s.getString("id"),
                tourId = tourId,
                order = s.getInt("order"),
                name = s.getString("name"),
                category = s.getString("category"),
                lat = s.getDouble("lat"),
                lng = s.getDouble("lng"),
                narration = s.getString("narration"),
                suggestedMinutes = s.optInt("suggestedMinutes", 5),
                entranceFeeTry = s.optString("entranceFeeTry", "Unknown"),
                entranceFeeNote = s.optString("entranceFeeNote", ""),
                isFree = s.optBoolean("isFree", false),
                openingHours = s.optString("openingHours", ""),
                accessibility = s.optString("accessibility", ""),
                insiderTip = s.optString("insiderTip", ""),
                nextStopDirections = s.optString("nextStopDirections", ""),
                photoAsset = s.optString("photoAsset").takeIf { it.isNotBlank() },
                photoAttribution = s.optString("photoAttribution").takeIf { it.isNotBlank() },
                triggerRadiusMeters = s.optInt("triggerRadiusMeters", DEFAULT_RADIUS_METERS),
            )
        }

        dao.insertTours(listOf(tour))
        dao.insertStops(stops)
        Log.i(TAG, "Seeded tour '$tourId' with ${stops.size} stops")
    }

    private companion object {
        const val TAG = "ContentSeeder"
        const val ASSET_DIR = "tours"
        const val DEFAULT_RADIUS_METERS = 40
    }
}
