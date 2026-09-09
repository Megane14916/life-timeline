package com.megane14916.lifetimeline.repository

import androidx.room.withTransaction
import com.megane14916.lifetimeline.data.local.AndroidAppEntity
import com.megane14916.lifetimeline.data.local.AndroidAppSessionEntity
import com.megane14916.lifetimeline.data.local.CollectorStateEntity
import com.megane14916.lifetimeline.data.local.LifeTimelineDatabase
import com.megane14916.lifetimeline.data.local.OpenActivityEntity

data class CollectionInput(
  val apps: List<AndroidAppEntity>,
  val sessions: List<AndroidAppSessionEntity>,
  val collectorState: CollectorStateEntity,
  val openActivities: List<OpenActivityEntity>,
)

data class CollectionSaveResult(
  val insertedSessions: Int,
  val reusedSessions: Int,
)

interface PendingSessionStore {
  suspend fun getPendingSessions(): List<AndroidAppSessionEntity>

  suspend fun markAcceptedAsSynced(
    ids: List<String>,
    syncedAtMs: Long,
  ): Int

  suspend fun countPending(): Int
}

class SourceKeyConflictException(
  message: String,
) : IllegalStateException(message)

class LocalDataRepository(
  private val database: LifeTimelineDatabase,
) : PendingSessionStore {
  suspend fun saveCollection(input: CollectionInput): CollectionSaveResult =
    database.withTransaction {
      val canonicalAppIds =
        input.apps.associate { app ->
          app.id to database.androidAppDao().upsertByPackageName(app).id
        }

      var insertedSessions = 0
      var reusedSessions = 0
      input.sessions.forEach { session ->
        val canonicalAppId =
          canonicalAppIds[session.appId]
            ?: throw IllegalArgumentException("Session references an app outside this collection.")
        val normalizedSession = session.copy(appId = canonicalAppId)
        require(normalizedSession.endedAtMs > normalizedSession.startedAtMs) {
          "Session end must be after its start."
        }
        require(normalizedSession.durationMs == normalizedSession.endedAtMs - normalizedSession.startedAtMs) {
          "Session duration must match its timestamp range."
        }
        require(normalizedSession.syncStatus in setOf("pending", "synced")) {
          "Session sync status must be pending or synced."
        }

        val existingBySource =
          database
            .androidAppSessionDao()
            .findBySourceKey(normalizedSession.sourceKey)
        val existingById = database.androidAppSessionDao().findById(normalizedSession.id)
        val existing = existingBySource ?: existingById
        if (existing == null) {
          database.androidAppSessionDao().insertIfAbsent(normalizedSession)
          insertedSessions += 1
        } else if (existing.hasSameContentAs(normalizedSession)) {
          reusedSessions += 1
        } else {
          throw SourceKeyConflictException(
            "The source key or session ID is already stored with different content.",
          )
        }
      }

      database.collectorStateDao().upsert(input.collectorState)
      database.openActivityDao().deleteAll()
      database.openActivityDao().insertAll(input.openActivities)
      CollectionSaveResult(insertedSessions, reusedSessions)
    }

  override suspend fun getPendingSessions(): List<AndroidAppSessionEntity> = database.androidAppSessionDao().getPending()

  suspend fun getAppsByIds(ids: List<String>): List<AndroidAppEntity> =
    if (ids.isEmpty()) {
      emptyList()
    } else {
      database.androidAppDao().findByIds(ids)
    }

  override suspend fun markAcceptedAsSynced(
    ids: List<String>,
    syncedAtMs: Long,
  ): Int =
    if (ids.isEmpty()) {
      0
    } else {
      database.withTransaction {
        database.androidAppSessionDao().markAcceptedAsSynced(ids, syncedAtMs)
      }
    }

  override suspend fun countPending(): Int = database.androidAppSessionDao().countPending()
}

private fun AndroidAppSessionEntity.hasSameContentAs(other: AndroidAppSessionEntity): Boolean =
  appId == other.appId &&
    startedAtMs == other.startedAtMs &&
    endedAtMs == other.endedAtMs &&
    durationMs == other.durationMs &&
    source == other.source &&
    sourceKey == other.sourceKey
