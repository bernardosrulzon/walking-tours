package com.walkingtours.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Location source for the geofences.
 *
 * Uses the platform [LocationManager] rather than Google's fused provider on purpose: it needs no
 * Play Services, costs nothing, and works on every Android device including ones without Google
 * apps. For a walking tour the battery/accuracy trade-off is irrelevant because updates only run
 * while a tour is actually in progress.
 *
 * Note the deliberate settings: requests are frequent (every second) because arrival at a stop
 * should feel instant, and updates are reference counted. The tour holds one lease while it runs,
 * and every screen showing a map holds another, so the blue dot is available while simply browsing
 * a tour — which it was not before, because only the tour ever started the tracker.
 */
class LocationTracker(private val context: Context) {

    private val _lastLocation = MutableStateFlow<Location?>(null)
    val lastLocation: StateFlow<Location?> = _lastLocation.asStateFlow()

    private val _providerIssue = MutableStateFlow<String?>(null)
    val providerIssue: StateFlow<String?> = _providerIssue.asStateFlow()

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var running = false

    /**
     * How many callers currently want updates. Position updates are only unregistered when the last
     * lease is released, so closing a map screen cannot silently stop a tour's geofences.
     */
    private var leases = 0

    private val listener = LocationListener { location -> _lastLocation.value = location }

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    fun isGpsEnabled(): Boolean =
        locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true

    /** Take a lease on position updates. Balanced with [release]. */
    fun acquire() {
        leases++
        if (leases == 1) startUpdates()
    }

    /** Give a lease back. Updates stop only when the last one is returned. */
    fun release() {
        leases = (leases - 1).coerceAtLeast(0)
        if (leases == 0) stopUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        val manager = locationManager ?: run {
            _providerIssue.value = "This device has no location service."
            return
        }
        if (!hasPermission()) {
            _providerIssue.value = "Location permission is needed to trigger stops automatically."
            return
        }
        if (running) return

        var registered = false
        // GPS first for accuracy; fall back to the network provider so the tour still works
        // indoors or where the sky view is blocked by tall buildings.
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!manager.isProviderEnabled(provider) && provider == LocationManager.GPS_PROVIDER) {
                continue
            }
            try {
                manager.requestLocationUpdates(provider, 1_000L, 1f, listener, Looper.getMainLooper())
                registered = true
                manager.getLastKnownLocation(provider)?.let { last ->
                    if (_lastLocation.value == null) _lastLocation.value = last
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Location permission revoked while starting updates", e)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Provider $provider unavailable", e)
            }
        }

        running = registered
        _providerIssue.value = when {
            registered && !isGpsEnabled() -> "GPS is off, using approximate location."
            registered -> null
            else -> "No location provider is available."
        }
    }

    private fun stopUpdates() {
        if (!running) return
        try {
            locationManager?.removeUpdates(listener)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Failed to remove location updates", e)
        }
        running = false
    }

    private companion object {
        const val TAG = "LocationTracker"
    }
}
