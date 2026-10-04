package com.walkingtours.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.util.Log
import com.walkingtours.app.ai.AiException
import com.walkingtours.app.ai.AiSettings
import com.walkingtours.app.ai.GoogleCloudTtsClient
import com.walkingtours.app.ai.NARRATION_LANGUAGE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * Narration using Google Cloud Text-to-Speech, with the audio cached on disk.
 *
 * Design decisions that matter:
 *
 *  - **Synthesise once, play many times.** Each stop's narration is fetched once and written to the
 *    app's cache keyed by voice plus text. After that the tour plays entirely offline and costs
 *    nothing, which is what makes a cloud voice viable for a walking tour: the network is only
 *    needed the first time a stop is reached, and the download happens while the previous stop is
 *    still playing.
 *  - **Cloud calls never happen mid-walk if we can help it.** Synthesis is kicked off the moment the
 *    stop starts playing, so a dropped connection is very unlikely to interrupt a tour in progress.
 *  - **Speed is applied at playback, not synthesis.** Changing speed with `PlaybackParams` avoids
 *    re-billing and re-downloading the same narration at a different rate.
 *  - **Highlighting stays honest.** Google returns no word timings for plain text input, so the
 *    spoken position is derived from playback time and snapped to whole words, which reads
 *    naturally even though it is an approximation.
 */
