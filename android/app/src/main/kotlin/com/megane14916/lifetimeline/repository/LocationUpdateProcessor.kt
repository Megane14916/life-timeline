package com.megane14916.lifetimeline.repository

import com.megane14916.lifetimeline.collector.LocationAccessState
import com.megane14916.lifetimeline.collector.LocationPermissionChecker
import com.megane14916.lifetimeline.data.preferences.AppSettings

/** Revalidates opt-in and OS access around asynchronous receiver delivery before writing to Room. */
class LocationUpdateProcessor(
  private val settingsProvider: suspend () -> AppSettings,
  private val permissionChecker: LocationPermissionChecker,
  private val repository: LocationCollectionRepository,
  private val deviceIdProvider: suspend () -> String,
  private val nowMs: () -> Long = System::currentTimeMillis,
) {
  suspend fun persistBatch(fixes: List<LocationFix>): LocationCollectionResult? {
    if (fixes.isEmpty()) return null
    val initialSettings = settingsProvider()
    if (!mayPersist(initialSettings)) return null

    val deviceId = deviceIdProvider()
    val latestSettings = settingsProvider()
    if (!mayPersist(latestSettings)) return null
    val startedAtMs = latestSettings.locationCollectionStartedAtMs ?: return null
    return repository.persist(
      fixes = fixes,
      deviceId = deviceId,
      collectionStartedAtMs = startedAtMs,
      nowMs = nowMs(),
    )
  }

  private fun mayPersist(settings: AppSettings): Boolean {
    if (!settings.locationCollectionEnabled || settings.locationCollectionStartedAtMs == null) return false
    val access = permissionChecker.currentAccess(collectionEnabled = true)
    return access == LocationAccessState.APPROXIMATE || access == LocationAccessState.PRECISE
  }
}
