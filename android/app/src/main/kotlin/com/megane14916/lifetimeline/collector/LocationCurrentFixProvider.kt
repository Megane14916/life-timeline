package com.megane14916.lifetimeline.collector

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.megane14916.lifetimeline.repository.LocationFix
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface LocationCurrentFixProvider {
  /** Returns a newly derived fix, or null when Play services cannot obtain one within the bounded request. */
  suspend fun getCurrentLocation(): LocationFix?
}

class FusedLocationCurrentFixProvider internal constructor(
  private val permissionChecker: LocationPermissionChecker,
  private val adapter: FusedCurrentLocationAdapter,
) : LocationCurrentFixProvider {
  constructor(
    context: Context,
    permissionChecker: LocationPermissionChecker = LocationPermissionChecker.from(context),
  ) : this(permissionChecker, PlayServicesFusedCurrentLocationAdapter(context))

  @SuppressLint("MissingPermission")
  override suspend fun getCurrentLocation(): LocationFix? {
    val access = permissionChecker.currentAccess(collectionEnabled = true)
    check(access == LocationAccessState.APPROXIMATE || access == LocationAccessState.PRECISE) {
      "Current location is not allowed in state $access."
    }
    return adapter.getCurrentLocation(currentLocationRequest())?.toLocationFix()
  }

  internal fun currentLocationRequest(): CurrentLocationRequest =
    CurrentLocationRequest
      .Builder()
      .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
      .setMaxUpdateAgeMillis(0)
      .setDurationMillis(REQUEST_DURATION_MS)
      .build()

  private companion object {
    const val REQUEST_DURATION_MS = 30_000L
  }
}

internal fun interface FusedCurrentLocationAdapter {
  suspend fun getCurrentLocation(request: CurrentLocationRequest): Location?
}

private class PlayServicesFusedCurrentLocationAdapter(
  context: Context,
) : FusedCurrentLocationAdapter {
  private val fusedLocationClient: FusedLocationProviderClient by lazy {
    LocationServices.getFusedLocationProviderClient(context)
  }

  @SuppressLint("MissingPermission")
  override suspend fun getCurrentLocation(request: CurrentLocationRequest): Location? {
    val cancellationTokenSource = CancellationTokenSource()
    return suspendCancellableCoroutine { continuation ->
      continuation.invokeOnCancellation { cancellationTokenSource.cancel() }
      fusedLocationClient
        .getCurrentLocation(request, cancellationTokenSource.token)
        .addOnCompleteListener { task ->
          if (!continuation.isActive) return@addOnCompleteListener
          if (task.isSuccessful) {
            continuation.resume(task.result)
          } else {
            continuation.resumeWithException(task.exception ?: IllegalStateException("Current location task failed."))
          }
        }
    }
  }
}
