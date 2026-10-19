package com.walkingtours.app

import android.content.Context
import com.walkingtours.app.ai.AiSettings
import com.walkingtours.app.ai.ModelClient
import com.walkingtours.app.ai.GuideController
import com.walkingtours.app.ai.ElevenLabsClient
import com.walkingtours.app.ai.GeminiTtsClient
import com.walkingtours.app.ai.PersonaSettings
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
        // phone's voice, Gemini and ElevenLabs takes effect immediately.
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

    /** The walker's persona: the guide chosen for each tour, and how they asked it to tell it. */
    val personaSettings: PersonaSettings by lazy { PersonaSettings(appContext) }

    val geminiTtsClient: GeminiTtsClient by lazy {
        GeminiTtsClient(appContext) { aiSettings.current.geminiApiKey }
    }

    val elevenLabsClient: ElevenLabsClient by lazy {
        ElevenLabsClient { aiSettings.current.elevenLabsApiKey }
    }

    /** Text models (guides, rewrites, detours, chat), via OpenCode Go by default. */
    val modelClient: ModelClient by lazy { ModelClient(appContext, aiSettings) }

    /**
     * The guide personas and the personalised narration they produce. App-scoped so a rewrite keeps
     * running while the walker moves between screens, and so the cache outlives any one screen.
     */
    val guide: GuideController by lazy {
        GuideController(appContext, repository, modelClient, aiSettings, personaSettings)
    }

    /**
     * One narration engine for the whole process. It forwards to whichever implementation the
     * settings select, so screens and the tour session never have to re-subscribe mid-walk.
     */
    val narrationEngine: NarrationRouter by lazy {
        NarrationRouter(appContext, aiSettings, geminiTtsClient, elevenLabsClient)
    }

    val locationTracker: LocationTracker by lazy { LocationTracker(appContext) }

    /** Compass, used for the Google-Maps-style heading cone on the map. */
    val headingProvider: HeadingProvider by lazy { HeadingProvider(appContext) }

    val session: TourSessionManager by lazy {
        TourSessionManager(appContext, repository, locationTracker, narrationEngine, aiSettings, guide)
    }

    val chat: TravelChatController by lazy {
        TravelChatController(repository, modelClient, aiSettings, guide)
    }
}
