package com.megane14916.lifetimeline.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import com.google.android.gms.location.LocationResult
import com.megane14916.lifetimeline.LifeTimelineApplication
import com.megane14916.lifetimeline.repository.LocationFix
import com.megane14916.lifetimeline.repository.LocationUpdateProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Persists Fused batches locally only; all network work stays outside this short receiver lifetime. */
class LocationUpdatesReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent?,
  ) {
    if (intent?.action != "${context.packageName}.LOCATION_UPDATES") return
    val appContainer = (context.applicationContext as? LifeTimelineApplication)?.appContainer
    if (appContainer == null) return

    val pendingResult = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      try {
        withTimeout(RECEIVER_TIMEOUT_MS) {
          handleLocationUpdateIntent(
            packageName = context.packageName,
            intent = intent,
            processor = appContainer.locationUpdateProcessor,
          ) {
            appContainer.backgroundWorkScheduler.enqueueLocationSync()
          }
        }
      } catch (_: Throwable) {
        // No coordinates or provider payload are logged. A later Fused batch can safely retry these fixes.
      } finally {
        pendingResult.finish()
      }
    }
  }

  private companion object {
    const val RECEIVER_TIMEOUT_MS = 25_000L
  }
}

internal suspend fun handleLocationUpdateIntent(
  packageName: String,
  intent: Intent?,
  processor: LocationUpdateProcessor,
  enqueueLocationSync: () -> Unit,
): Int {
  if (intent?.action != "$packageName.LOCATION_UPDATES") return 0
  val fixes =
    LocationResult
      .extractResult(intent)
      ?.locations
      .orEmpty()
      .map(Location::toLocationFix)
  if (fixes.isEmpty()) return 0

  val insertedCount = processor.persistBatch(fixes)?.insertedCount ?: 0
  if (insertedCount > 0) enqueueLocationSync()
  return insertedCount
}

internal fun Location.toLocationFix(): LocationFix =
  LocationFix(
    recordedAtMs = time,
    latitude = latitude,
    longitude = longitude,
    accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
    altitudeM = if (hasAltitude()) altitude else null,
    speedMps = if (hasSpeed()) speed.toDouble() else null,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
  )
