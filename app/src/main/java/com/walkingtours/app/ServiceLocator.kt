package com.walkingtours.app

import android.content.Context
import com.walkingtours.app.ai.AiSettings
import com.walkingtours.app.ai.GeminiClient
import com.walkingtours.app.ai.GoogleCloudTtsClient
import com.walkingtours.app.ai.TravelChatController
import com.walkingtours.app.audio.NarrationRouter
import com.walkingtours.app.data.ContentSeeder
import com.walkingtours.app.data.TourRepository
import com.walkingtours.app.data.db.WalkingToursDatabase
import com.walkingtours.app.location.HeadingProvider
import com.walkingtours.app.location.LocationTracker
import com.walkingtours.app.tour.TourSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Minimal hand-rolled dependency holder.
 *
 * The app is deliberately free of a DI framework: a single process-wide container is plenty for an
 * MVP and keeps the build simple. Callers only ever ask for interfaces, so swapping this for
 * Hilt or Koin later is mechanical.
 */
object ServiceLocator {

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun init(context: Context) {
        appContext = context.applicationContext

        // Keep the narration engine in step with the user's voice choice, so switching between the
        // free on-device voice and the Google Cloud voice takes effect immediately.
        scope.launch {
            aiSettings.state.collect { narrationEngine.refreshFromSettings() }
        }
    }

    val context: Context get() = appContext

    private val database: WalkingToursDatabase by lazy { WalkingToursDatabase.build(appContext) }

    val repository: TourRepository by lazy {
        val dao = database.tourDao()
        TourRepository(dao, ContentSeeder(appContext, dao))
    }

    val aiSettings: AiSettings by lazy { AiSettings(appContext) }

    val cloudTtsClient: GoogleCloudTtsClient by lazy {
        GoogleCloudTtsClient(appContext) { aiSettings.current.ttsApiKey }
    }

    val geminiClient: GeminiClient by lazy {
        GeminiClient(appContext) { aiSettings.current.geminiApiKey }
    }

    /**
     * One narration engine for the whole process. It forwards to whichever implementation the
     * settings select, so screens and the tour session never have to re-subscribe mid-walk.
     */
    val narrationEngine: NarrationRouter by lazy {
        NarrationRouter(appContext, aiSettings, cloudTtsClient)
    }

    val locationTracker: LocationTracker by lazy { LocationTracker(appContext) }

    /** Compass, used for the Google-Maps-style heading cone on the map. */
    val headingProvider: HeadingProvider by lazy { HeadingProvider(appContext) }

    val session: TourSessionManager by lazy {
        TourSessionManager(appContext, repository, locationTracker, narrationEngine, aiSettings)
    }

    val chat: TravelChatController by lazy {
        TravelChatController(repository, geminiClient, aiSettings, narrationEngine)
    }
}
