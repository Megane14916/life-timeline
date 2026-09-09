package com.megane14916.lifetimeline.repository

import androidx.room.withTransaction
import com.megane14916.lifetimeline.data.local.AndroidAppEntity
import com.megane14916.lifetimeline.data.local.AndroidAppSessionEntity
import com.megane14916.lifetimeline.data.local.CollectorStateEntity
import com.megane14916.lifetimeline.data.local.LifeTimelineDatabase
import com.megane14916.lifetimeline.data.local.OpenActivityEntity
import com.megane14916.lifetimeline.domain.OpenActivityState
import com.megane14916.lifetimeline.domain.SessionInterval
import com.megane14916.lifetimeline.domain.UsageEventRecord
import com.megane14916.lifetimeline.domain.appSessionSourceKey
import com.megane14916.lifetimeline.domain.generateUlid
import com.megane14916.lifetimeline.session.AppSessionizer
import com.megane14916.lifetimeline.session.SessionizerDiagnostics

data class CollectionRequest(
  val deviceId: String,
  val events: List<UsageEventRecord>,
  val cursorAtMs: Long,
  val cursorKey: String,
  val collectedAtMs: Long,
)

data class CollectionResult(
  val insertedSessions: Int,
  val reusedSessions: Int,
  val sessions: List<AndroidAppSessionEntity>,
  val openActivities: List<OpenActivityEntity>,
  val diagnostics: SessionizerDiagnostics,
)

class CollectionRepository(
  private val database: LifeTimelineDatabase,
  private val sessionizer: AppSessionizer = AppSessionizer(),
  private val idGenerator: () -> String = { generateUlid() },
) {
  suspend fun collectAndSave(request: CollectionRequest): CollectionResult =
    database.withTransaction {
      require(request.deviceId.isNotBlank()) { "deviceId must not be blank." }
      require(request.cursorAtMs >= 0) { "cursorAtMs must be non-negative." }
      require(request.collectedAtMs >= 0) { "collectedAtMs must be non-negative." }

      val collectorStateDao = database.collectorStateDao()
      val existingState = collectorStateDao.find(COLLECTOR_NAME)
      require(
        existingState == null ||
          compareCursor(
            request.cursorAtMs,
            request.cursorKey,
            existingState.cursorAtMs,
            existingState.cursorKey,
          ) >= 0,
      ) {
        "Collection cursor cannot move backwards."
      }

      val sessionizerResult =
        sessionizer.process(
          request.events,
          database.openActivityDao().getAll().map { it.toDomain() },
        )
      val appIdsByPackage = upsertApps(sessionizerResult.sessions, request)
      val persistedSessions = mutableListOf<AndroidAppSessionEntity>()
      var insertedSessions = 0
      var reusedSessions = 0

      sessionizerResult.sessions.forEach { interval ->
        val sourceKey =
          appSessionSourceKey(
            collectorVersion = COLLECTOR_NAME,
            deviceId = request.deviceId,
            packageName = interval.packageName,
            startedAtMs = interval.startedAtMs,
            endedAtMs = interval.endedAtMs,
          )
        val appId = checkNotNull(appIdsByPackage[interval.packageName])
        val existing = database.androidAppSessionDao().findBySourceKey(sourceKey)
        if (existing != null) {
          if (!existing.hasSameSessionContent(interval, appId, sourceKey)) {
            throw SourceKeyConflictException("The source key is already stored with different content.")
          }
          persistedSessions += existing
          reusedSessions += 1
        } else {
          val newSession =
            AndroidAppSessionEntity(
              id = idGenerator(),
              appId = appId,
              startedAtMs = interval.startedAtMs,
              endedAtMs = interval.endedAtMs,
              durationMs = interval.durationMs,
              source = SOURCE_NAME,
              sourceKey = sourceKey,
              syncStatus = PENDING_STATUS,
              collectedAtMs = request.collectedAtMs,
            )
          val existingId = database.androidAppSessionDao().findById(newSession.id)
          if (existingId != null && !existingId.hasSameSessionContent(interval, appId, sourceKey)) {
            throw SourceKeyConflictException("The generated session ID is already stored with different content.")
          }
          database.androidAppSessionDao().insertIfAbsent(newSession)
          persistedSessions += newSession
          insertedSessions += 1
        }
      }

      val openActivities = sessionizerResult.openActivities.map { it.toEntity() }
      collectorStateDao.upsert(
        CollectorStateEntity(
          collector = COLLECTOR_NAME,
          cursorAtMs = request.cursorAtMs,
          cursorKey = request.cursorKey,
          lastCollectedAtMs = request.collectedAtMs,
        ),
      )
      database.openActivityDao().deleteAll()
      database.openActivityDao().insertAll(openActivities)

      CollectionResult(
        insertedSessions = insertedSessions,
        reusedSessions = reusedSessions,
        sessions = persistedSessions,
        openActivities = openActivities,
        diagnostics = sessionizerResult.diagnostics,
      )
    }

  private suspend fun upsertApps(
    sessions: List<SessionInterval>,
    request: CollectionRequest,
  ): Map<String, String> {
    val appDao = database.androidAppDao()
    val appIdsByPackage = mutableMapOf<String, String>()
    sessions
      .map { it.packageName }
      .distinct()
      .sorted()
      .forEach { packageName ->
        val existing = appDao.findByPackageName(packageName)
        val app =
          existing
            ?: AndroidAppEntity(
              id = idGenerator(),
              packageName = packageName,
              displayName = displayNameFor(packageName, request.events),
              updatedAtMs = request.collectedAtMs,
            )
        appIdsByPackage[packageName] = appDao.upsertByPackageName(app).id
      }
    return appIdsByPackage
  }

  private fun displayNameFor(
    packageName: String,
    events: List<UsageEventRecord>,
  ): String =
    events
      .asSequence()
      .filter { it.packageName == packageName }
      .map { it.displayName.trim() }
      .firstOrNull { it.isNotEmpty() }
      ?: packageName

  private fun compareCursor(
    leftAtMs: Long,
    leftKey: String,
    rightAtMs: Long,
    rightKey: String,
  ): Int =
    compareValuesBy(
      leftAtMs,
      rightAtMs,
      { it },
    ).takeIf { it != 0 } ?: leftKey.compareTo(rightKey)

  private companion object {
    const val COLLECTOR_NAME = "android_usage_stats_v1"
    const val SOURCE_NAME = "android_usage_stats"
    const val PENDING_STATUS = "pending"
  }
}

private fun OpenActivityEntity.toDomain(): OpenActivityState =
  OpenActivityState(
    activityKey = activityKey,
    packageName = packageName,
    className = className,
    startedAtMs = startedAtMs,
    startEventKey = startEventKey,
  )

private fun OpenActivityState.toEntity(): OpenActivityEntity =
  OpenActivityEntity(
    activityKey = activityKey,
    packageName = packageName,
    className = className,
    startedAtMs = startedAtMs,
    startEventKey = startEventKey,
  )

private fun AndroidAppSessionEntity.hasSameSessionContent(
  interval: SessionInterval,
  appId: String,
  sourceKey: String,
): Boolean =
  this.appId == appId &&
    startedAtMs == interval.startedAtMs &&
    endedAtMs == interval.endedAtMs &&
    durationMs == interval.durationMs &&
    source == "android_usage_stats" &&
    this.sourceKey == sourceKey
