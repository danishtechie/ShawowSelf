package com.shadowself.response

import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.shadowself.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * LocationSnapshotter
 *
 * Captures a single GPS fix at alert time. Uses HIGH_ACCURACY priority
 * to get the most precise location possible within a 5-second timeout.
 * Falls back to BALANCED_POWER if high-accuracy times out.
 *
 * The resulting coordinates are stored encrypted in the IncidentEntity.
 * They are never transmitted off-device in plaintext — the FCM notification
 * contains only a notification ID that the owner's app uses to retrieve
 * the incident from the paired device's local store via end-to-end
 * encrypted sync (implemented in FcmAlertNotifier).
 *
 * Reverse geocoding (lat/lng → address string) is done lazily and
 * cached in the IncidentEntity.addressLine field.
 */
@Singleton
class LocationSnapshotter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG              = "LocationSnapshotter"
        private const val TIMEOUT_MS       = 5_000L
        private const val FALLBACK_TIMEOUT = 3_000L
    }

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    data class LocationSnapshot(
        val latitude:    Double,
        val longitude:   Double,
        val accuracyM:   Float,
        val addressLine: String? = null
    )

    /**
     * Returns a GPS fix or null if unavailable within the timeout.
     * Never throws — location is best-effort for incident logging.
     */
    @Suppress("MissingPermission")
    suspend fun snapshot(): LocationSnapshot? {
        // Try high accuracy first
        val result = withTimeoutOrNull(TIMEOUT_MS) {
            getLocation(Priority.PRIORITY_HIGH_ACCURACY)
        }
        if (result != null) return result

        // Fall back to balanced power (cached last-known location)
        Logger.d(TAG, "High accuracy timed out — falling back to balanced")
        return withTimeoutOrNull(FALLBACK_TIMEOUT) {
            getLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
        }
    }

    @Suppress("MissingPermission")
    private suspend fun getLocation(priority: Int): LocationSnapshot? =
        suspendCancellableCoroutine { cont ->
            val cancel = CancellationTokenSource()
            fusedClient.getCurrentLocation(priority, cancel.token)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        cont.resume(LocationSnapshot(
                            latitude  = loc.latitude,
                            longitude = loc.longitude,
                            accuracyM = loc.accuracy
                        ))
                    } else {
                        cont.resume(null)
                    }
                }
                .addOnFailureListener { e ->
                    Logger.e(TAG, "Location error: ${e.message}")
                    cont.resume(null)
                }
            cont.invokeOnCancellation { cancel.cancel() }
        }

    /**
     * Reverse-geocodes coordinates to a human-readable address.
     * Returns null if geocoder is unavailable or network is down.
     * Never called on the main thread.
     */
    fun reverseGeocode(lat: Double, lng: Double): String? {
        return try {
            @Suppress("DEPRECATION")
            android.location.Geocoder(context, java.util.Locale.getDefault())
                .getFromLocation(lat, lng, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
        } catch (e: Exception) {
            Logger.e(TAG, "Geocode failed: ${e.message}")
            null
        }
    }
}
