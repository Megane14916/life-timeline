package com.megane14916.lifetimeline.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megane14916.lifetimeline.domain.UsageEventKind
import com.megane14916.lifetimeline.domain.UsageEventRecord
import com.megane14916.lifetimeline.domain.generateUlid
import com.megane14916.lifetimeline.repository.CollectionInput
import com.megane14916.lifetimeline.repository.CollectionRepository
import com.megane14916.lifetimeline.repository.CollectionRequest
import com.megane14916.lifetimeline.repository.LocalDataRepository
import com.megane14916.lifetimeline.repository.SourceKeyConflictException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LifeTimelineDatabaseTest {
  private lateinit var database: LifeTimelineDatabase
  private lateinit var app: AndroidAppEntity

  @Before
  fun setUp() {
    database =
      Room
        .inMemoryDatabaseBuilder(
          ApplicationProvider.getApplicationContext(),
          LifeTimelineDatabase::class.java,
        ).allowMainThreadQueries()
        .build()
    app =
      AndroidAppEntity(
        id = generateUlid(1_780_000_000_000),
        packageName = "com.example.browser",
        displayName = "Example Browser",
        updatedAtMs = 1_780_000_000_000,
      )
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun returnsPendingSessionsOldestFirstWithHardLimit() =
    runBlocking {
      database.androidAppDao().upsertByPackageName(app)
      val sessions = (0 until 101).map { index -> session(index) }
      sessions.forEach { database.androidAppSessionDao().insertIfAbsent(it) }

      val pending = database.androidAppSessionDao().getPending()

      assertEquals(100, pending.size)
      assertEquals(sessions.sortedWith(compareBy({ it.startedAtMs }, { it.id })).take(100), pending)
    }

  @Test
  fun marksOnlyAcceptedPendingIdsAsSynced() =
    runBlocking {
      database.androidAppDao().upsertByPackageName(app)
      val sessions = listOf(session(1), session(2))
      sessions.forEach { database.androidAppSessionDao().insertIfAbsent(it) }

      val updated =
        database.androidAppSessionDao().markAcceptedAsSynced(
          ids = listOf(sessions[0].id, generateUlid(1_780_000_000_100)),
          syncedAtMs = 1_780_000_000_500,
        )

      assertEquals(1, updated)
      assertEquals(listOf(sessions[1]), database.androidAppSessionDao().getPending())
    }

  @Test
  fun collectionRepositoryReusesSessionIdAndPersistsOpenActivityAcrossCollections() =
    runBlocking {
      val generatedIds =
        listOf(
          generateUlid(1_780_000_003_000),
          generateUlid(1_780_000_003_001),
        ).iterator()
      val repository =
        CollectionRepository(
          database = database,
          idGenerator = { generatedIds.next() },
        )
      val deviceId = generateUlid(1_780_000_003_100)
      val firstRequest =
        CollectionRequest(
          deviceId = deviceId,
          events = listOf(usageEvent(1_780_000_003_200, UsageEventKind.ACTIVITY_RESUMED)),
          cursorAtMs = 1_780_000_003_200,
          cursorKey = "cursor-resume",
          collectedAtMs = 1_780_000_003_400,
        )
      val secondRequest =
        CollectionRequest(
          deviceId = deviceId,
          events = listOf(usageEvent(1_780_000_003_300, UsageEventKind.ACTIVITY_PAUSED)),
          cursorAtMs = 1_780_000_003_300,
          cursorKey = "cursor-pause",
          collectedAtMs = 1_780_000_003_500,
        )
      val replayRequest =
        secondRequest.copy(
          events =
            listOf(
              usageEvent(1_780_000_003_200, UsageEventKind.ACTIVITY_RESUMED),
              usageEvent(1_780_000_003_300, UsageEventKind.ACTIVITY_PAUSED),
            ),
        )

      val first = repository.collectAndSave(firstRequest)
      assertEquals(0, first.insertedSessions)
      assertEquals(1, first.openActivities.size)
      assertEquals(0, database.androidAppSessionDao().countPending())

      val second = repository.collectAndSave(secondRequest)
      val replay = repository.collectAndSave(replayRequest)

      assertEquals(1, second.insertedSessions)
      assertEquals(0, second.reusedSessions)
      assertEquals(0, replay.insertedSessions)
      assertEquals(1, replay.reusedSessions)
      assertEquals(second.sessions.single().id, replay.sessions.single().id)
      assertEquals(1, database.androidAppSessionDao().countPending())
      assertEquals(null, database.openActivityDao().getAll().singleOrNull())
    }

  @Test
  fun collectionTransactionRollsBackAndReusesSameSourceKey() =
    runBlocking {
      val repository = LocalDataRepository(database)
      val first = session(1)
      val input = collectionInput(listOf(first))

      assertEquals(1, repository.saveCollection(input).insertedSessions)
      assertEquals(1, repository.saveCollection(input).reusedSessions)
      assertEquals(1, repository.countPending())

      assertThrows(SourceKeyConflictException::class.java) {
        runBlocking {
          repository.saveCollection(collectionInput(listOf(first.copy(endedAtMs = first.endedAtMs + 1, durationMs = first.durationMs + 1))))
        }
      }
      assertEquals(1, repository.countPending())

      val invalid = session(2).copy(appId = generateUlid(1_780_000_001_000))
      assertThrows(IllegalArgumentException::class.java) {
        runBlocking { repository.saveCollection(collectionInput(listOf(session(3), invalid))) }
      }
      assertEquals(1, repository.countPending())
      assertEquals(null, database.collectorStateDao().find("android_usage_stats_v1"))
    }

  @Test
  fun databaseCanBeClosedAndReopenedWithoutLosingPendingData() =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val name = "reopen-${generateUlid(1_780_000_002_000)}.db"
      val file = context.getDatabasePath(name)
      var fileDatabase =
        Room
          .databaseBuilder(context, LifeTimelineDatabase::class.java, name)
          .allowMainThreadQueries()
          .build()
      fileDatabase.androidAppDao().upsertByPackageName(app)
      fileDatabase.androidAppSessionDao().insertIfAbsent(session(1))
      fileDatabase.close()

      fileDatabase =
        Room
          .databaseBuilder(context, LifeTimelineDatabase::class.java, name)
          .allowMainThreadQueries()
          .build()
      assertEquals(1, fileDatabase.androidAppSessionDao().getPending().size)
      fileDatabase.close()
      file.delete()
      Unit
    }

  private fun collectionInput(sessions: List<AndroidAppSessionEntity>) =
    CollectionInput(
      apps = listOf(app),
      sessions = sessions,
      collectorState =
        CollectorStateEntity(
          collector = "android_usage_stats_v1",
          cursorAtMs = 1_780_000_000_000,
          cursorKey = "cursor",
          lastCollectedAtMs = 1_780_000_000_500,
        ),
      openActivities = emptyList(),
    )

  private fun session(index: Int) =
    AndroidAppSessionEntity(
      id = generateUlid(1_780_000_000_000 + index),
      appId = app.id,
      startedAtMs = 1_780_000_000_000 + index,
      endedAtMs = 1_780_000_000_100 + index,
      durationMs = 100,
      source = "android_usage_stats",
      sourceKey = "source-key-$index",
      syncStatus = "pending",
      collectedAtMs = 1_780_000_000_500,
    )

  private fun usageEvent(
    timestampMs: Long,
    kind: UsageEventKind,
  ) = UsageEventRecord(
    timestampMs = timestampMs,
    packageName = "com.example.browser",
    className = "MainActivity",
    displayName = "Example Browser",
    kind = kind,
    eventKey = "$timestampMs|browser|${kind.ordinal}",
  )
}
