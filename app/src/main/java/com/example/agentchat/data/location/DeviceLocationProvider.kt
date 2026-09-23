package com.example.agentchat.data.location

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class DeviceCoordinates(val latitude: Double, val longitude: Double)

class DeviceLocationProvider(context: Context) {
    private val appContext = context.applicationContext

    suspend fun current(): DeviceCoordinates? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return@withContext null
        }
        val manager = appContext.getSystemService(LocationManager::class.java) ?: return@withContext null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
        val lastKnown = providers.mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        if (lastKnown != null) return@withContext lastKnown.toCoordinates()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || providers.isEmpty()) return@withContext null

        val provider = providers.first()
        withTimeoutOrNull(6_000L) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                @Suppress("MissingPermission")
                manager.getCurrentLocation(provider, signal, appContext.mainExecutor) { location ->
                    if (continuation.isActive) continuation.resume(location?.toCoordinates())
                }
            }
        }
    }

    private fun Location.toCoordinates() = DeviceCoordinates(latitude, longitude)
}