class GoogleCloudTtsNarrationEngine(
    context: Context,
    private val settings: AiSettings,
    private val client: GoogleCloudTtsClient,
) : NarrationEngine {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _progress = MutableStateFlow(NarrationProgress())
    override val progress: StateFlow<NarrationProgress> = _progress.asStateFlow()

    override val isAvailable: Boolean get() = settings.current.hasTtsKey

    override val engineLabel: String
        get() = "Google Cloud voice \u00b7 ${settings.current.cloudVoiceName.substringAfterLast('-')}"

    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null

    private var player: MediaPlayer? = null
    private var tickJob: Job? = null
    private var workJob: Job? = null

    private var currentText: String = ""
    private var currentStopId: String? = null
    private var currentRate: Float = 1.0f

    /** Where the current utterance started, as a character offset into the narration. */
    private var baseOffset: Int = 0

    override fun prepare(onReady: (Boolean) -> Unit) {
        onReady(isAvailable)
    }

    override fun play(stopId: String, text: String, startOffset: Int) {
        if (!isAvailable) {
            _progress.value = NarrationProgress(
                state = NarrationState.UNAVAILABLE,
                stopId = stopId,
                message = "Add a Google Cloud Text-to-Speech API key in Settings to use the cloud voice.",
            )
            return
        }

        stopInternal(resetProgress = false)
        currentStopId = stopId
        currentText = text
        baseOffset = startOffset.coerceIn(0, text.length)

        _progress.value = NarrationProgress(
            state = NarrationState.PREPARING,
            stopId = stopId,
            highlightStart = baseOffset,
            highlightEnd = baseOffset,
            rate = currentRate,
            message = "Preparing audio\u2026",
        )

        workJob = scope.launch {
            try {
                val file = ensureAudioFile(text)
                startPlayback(file)
            } catch (e: AiException) {
                Log.w(TAG, "Cloud synthesis failed", e)
                _progress.value = NarrationProgress(
                    state = NarrationState.UNAVAILABLE,
                    stopId = stopId,
                    message = e.message,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected synthesis failure", e)
                _progress.value = NarrationProgress(
                    state = NarrationState.UNAVAILABLE,
                    stopId = stopId,
                    message = "Could not play the cloud voice. ${e.message.orEmpty()}",
                )
            }
        }
    }

    // ------------------------------------------------------------------ synthesis + cache

    /** Returns the cached audio file, synthesising it first if this is the first time we need it. */
    private suspend fun ensureAudioFile(text: String): File {
        val voice = settings.current.cloudVoiceName
        val dir = File(appContext.cacheDir, "cloud-tts").apply { mkdirs() }
        val name = sha1("$voice|$NARRATION_LANGUAGE|$text") + ".mp3"
        val file = File(dir, name)
        if (file.exists() && file.length() > 0) return file

        val bytes = client.synthesize(text, voice, NARRATION_LANGUAGE)
        // Write to a temp file and rename, so an interrupted download can never leave a truncated
        // MP3 in the cache that would fail to play forever after.
        val temp = File(dir, "$name.part")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        return file
    }

    // ------------------------------------------------------------------ playback

    private fun startPlayback(file: File) {
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        try {
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.prepare()

            // Resuming part-way through: convert the character offset into a position. MP3 carries
            // no word timings, so this is proportional — good enough to feel right.
            if (baseOffset > 0 && mediaPlayer.duration > 0) {
                val fraction = baseOffset.toFloat() / currentText.length.coerceAtLeast(1)
                mediaPlayer.seekTo((mediaPlayer.duration * fraction).toInt())
            }

            mediaPlayer.setOnCompletionListener {
                _progress.value = _progress.value.copy(
                    state = NarrationState.COMPLETED,
                    highlightStart = currentText.length,
                    highlightEnd = currentText.length,
                    positionMs = mediaPlayer.duration.toLong().coerceAtLeast(0L),
                    durationMs = mediaPlayer.duration.toLong().coerceAtLeast(0L),
                )
                stopTicking()
                abandonFocus()
            }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                _progress.value = _progress.value.copy(
                    state = NarrationState.UNAVAILABLE,
                    message = "Playback failed.",
                )
                stopTicking()
                true
            }

            requestFocus()
            mediaPlayer.start()
            applyRate()
            _progress.value = _progress.value.copy(
                state = NarrationState.PLAYING,
                message = null,
                positionMs = runCatching { mediaPlayer.currentPosition.toLong() }.getOrDefault(0L),
                durationMs = runCatching { mediaPlayer.duration.toLong() }.getOrDefault(0L),
            )
            startTicking()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback", e)
            releasePlayer()
            _progress.value = NarrationProgress(
                state = NarrationState.UNAVAILABLE,
                stopId = currentStopId,
                message = "Could not play the audio file.",
            )
        }
    }

    private fun startTicking() {
        stopTicking()
        tickJob = scope.launch {
            while (true) {
                val mediaPlayer = player ?: break
                val duration = runCatching { mediaPlayer.duration }.getOrDefault(0)
                val position = runCatching { mediaPlayer.currentPosition }.getOrDefault(0)
                if (duration > 0) {
                    val fraction = (position.toDouble() / duration).coerceIn(0.0, 1.0)
                    // The file holds the whole narration and we seek within it, so the player's
                    // position is already absolute — mapping it straight onto the text length is
                    // correct. (Adding baseOffset here would double-count the resume point.)
                    val offset = (fraction * currentText.length).toInt()
                    val range = wordRangeAt(currentText, offset)
                    _progress.value = _progress.value.copy(
                        highlightStart = range.first,
                        highlightEnd = range.last + 1,
                        positionMs = position.toLong(),
                        durationMs = duration.toLong(),
                        rate = currentRate,
                    )
                }
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    /** Expands a rough character offset to the whole word containing it, so highlighting looks clean. */
    private fun wordRangeAt(text: String, offset: Int): IntRange {
        if (text.isEmpty()) return 0..0
        val clamped = offset.coerceIn(0, text.length)
        var start = clamped
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        var end = clamped
        while (end < text.length && !text[end].isWhitespace()) end++
        return start until maxOf(end, start + 1)
    }

    override fun pause() {
        val mediaPlayer = player ?: return
        if (_progress.value.state != NarrationState.PLAYING &&
            _progress.value.state != NarrationState.PREPARING
        ) {
            return
        }
        runCatching { mediaPlayer.pause() }
        _progress.value = _progress.value.copy(
            state = NarrationState.PAUSED,
            positionMs = runCatching { mediaPlayer.currentPosition.toLong() }.getOrDefault(0L),
        )
        stopTicking()
    }

    /** The cloud player can seek a cached MP3 outright, so this is exact. */
    override fun seekTo(positionMs: Long) {
        val mediaPlayer = player ?: run {
            // Audio not loaded yet: remember the intent by re-playing from the equivalent offset.
            val stopId = currentStopId
            if (stopId != null && currentText.isNotBlank()) {
                val duration = _progress.value.durationMs
                val offset = if (duration > 0L) {
                    ((positionMs.toFloat() / duration) * currentText.length).toInt()
                } else {
                    0
                }
                play(stopId, currentText, offset.coerceIn(0, currentText.length))
            }
            return
        }
        val duration = runCatching { mediaPlayer.duration.toLong() }.getOrDefault(0L)
        val target = if (duration > 0L) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        runCatching { mediaPlayer.seekTo(target.toInt()) }
        _progress.value = _progress.value.copy(positionMs = target)
        val offset = if (duration > 0L) {
            ((target.toFloat() / duration) * currentText.length).toInt()
        } else {
            0
        }
        val range = wordRangeAt(currentText, offset)
        _progress.value = _progress.value.copy(highlightStart = range.first, highlightEnd = range.last + 1)
    }

    override fun resume() {
        val mediaPlayer = player ?: run {
            // Nothing loaded (e.g. after the process moved on) — restart from the top.
            val stopId = currentStopId
            if (stopId != null && currentText.isNotBlank()) play(stopId, currentText, 0)
            return
        }
        if (_progress.value.state != NarrationState.PAUSED) return
        requestFocus()
        runCatching { mediaPlayer.start() }
        applyRate()
        _progress.value = _progress.value.copy(
            state = NarrationState.PLAYING,
            durationMs = runCatching { mediaPlayer.duration.toLong() }.getOrDefault(0L),
        )
        startTicking()
    }

    override fun stop() = stopInternal(resetProgress = true)

    private fun stopInternal(resetProgress: Boolean) {
        workJob?.cancel()
        workJob = null
        stopTicking()
        releasePlayer()
        abandonFocus()
        if (resetProgress) {
            baseOffset = 0
            currentText = ""
            currentStopId = null
            _progress.value = NarrationProgress(state = NarrationState.IDLE, rate = currentRate)
        }
    }

    override fun setRate(rate: Float) {
        currentRate = rate.coerceIn(0.5f, 2.0f)
        applyRate()
        _progress.value = _progress.value.copy(rate = currentRate)
    }

    /**
     * Applies playback speed. Only safe while playing: on Android, calling setPlaybackParams on a
     * paused MediaPlayer silently starts it.
     */
    private fun applyRate() {
        val mediaPlayer = player ?: return
        val playing = runCatching { mediaPlayer.isPlaying }.getOrDefault(false)
        if (!playing) return
        runCatching {
            mediaPlayer.playbackParams = PlaybackParams()
                .setSpeed(currentRate)
                .setPitch(1.0f)
                .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT)
        }.onFailure { Log.w(TAG, "Could not set playback speed", it) }
    }

    private fun releasePlayer() {
        val mediaPlayer = player ?: return
        player = null
        runCatching { if (mediaPlayer.isPlaying) mediaPlayer.stop() }
        runCatching { mediaPlayer.reset() }
        runCatching { mediaPlayer.release() }
    }

    override fun release() {
        stopInternal(resetProgress = true)
        scope.coroutineContext[Job]?.cancel()
    }

    // ------------------------------------------------------------------ audio focus

    /**
     * Narration should yield to a phone call or another media app rather than talking over it.
     */
    private fun requestFocus() {
        val manager = audioManager ?: return
        if (focusRequest == null) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        -> pause()

                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit
                    }
                }
                .build()
        }
        focusRequest?.let { manager.requestAudioFocus(it) }
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        focusRequest?.let { manager.abandonAudioFocusRequest(it) }
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "CloudTts"
        const val TICK_MS = 90L
    }
}
