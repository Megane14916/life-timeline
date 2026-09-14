package com.megane14916.lifetimeline.collector

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.megane14916.lifetimeline.location.LocationUpdatesReceiver
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns the stable, package-scoped PendingIntent used by Fused Location across process death. */
interface LocationRegistrationClient {
  suspend fun register()

  suspend fun unregister()
}

class LocationRequestController internal constructor(
  private val context: Context,
  private val permissionChecker: LocationPermissionChecker,
  private val fusedLocationUpdatesAdapter: FusedLocationUpdatesAdapter,
) : LocationRegistrationClient {
  constructor(
    context: Context,
    permissionChecker: LocationPermissionChecker = LocationPermissionChecker.from(context),
  ) : this(context, permissionChecker, PlayServicesFusedLocationUpdatesAdapter(context))

  @SuppressLint("MissingPermission")
  override suspend fun register() {
    val access = permissionChecker.currentAccess(collectionEnabled = true)
    check(access == LocationAccessState.APPROXIMATE || access == LocationAccessState.PRECISE) {
      "Location registration is not allowed in state $access."
    }
    fusedLocationUpdatesAdapter.requestLocationUpdates(locationRequest(), locationPendingIntent(context))
  }

  override suspend fun unregister() {
    fusedLocationUpdatesAdapter.removeLocationUpdates(locationPendingIntent(context))
  }

  internal fun locationRequest(): LocationRequest =
    LocationRequest
      .Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, INTERVAL_MS)
      .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
      .setMaxUpdateDelayMillis(MAX_BATCH_DELAY_MS)
      .setWaitForAccurateLocation(false)
      .build()

  private companion object {
    const val INTERVAL_MS = 5 * 60 * 1000L
    const val MIN_INTERVAL_MS = 5 * 60 * 1000L
    const val MAX_BATCH_DELAY_MS = 15 * 60 * 1000L
  }
}

internal interface FusedLocationUpdatesAdapter {
  suspend fun requestLocationUpdates(
    request: LocationRequest,
    pendingIntent: PendingIntent,
  )

  suspend fun removeLocationUpdates(pendingIntent: PendingIntent)
}

private class PlayServicesFusedLocationUpdatesAdapter(
  context: Context,
) : FusedLocationUpdatesAdapter {
  private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(context) }

  @SuppressLint("MissingPermission")
  override suspend fun requestLocationUpdates(
    request: LocationRequest,
    pendingIntent: PendingIntent,
  ) {
    fusedLocationClient.requestLocationUpdates(request, pendingIntent).awaitCompletion()
  }

  override suspend fun removeLocationUpdates(pendingIntent: PendingIntent) {
    fusedLocationClient.removeLocationUpdates(pendingIntent).awaitCompletion()
  }
}

private const val LOCATION_PENDING_INTENT_REQUEST_CODE = 504

internal fun locationPendingIntent(context: Context): PendingIntent =
  PendingIntent.getBroadcast(
    context,
    LOCATION_PENDING_INTENT_REQUEST_CODE,
    locationUpdatesReceiverIntent(context),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )

internal fun locationUpdatesReceiverIntent(context: Context): Intent =
  Intent(context, LocationUpdatesReceiver::class.java)
    .setAction("${context.packageName}.LOCATION_UPDATES")
    .setData("lifetimeline://${context.packageName}/location-updates/v1".toUri())

private suspend fun com.google.android.gms.tasks.Task<Void>.awaitCompletion() =
  suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
      if (!continuation.isActive) return@addOnCompleteListener
      if (task.isSuccessful) {
        continuation.resume(Unit)
      } else {
        continuation.resumeWithException(task.exception ?: IllegalStateException("Location task failed."))
      }
    }
  }
