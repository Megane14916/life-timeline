package com.megane14916.lifetimeline.collector

import android.location.Location
import com.megane14916.lifetimeline.repository.LocationFix

/** Converts provider output into the privacy-minimized model shared by receiver and foreground capture. */
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
