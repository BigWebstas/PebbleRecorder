package com.pebblerecorder.app.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.pebblerecorder.app.settings.GeminiPrefs

private const val TAG = "RecordingLocation"

/**
 * Where a recording started, for the transcript header. Captured when the recording starts (not
 * when it stops) and only if Gemini transcription and its location option are both on.
 * Best-effort: no permission or fix just means [current] stays null and the header omits it.
 */
class RecordingLocation(private val context: Context) {
    @Volatile var current: Location? = null
        private set
    private var pendingListener: LocationListener? = null

    /**
     * getLastKnownLocation alone is a stale cache - often null if no other app has requested a
     * fix recently, which is why the tag would sometimes go missing. So a fresh fix is also
     * requested in the background to upgrade [current] before the recording stops, with the
     * cache as the fallback in the meantime.
     */
    fun begin() {
        current = if (GeminiPrefs.isEnabled(context) && GeminiPrefs.isLocationEnabled(context)) {
            requestFreshLocation()
            getBestLastKnownLocation()
        } else {
            null
        }
    }

    fun cancel() {
        context.getSystemService(LocationManager::class.java)?.let { clearPendingRequest(it) }
        current = null
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Best-effort, synchronous last-known location. This is a passive cache - it's often null or
     * stale, which is why [requestFreshLocation] runs alongside it. Returns null if location
     * permission isn't granted or no provider has a cached fix yet.
     */
    private fun getBestLastKnownLocation(): Location? {
        if (!hasPermission()) return null
        val locationManager = context.getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /**
     * Requests one fresh fix in the background and, if it arrives before the recording stops,
     * overwrites [current] with it. Doesn't block recording start. Gives up and stops listening
     * after 15s so a GPS-less environment doesn't leak the listener.
     */
    private fun requestFreshLocation() {
        if (!hasPermission()) return
        val locationManager = context.getSystemService(LocationManager::class.java) ?: return
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return

        clearPendingRequest(locationManager)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                current = location
                clearPendingRequest(locationManager)
            }
        }
        pendingListener = listener
        try {
            locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request a fresh location fix", e)
            pendingListener = null
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({ clearPendingRequest(locationManager) }, 15_000)
    }

    private fun clearPendingRequest(locationManager: LocationManager) {
        pendingListener?.let {
            runCatching { locationManager.removeUpdates(it) }
            pendingListener = null
        }
    }
}
