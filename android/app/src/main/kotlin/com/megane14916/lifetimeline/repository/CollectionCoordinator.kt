package com.megane14916.lifetimeline.repository

import com.megane14916.lifetimeline.collector.UsageAccessChecker
import com.megane14916.lifetimeline.collector.UsageCollectionStatus
import com.megane14916.lifetimeline.collector.UsageEventsCollector
import com.megane14916.lifetimeline.collector.UsageQueryWindow
import com.megane14916.lifetimeline.data.local.LifeTimelineDatabase
import com.megane14916.lifetimeline.data.preferences.AppPreferences

enum class CollectionRunStatus {
  SUCCESS,
  NO_DATA,
  PERMISSION_DENIED,
  UNAVAILABLE,
}

data class CollectionRunResult(
  val status: CollectionRunStatus,
  val saved: CollectionResult? = null,
)

class CollectionCoordinator(
  private val database: LifeTimelineDatabase,
  private val preferences: AppPreferences,
  private val accessChecker: UsageAccessChecker,
  private val collector: UsageEventsCollector,
  private val collectionRepository: CollectionRepository,
  private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
  suspend fun collect(): CollectionRunResult {
    if (!accessChecker.isUsageAccessGranted()) {
      return CollectionRunResult(CollectionRunStatus.PERMISSION_DENIED)
    }

    val currentTimeMs = nowMs()
    require(currentTimeMs >= 0) { "Collection timestamp must be non-negative." }
    val state = database.collectorStateDao().find(COLLECTOR_NAME)
    val beginAtMs = state?.cursorAtMs ?: (currentTimeMs - INITIAL_LOOKBACK_MS).coerceAtLeast(0)
    if (currentTimeMs <= beginAtMs) {
      preferences.recordCollection(currentTimeMs)
      return CollectionRunResult(CollectionRunStatus.NO_DATA)
    }

    val collected =
      collector.collect(
        UsageQueryWindow(
          beginAtMs = beginAtMs,
          endAtMs = currentTimeMs,
          cursorAtMs = state?.cursorAtMs ?: beginAtMs,
          cursorKey = state?.cursorKey.orEmpty(),
        ),
      )
    return when (collected.status) {
      UsageCollectionStatus.PERMISSION_DENIED -> {
        CollectionRunResult(CollectionRunStatus.PERMISSION_DENIED)
      }

      UsageCollectionStatus.UNAVAILABLE -> {
        CollectionRunResult(CollectionRunStatus.UNAVAILABLE)
      }

      UsageCollectionStatus.NO_DATA -> {
        preferences.recordCollection(currentTimeMs)
        CollectionRunResult(CollectionRunStatus.NO_DATA)
      }

      UsageCollectionStatus.SUCCESS -> {
        val nextCursorAtMs = checkNotNull(collected.nextCursorAtMs)
        val nextCursorKey = checkNotNull(collected.nextCursorKey)
        val saved =
          collectionRepository.collectAndSave(
            CollectionRequest(
              deviceId = preferences.ensureDeviceId(),
              events = collected.events,
              cursorAtMs = nextCursorAtMs,
              cursorKey = nextCursorKey,
              collectedAtMs = currentTimeMs,
            ),
          )
        preferences.recordCollection(currentTimeMs)
        CollectionRunResult(CollectionRunStatus.SUCCESS, saved)
      }
    }
  }

  private companion object {
    const val COLLECTOR_NAME = "android_usage_stats_v1"
    const val INITIAL_LOOKBACK_MS = 7 * 24 * 60 * 60 * 1_000L
  }
}
