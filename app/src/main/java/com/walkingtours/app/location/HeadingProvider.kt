package com.walkingtours.app.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which way the phone is pointing, in degrees clockwise from north.
 *
 * GPS only reports a course while you are actually moving. On a walking tour you spend most of your
 * time standing still looking at something, so the magnetometer is the only source that answers the
 * question the walker is really asking: "which way do I turn?".
 *
 * Uses the rotation vector sensor where available, which fuses accelerometer, gyroscope and
 * magnetometer, and falls back to the accelerometer plus magnetometer pair on devices without one.
 */
class HeadingProvider(private val context: Context) {

    private val _headingDegrees = MutableStateFlow<Float?>(null)
    val headingDegrees: StateFlow<Float?> = _headingDegrees.asStateFlow()

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private var rotationVector: Sensor? = null
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null

    private var running = false
    private var lastPublishMs = 0L
    private var lastEmitMs = 0L
    private var lastEmitted: Float? = null

    /** Reported by the platform; gates whether the compass is trusted at all. */
    @Volatile
    private var compassAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var haveGravity = false
    private var haveGeomagnetic = false

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    publish()
                }

                Sensor.TYPE_ACCELEROMETER -> {
                    // Low-pass filter: gravity is the slow component of the accelerometer signal.
                    for (i in 0..2) gravity[i] = gravity[i] * 0.9f + event.values[i] * 0.1f
                    haveGravity = true
                    if (haveGeomagnetic) publishFromRawSensors()
                }

                Sensor.TYPE_MAGNETIC_FIELD -> {
                    System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                    haveGeomagnetic = true
                    if (haveGravity) publishFromRawSensors()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // Track how much the compass can be trusted. An uncalibrated magnetometer is the usual
            // reason a heading cone spins or twitches, and it is better to leave the cone where it
            // was than to follow a reading the device itself is calling unreliable.
            if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) compassAccuracy = accuracy
        }
    }

    fun start() {
        val manager = sensorManager ?: return
        if (running) return

        rotationVector = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        var registered = false
        rotationVector?.let {
            registered = manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) || registered
        }
        if (rotationVector == null && accelerometer != null && magnetometer != null) {
            registered = manager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI) || registered
            registered = manager.registerListener(listener, magnetometer, SensorManager.SENSOR_DELAY_UI) || registered
        } else if (rotationVector != null && accelerometer != null && magnetometer != null) {
            // Also subscribe to the raw pair: some devices fall back to it while the fused sensor
            // warms up, and it costs almost nothing.
            manager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
            manager.registerListener(listener, magnetometer, SensorManager.SENSOR_DELAY_UI)
        }

        running = registered
        _isAvailable.value = registered
        if (!registered) Log.i(TAG, "No usable orientation sensor on this device")
    }

    fun stop() {
        if (!running) return
        sensorManager?.unregisterListener(listener)
        running = false
        _headingDegrees.value = null
    }

    private fun publishFromRawSensors() {
        if (!SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) return
        publish()
    }

    private fun publish() {
        SensorManager.getOrientation(rotationMatrix, orientation)
        val raw = ((Math.toDegrees(orientation[0].toDouble()).toFloat()) + 360f) % 360f

        // Time-based one-pole low pass rather than a fixed per-sample factor. The sensor fires at a
        // rate we do not control, so a fixed factor would filter differently on different devices.
        // A time constant of roughly half a second still turns when the walker turns, but ignores
        // the several-degree jitter of a phone magnetometer.
        val now = SystemClock.elapsedRealtime()
        val dtSeconds = if (lastPublishMs == 0L) 0f else ((now - lastPublishMs) / 1000f).coerceIn(0f, 0.5f)
        lastPublishMs = now

        val previous = _headingDegrees.value
        // Filter harder the less the platform trusts the magnetometer. Suppressing the heading
        // entirely was tempting, but an unreliable compass is common and momentary — indoors, near
        // metal, before the phone has been calibrated — and a cone that blinks in and out is worse
        // than one that turns slowly. Smoothing is what fixes the jitter; hiding is not.
        val timeConstant = if (compassAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) {
            TIME_CONSTANT_S
        } else {
            POOR_ACCURACY_TIME_CONSTANT_S
        }
        val alpha = if (dtSeconds <= 0f) 1f else 1f - kotlin.math.exp(-dtSeconds / timeConstant)
        val smoothed = if (previous == null) raw else shortestAngleLerp(previous, raw, alpha)

        // Only emit when the needle has actually moved a visible amount, so the map is not
        // invalidated sixty times a second to redraw an identical cone.
        val emitted = lastEmitted
        if (emitted == null || angularDelta(emitted, smoothed) >= MIN_CHANGE_DEGREES) {
            // ...and at most every MIN_EMIT_INTERVAL_MS. A phone held still still jitters by more
            // than a degree, so the change test alone lets through ten-odd updates a second — and
            // every update recomposes whatever draws the cone. On the tour page that is the whole
            // screen, measured on a Galaxy S23 at ~10 recompositions a second with the phone
            // sitting on a table. The cone glides to each new value over HEADING_GLIDE_MS (700),
            // far slower than this interval, so the slower feed is invisible.
            if (emitted != null && now - lastEmitMs < MIN_EMIT_INTERVAL_MS) return
            lastEmitMs = now
            lastEmitted = smoothed
            _headingDegrees.value = smoothed
        }
    }

    /** Smallest absolute angle between two bearings, 0..180. */
    private fun angularDelta(a: Float, b: Float): Float {
        var delta = (b - a) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return kotlin.math.abs(delta)
    }

    /** Interpolates the short way around the circle, so 350° to 10° goes forwards, not backwards. */
    private fun shortestAngleLerp(from: Float, to: Float, amount: Float): Float {
        var delta = (to - from) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return ((from + delta * amount) + 360f) % 360f
    }

    private companion object {
        const val TAG = "HeadingProvider"

        /** One-pole low-pass time constant. Larger is calmer and laggier. */
        const val TIME_CONSTANT_S = 0.55f

        /** ...and the same when the magnetometer says it is not well calibrated. */
        const val POOR_ACCURACY_TIME_CONSTANT_S = 1.5f

        /** Do not wake the map for a change smaller than this. */
        const val MIN_CHANGE_DEGREES = 1.0f

        /** Do not wake it more often than this, however much the magnetometer jitters. */
        const val MIN_EMIT_INTERVAL_MS = 200L
    }
}
