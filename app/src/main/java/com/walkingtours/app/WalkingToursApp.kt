package com.walkingtours.app

import android.app.Application
import android.util.Log
import com.google.android.gms.maps.MapsInitializer
import org.osmdroid.config.Configuration

/**
 * Application entry point.
 *
 * osmdroid needs a User-Agent before it will talk to the OpenStreetMap tile servers, and it wants
 * to cache tiles somewhere. We point the tile cache at app-private storage so the app never needs
 * a storage permission, and we enable a generous disk cache so a downloaded city keeps working
 * with no connection.
 */
class WalkingToursApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // osmdroid stores its own settings; a private preferences file is enough, and it avoids the
        // deprecated android.preference API.
        val prefs = getSharedPreferences("osmdroid", MODE_PRIVATE)
        Configuration.getInstance().load(this, prefs)
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().osmdroidBasePath = cacheDir.resolve("osmdroid")
        Configuration.getInstance().osmdroidTileCache = cacheDir.resolve("osmdroid/tiles")

        initialiseGoogleMapsRenderer()

        ServiceLocator.init(this)
    }

    /**
     * Selects the new Maps renderer, which has to happen before any map is created.
     *
     * The legacy renderer — the default, and the one the emulator logs as
     * "maps renderer version(legacy)" — still links against `org.apache.http`. Android removed that
     * library from the platform for apps targeting API 28 and above, and this app targets 37, so
     * merely creating a map with a valid key crashed the process with:
     *
     *     NoClassDefFoundError: Failed resolution of: Lorg/apache/http/ProtocolVersion;
     *
     * The newer renderer has no such dependency. Only initialised when a key is present: with no key
     * the app draws its OpenStreetMap map and has no reason to touch Google Play services at all.
     */
    private fun initialiseGoogleMapsRenderer() {
        if (BuildConfig.GOOGLE_MAPS_API_KEY.isBlank()) return
        runCatching {
            MapsInitializer.initialize(this, MapsInitializer.Renderer.LATEST) { renderer ->
                modernMapsRendererAvailable = renderer == MapsInitializer.Renderer.LATEST
                Log.i(
                    TAG,
                    "Maps renderer loaded: $renderer " +
                        if (modernMapsRendererAvailable) {
                            "(Google Maps available)"
                        } else {
                            "(legacy renderer only - using OpenStreetMap instead)"
                        },
                )
            }
        }
    }

    companion object {
        private const val TAG = "WalkingToursApp"

        /**
         * Whether the Maps SDK loaded its modern renderer.
         *
         * Only the modern renderer can be used on this app's targetSdk. Where Play Services offers
         * only the legacy renderer it falls back silently, and the first map then crashes the
         * process with `NoClassDefFoundError: org.apache.http.ProtocolVersion` from inside the Maps
         * dynamite module — a classloader this app cannot reach, so `org.apache.http.legacy` and
         * `useLibrary` do not help. Rather than bundle a deprecated Apache HTTP client to satisfy it,
         * the map screens check this flag and draw the OpenStreetMap map instead.
         *
         * Defaults to false: not yet knowing is treated as "cannot use Google Maps", which is the
         * safe direction.
         */
        @Volatile
        var modernMapsRendererAvailable: Boolean = false
            private set
    }
}
