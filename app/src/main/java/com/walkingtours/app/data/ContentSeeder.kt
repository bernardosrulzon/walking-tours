package com.walkingtours.app.data

import android.content.Context
import android.util.Log
import com.walkingtours.app.data.db.StopEntity
import com.walkingtours.app.data.db.TourDao
import com.walkingtours.app.data.db.TourEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    private val prefs = context.applicationContext
        .getSharedPreferences("bundled_content", Context.MODE_PRIVATE)

    /**
     * One content pass per process, however many screens ask for one.
     *
     * Every screen that shows tours calls [seedIfEmpty] as it appears, and the pass it used to run
     * read and parsed the whole asset bundle on the caller's thread — the main thread, from
     * `LaunchedEffect` — in the middle of the navigation transition. Device traces put those reads
     * and parses in the same frames as 40-60 ms main-thread stalls, which is exactly the stutter a
     * walker sees on the way from a city to its tour list.
     *
     * Nothing can change between two calls in one process — the assets are packaged in the APK —
     * so the first caller does the pass on IO and everyone after them returns immediately.
     */
    private val lock = Mutex()

    @Volatile
    private var checked = false

    suspend fun seedIfEmpty() {
        if (checked) return
        lock.withLock {
            if (checked) return
            withContext(Dispatchers.IO) { checkAndSeed() }
            checked = true
        }
    }

    /**
     * The pass itself: lists, reads and parses the bundled assets, then inserts anything the
     * database does not hold yet. Blocking work, so it runs on [Dispatchers.IO], never on the
     * thread that called [seedIfEmpty].
     */
    private suspend fun checkAndSeed() {
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

        // A content change — a tour edited, a price corrected — has to reach a device that already
        // holds the old copy, so bump [CONTENT_VERSION] and the whole bundle is re-seeded. Progress
        // and settings live in their own tables and are left alone.
        //
        // An empty tours table counts as a fresh bundle too: this database is built with a
        // destructive migration, so a schema change wipes the content without touching these
        // preferences — and without this the app would believe the content was already there.
        val freshBundle = prefs.getString(KEY_VERSION, null) != CONTENT_VERSION
        val emptyDatabase = dao.tourCount() == 0
        if (freshBundle || emptyDatabase) {
            dao.clearAllStops()
            dao.clearAllTours()
        }

        // A file an earlier run already seeded does not need to be opened again. Its name is
        // enough to skip it, which matters because the parse is the expensive half. A file the
        // run has never seen is not in the set and is picked up even when the version did not
        // change — the same promise the per-tour id check below used to keep.
        val seededFiles = if (freshBundle || emptyDatabase) {
            emptySet()
        } else {
            prefs.getStringSet(KEY_FILES, emptySet()).orEmpty()
        }

        val seeded = HashSet(seededFiles)
        for (name in assetNames.sorted()) {
            if (name in seeded) continue
            try {
                val json = context.assets.open("$ASSET_DIR/$name")
                    .bufferedReader()
                    .use { it.readText() }
                val root = JSONObject(json)
                val tourId = root.getJSONObject("tour").getString("id")
                // A database an earlier release filled (one that predates [KEY_FILES]) already
                // holds this tour; remember the file and leave the rows alone. Writing them again
                // would REPLACE the tour row, and the cascade would delete and re-insert its stops
                // for no gain.
                if (dao.getTour(tourId) == null) seedFromJson(root)
                seeded += name
            } catch (e: Exception) {
                // One malformed tour must not stop the others from loading. It stays out of the
                // seeded set, so a corrected file is picked up by a later run.
                Log.e(TAG, "Failed to seed tour from $name", e)
            }
        }

        prefs.edit()
            .putString(KEY_VERSION, CONTENT_VERSION)
            .putStringSet(KEY_FILES, seeded)
            .apply()
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

        /**
         * Bump whenever the bundled JSON changes, so an already-seeded device re-imports it. Not
         * the Room schema version — that is separate.
         *
         * 14: photographs moved from JPEG to WebP, so every photoAsset path changed.
         */
        const val CONTENT_VERSION = "14"
        const val KEY_VERSION = "content_version"

        /** File names already seeded, so they are not read and parsed again on later launches. */
        const val KEY_FILES = "seeded_files"
    }
}
