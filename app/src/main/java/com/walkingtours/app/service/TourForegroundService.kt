package com.walkingtours.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.walkingtours.app.MainActivity
import com.walkingtours.app.R
import com.walkingtours.app.ServiceLocator
import com.walkingtours.app.audio.NarrationProgress
import com.walkingtours.app.audio.NarrationState
import com.walkingtours.app.tour.TourSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps a running tour alive and drives the media notification.
 *
 * Two jobs, and they are related. The foreground service is what stops Android freezing the process
 * once the phone goes in a pocket — without it location updates stop and the geofences silently stop
 * firing. The [MediaSession] is what turns that service into something the user can actually
 * control: a Spotify-style persistent notification with play/pause, previous and next stop, a
 * fifteen-second rewind, and a seek bar.
 *
 * The seek bar works because the session publishes a [PlaybackState] carrying the position and
 * playback speed. Android's system media controls interpolate from those, so the bar keeps moving
 * smoothly without us pushing an update ten times a second. Playback state is therefore refreshed on
 * real changes plus a periodic correction, not on every frame.
 *
 * Uses the platform `android.media.session` APIs rather than AndroidX Media3 or androidx.media:
 * they are available from API 21, need no extra dependency, and this app has exactly one player with
 * exactly one queue-less "track" per stop.
 */
class TourForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    private var mediaSession: MediaSession? = null

    /** Notifications are comparatively expensive binder calls, so they are rate-limited. */
    private var lastNotificationAt = 0L

    /** Bundled stop photos decoded for the notification's large icon, keyed by asset path. */
    private val artworkCache = mutableMapOf<String, Bitmap?>()

    private var lastArtworkPath: String? = null
    private var lastArtwork: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        createMediaSession()
    }

    private fun createMediaSession() {
        val session = MediaSession(this, MEDIA_SESSION_TAG).apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = ServiceLocator.session.resumeNarration()
                override fun onPause() = ServiceLocator.session.pauseNarration()
                override fun onStop() = ServiceLocator.session.shutdown()
                override fun onSkipToNext() = ServiceLocator.session.playNextStop()
                override fun onSkipToPrevious() = ServiceLocator.session.playPreviousStop()
                override fun onRewind() = ServiceLocator.session.skipNarrationBy(-SKIP_MS)
                override fun onFastForward() = ServiceLocator.session.skipNarrationBy(SKIP_MS)
                override fun onSeekTo(pos: Long) = ServiceLocator.session.seekNarrationTo(pos)

                // Custom actions are what SystemUI renders as extra buttons in the media control.
                // PlaybackState.ACTION_REWIND alone is not enough: on Android 13+ the system media
                // card draws its buttons from the session's custom actions, so without these the
                // 15-second rewind existed only on the notification's own (older) action row and
                // was invisible in the media player.
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    when (action) {
                        CUSTOM_REWIND -> ServiceLocator.session.skipNarrationBy(-SKIP_MS)
                        CUSTOM_FORWARD -> ServiceLocator.session.skipNarrationBy(SKIP_MS)
                    }
                }
            })
            setSessionActivity(openAppIntent())

            // Note: setMediaButtonReceiver is deprecated and ignored from API 34; the platform
            // routes hardware media keys to the active, playing session instead. Our fallback
            // service intent is still wired up in onStartCommand for devices that do deliver it.

            // Tell the platform this is speech, so volume keys and ducking behave sensibly.
            setPlaybackToLocal(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )

            isActive = true
        }
        mediaSession = session
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground promptly after being started, or the system kills us.
        val state = ServiceLocator.session.state.value
        val progress = ServiceLocator.session.narration.progress.value
        val notification = buildNotification(state, progress)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Declaring the location type is mandatory on Android 14+, where an undeclared type
            // throws at runtime.
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Transport buttons that the notification itself owns. On Android 13+ the system media
        // control uses the MediaSession callbacks instead, but both paths must work.
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                val playing = progress.state == NarrationState.PLAYING ||
                    progress.state == NarrationState.PREPARING
                if (playing) ServiceLocator.session.pauseNarration() else ServiceLocator.session.resumeNarration()
            }

            ACTION_REWIND -> ServiceLocator.session.skipNarrationBy(-SKIP_MS)
            ACTION_FORWARD -> ServiceLocator.session.skipNarrationBy(SKIP_MS)
            ACTION_NEXT -> ServiceLocator.session.playNextStop()
            ACTION_PREVIOUS -> ServiceLocator.session.playPreviousStop()
            ACTION_MEDIA_BUTTON -> handleMediaButton(intent)
        }

        observeJob?.cancel()
        observeJob = scope.launch {
            combine(
                ServiceLocator.session.state,
                ServiceLocator.session.narration.progress,
            ) { tourState, narration -> tourState to narration }
                .collectLatest { (tourState, narration) ->
                    if (!tourState.isRunning) {
                        stopSelf()
                        return@collectLatest
                    }
                    publish(tourState, narration)
                }
        }
        return START_STICKY
    }

    /**
     * The user swiped the app out of Recents. A foreground service outlives that by design, so this
     * is the hook that stops the tour: cut the narration immediately and take the service down.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "Task removed by the user: stopping tour audio")
        ServiceLocator.session.shutdown()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        // Belt and braces: however this service ends, no narration should survive it.
        ServiceLocator.narrationEngine.stop()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ publishing state

    private fun publish(tourState: TourSessionState, narration: NarrationProgress) {
        mediaSession?.let { session ->
            session.setMetadata(buildMetadata(tourState, narration))
            session.setPlaybackState(buildPlaybackState(narration))
        }

        // Rate-limit notification churn; the system media control interpolates between updates.
        val now = System.currentTimeMillis()
        val isStateChange = narration.state != lastPublishedState
        if (!isStateChange && now - lastNotificationAt < NOTIFICATION_THROTTLE_MS) return
        lastNotificationAt = now
        lastPublishedState = narration.state

        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(tourState, narration))
        }.onFailure { Log.w(TAG, "Could not update the tour notification", it) }
    }

    private var lastPublishedState: NarrationState? = null

    private fun buildMetadata(state: TourSessionState, narration: NarrationProgress): MediaMetadata {
        val stop = state.stops.firstOrNull { it.id == narration.stopId }
            ?: state.currentStop
        val duration = narration.durationMs
        return MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, stop?.name ?: "Walking tour")
            .putString(
                MediaMetadata.METADATA_KEY_ARTIST,
                when {
                    stop != null -> "Historic Istanbul \u00b7 stop ${stop.order} of ${state.stops.size}"
                    state.showingOverview -> "Introduction to the walk"
                    else -> "Walking tour"
                },
            )
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "Walking Tours")
            .apply {
                if (duration > 0L) putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                artworkFor(stop?.photoAsset)?.let {
                    putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                }
            }
            .build()
    }

    private fun buildPlaybackState(narration: NarrationProgress): PlaybackState {
        val playbackState = when (narration.state) {
            NarrationState.PLAYING, NarrationState.PREPARING -> PlaybackState.STATE_PLAYING
            NarrationState.PAUSED -> PlaybackState.STATE_PAUSED
            NarrationState.COMPLETED -> PlaybackState.STATE_STOPPED
            NarrationState.UNAVAILABLE -> PlaybackState.STATE_ERROR
            NarrationState.IDLE -> PlaybackState.STATE_NONE
        }
        val speed = if (narration.state == NarrationState.PLAYING) narration.rate else 0f

        return PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_REWIND or
                    PlaybackState.ACTION_FAST_FORWARD or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_STOP,
            )
            .setState(playbackState, narration.positionMs, speed)
            .addCustomAction(
                PlaybackState.CustomAction.Builder(
                    CUSTOM_REWIND,
                    "Rewind 15 seconds",
                    R.drawable.ic_rewind_15,
                ).build(),
            )
            .addCustomAction(
                PlaybackState.CustomAction.Builder(
                    CUSTOM_FORWARD,
                    "Forward 15 seconds",
                    R.drawable.ic_forward_15,
                ).build(),
            )
            .build()
    }

    // ------------------------------------------------------------------ notification

    private fun buildNotification(
        state: TourSessionState,
        narration: NarrationProgress,
    ): Notification {
        val stop = state.stops.firstOrNull { it.id == narration.stopId } ?: state.currentStop

        val title = stop?.name ?: "Walking tour"
        val text = when (narration.state) {
            NarrationState.PLAYING -> "Playing \u00b7 stop ${stop?.order ?: 0} of ${state.stops.size}"
            NarrationState.PREPARING -> "Preparing audio\u2026"
            NarrationState.PAUSED -> "Paused"
            NarrationState.COMPLETED -> "Finished \u00b7 ${state.visitedStopIds.size} of ${state.stops.size} reached"
            NarrationState.UNAVAILABLE -> narration.message ?: "Audio unavailable"
            NarrationState.IDLE -> "${state.visitedStopIds.size} of ${state.stops.size} stops reached"
        }

        val playing = narration.state == NarrationState.PLAYING ||
            narration.state == NarrationState.PREPARING

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_walk_notification)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setDeleteIntent(actionIntent(ACTION_STOP))

        artworkFor(stop?.photoAsset)?.let { builder.setLargeIcon(it) }

        if (narration.durationMs > 0L) {
            builder.setProgress(1000, (narration.fraction * 1000).toInt(), false)
        }

        builder
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_rewind_15),
                    "Rewind 15 seconds",
                    actionIntent(ACTION_REWIND),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this,
                        if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                    ),
                    if (playing) "Pause" else "Play",
                    actionIntent(ACTION_PLAY_PAUSE),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_next),
                    "Next stop",
                    actionIntent(ACTION_NEXT),
                ).build(),
            )

        mediaSession?.let { session ->
            builder.style = Notification.MediaStyle()
                .setMediaSession(session.sessionToken)
                // Rewind, play/pause and next stop in the collapsed view.
                .setShowActionsInCompactView(0, 1, 2)
        }

        return builder.build()
    }

    /**
     * Fallback path for hardware media keys, used if the platform delivers the button to our
     * receiver rather than straight to the session callback.
     */
    @Suppress("DEPRECATION")
    private fun handleMediaButton(intent: Intent?) {
        val event = intent?.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (event.action != android.view.KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> ServiceLocator.session.resumeNarration()
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> ServiceLocator.session.pauseNarration()
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            android.view.KeyEvent.KEYCODE_HEADSETHOOK,
            -> {
                val playing = ServiceLocator.session.narration.progress.value.state == NarrationState.PLAYING
                if (playing) ServiceLocator.session.pauseNarration() else ServiceLocator.session.resumeNarration()
            }

            android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> ServiceLocator.session.playNextStop()
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> ServiceLocator.session.playPreviousStop()
            android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> ServiceLocator.session.skipNarrationBy(-SKIP_MS)
            android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> ServiceLocator.session.skipNarrationBy(SKIP_MS)
            android.view.KeyEvent.KEYCODE_MEDIA_STOP -> ServiceLocator.session.shutdown()
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, TourForegroundService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Decodes a bundled stop photograph once, and only when the stop changes. */
    private fun artworkFor(assetPath: String?): Bitmap? {
        if (assetPath == null) return null
        if (assetPath == lastArtworkPath) return lastArtwork
        val bitmap = artworkCache.getOrPut(assetPath) {
            runCatching {
                assets.open(assetPath).use { stream ->
                    BitmapFactory.decodeStream(stream)?.let { full ->
                        // The notification only needs a thumbnail; keep it small.
                        val size = minOf(full.width, full.height)
                        Bitmap.createBitmap(
                            full,
                            (full.width - size) / 2,
                            (full.height - size) / 2,
                            size,
                            size,
                        ).let { square ->
                            Bitmap.createScaledBitmap(square, ARTWORK_SIZE, ARTWORK_SIZE, true)
                        }
                    }
                }
            }.getOrNull()
        }
        lastArtworkPath = assetPath
        lastArtwork = bitmap
        return bitmap
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Active walking tour",
            // Low importance: this is a persistent status and control surface, not an alert.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Playback controls for the tour you are walking."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "TourService"
        private const val MEDIA_SESSION_TAG = "WalkingTours"
        private const val CHANNEL_ID = "walking_tour_active"
        private const val NOTIFICATION_ID = 4711
        private const val ARTWORK_SIZE = 256
        private const val NOTIFICATION_THROTTLE_MS = 900L

        /** The media notification's rewind and fast-forward step. */
        const val SKIP_MS = 15_000L

        /** Ids for the media control's extra buttons. */
        private const val CUSTOM_REWIND = "com.walkingtours.app.rewind15"
        private const val CUSTOM_FORWARD = "com.walkingtours.app.forward15"

        private const val ACTION_PLAY_PAUSE = "com.walkingtours.app.PLAY_PAUSE"
        private const val ACTION_REWIND = "com.walkingtours.app.REWIND"
        private const val ACTION_FORWARD = "com.walkingtours.app.FORWARD"
        private const val ACTION_NEXT = "com.walkingtours.app.NEXT"
        private const val ACTION_PREVIOUS = "com.walkingtours.app.PREVIOUS"
        private const val ACTION_STOP = "com.walkingtours.app.STOP"
        private const val ACTION_MEDIA_BUTTON = "com.walkingtours.app.MEDIA_BUTTON"

        fun start(context: Context) {
            val intent = Intent(context, TourForegroundService::class.java)
            // The service is only ever started from the UI while a tour begins, so it is always a
            // legitimate foreground start under Android 12+ background-start restrictions.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TourForegroundService::class.java))
        }
    }
}
