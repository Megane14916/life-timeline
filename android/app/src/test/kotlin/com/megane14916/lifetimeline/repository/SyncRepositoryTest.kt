package com.megane14916.lifetimeline.repository

import com.megane14916.lifetimeline.data.local.AndroidAppSessionEntity
import com.megane14916.lifetimeline.data.remote.SyncApi
import com.megane14916.lifetimeline.data.remote.SyncAppDto
import com.megane14916.lifetimeline.data.remote.SyncAppSessionsResponse
import com.megane14916.lifetimeline.data.remote.SyncDeviceDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class SyncRepositoryTest {
  @Test
  fun sendsPendingSessionsInOrderedBatchesAndMarksEachAcceptedBatch() =
    kotlinx.coroutines.runBlocking {
      val store = FakePendingSessionStore((0 until 101).map(::session))
      val api =
        RecordingSyncApi { request ->
          Response.success(
            SyncAppSessionsResponse(
              schemaVersion = 1,
              accepted = request.sessions.map { it.id },
            ),
          )
        }
      val repository = repository(store, api)

      val result = repository.syncAll()

      assertEquals(SyncRunStatus.SUCCESS, result.status)
      assertEquals(2, result.batchesSucceeded)
      assertEquals(101, result.sessionsSynced)
      assertEquals(0, result.pendingCount)
      assertEquals(listOf(100, 1), api.requests.map { it.sessions.size })
      assertEquals(
        (0 until 101).map { "session-$it" },
        api.requests.flatMap { it.sessions }.map { it.id },
      )
    }

  @Test
  fun rejectsPartialUnknownAndDuplicateAcknowledgementsWithoutChangingPending() =
    kotlinx.coroutines.runBlocking {
      val store = FakePendingSessionStore(listOf(session(1), session(2)))
      val api =
        RecordingSyncApi {
          Response.success(
            SyncAppSessionsResponse(
              schemaVersion = 1,
              accepted = listOf("session-1", "unknown", "session-1"),
            ),
          )
        }

      val result = repository(store, api).syncAll()

      assertEquals(SyncRunStatus.FAILED, result.status)
      assertEquals(SyncFailureKind.PROTOCOL, result.failure?.kind)
      assertEquals(2, result.pendingCount)
      assertTrue(store.markedIds.isEmpty())
    }

  @Test
  fun classifiesNetworkFailureWithoutDeletingPendingSessions() =
    kotlinx.coroutines.runBlocking {
      val store = FakePendingSessionStore(listOf(session(1)))
      val api = RecordingSyncApi { throw IOException("test network failure") }

      val result = repository(store, api).syncAll()

      assertEquals(SyncRunStatus.FAILED, result.status)
      assertEquals(SyncFailureKind.NETWORK, result.failure?.kind)
      assertEquals(1, result.pendingCount)
      assertTrue(store.markedIds.isEmpty())
    }

  @Test
  fun classifiesUnexpectedApiRuntimeFailureAsProtocolFailure() =
    kotlinx.coroutines.runBlocking {
      val store = FakePendingSessionStore(listOf(session(1)))
      val api = RecordingSyncApi { throw IllegalArgumentException("malformed response") }

      val result = repository(store, api).syncAll()

      assertEquals(SyncFailureKind.PROTOCOL, result.failure?.kind)
      assertEquals(1, result.pendingCount)
      assertTrue(store.markedIds.isEmpty())
    }

  @Test
  fun stopsAfterConfiguredBatchLimitAndLeavesRemainingSessionsPending() =
    kotlinx.coroutines.runBlocking {
      val store = FakePendingSessionStore((0 until 201).map(::session))
      val api =
        RecordingSyncApi { request ->
          Response.success(
            SyncAppSessionsResponse(
              schemaVersion = 1,
              accepted = request.sessions.map { it.id },
            ),
          )
        }

      val result = repository(store, api).syncAll(maxBatches = 1)

      assertEquals(SyncRunStatus.RETRY_LIMIT_REACHED, result.status)
      assertEquals(1, result.batchesSucceeded)
      assertEquals(100, result.sessionsSynced)
      assertEquals(101, result.pendingCount)
      assertEquals(100, store.markedIds.size)
      assertEquals(listOf(100), api.requests.map { it.sessions.size })
    }

  @Test
  fun stopsWhenLeaseHeartbeatIsLostAfterAcceptedBatch() =
    kotlinx.coroutines.runBlocking {
      val store = FakePendingSessionStore((0 until 101).map(::session))
      val api =
        RecordingSyncApi { request ->
          Response.success(
            SyncAppSessionsResponse(
              schemaVersion = 1,
              accepted = request.sessions.map { it.id },
            ),
          )
        }

      val result = repository(store, api).syncAll(onBatchCompleted = { false })

      assertEquals(SyncRunStatus.LEASE_LOST, result.status)
      assertEquals(1, result.batchesSucceeded)
      assertEquals(1, result.pendingCount)
      assertEquals(100, store.markedIds.size)
      assertEquals(listOf(100), api.requests.map { it.sessions.size })
    }

  private fun repository(
    store: FakePendingSessionStore,
    api: SyncApi,
  ) = SyncRepository(
    pendingStore = store,
    appProvider = { set ->
      assertEquals(setOf("app-1"), set)
      listOf(SyncAppDto("app-1", "com.example.app", "Example App"))
    },
    syncApi = api,
    device = SyncDeviceDto("device-1", "Test Device", "android"),
    nowMs = { 1_780_000_000_000 },
  )

  private fun session(index: Int) =
    AndroidAppSessionEntity(
      id = "session-$index",
      appId = "app-1",
      startedAtMs = 1_780_000_000_000L + index,
      endedAtMs = 1_780_000_000_100L + index,
      durationMs = 100,
      source = "android_usage_stats",
      sourceKey = "source-$index",
      syncStatus = "pending",
      collectedAtMs = 1_780_000_000_500,
    )
}

private class FakePendingSessionStore(
  initialSessions: List<AndroidAppSessionEntity>,
) : PendingSessionStore {
  private val pending = initialSessions.toMutableList()
  val markedIds = mutableListOf<String>()

  override suspend fun getPendingSessions(): List<AndroidAppSessionEntity> = pending.toList()

  override suspend fun markAcceptedAsSynced(
    ids: List<String>,
    syncedAtMs: Long,
  ): Int {
    markedIds += ids
    pending.removeAll { it.id in ids }
    return ids.size
  }

  override suspend fun countPending(): Int = pending.size
}

private class RecordingSyncApi(
  private val responder: suspend (com.megane14916.lifetimeline.data.remote.SyncAppSessionsRequest) -> Response<SyncAppSessionsResponse>,
) : SyncApi {
  val requests = mutableListOf<com.megane14916.lifetimeline.data.remote.SyncAppSessionsRequest>()

  override suspend fun syncAppSessions(
    request: com.megane14916.lifetimeline.data.remote.SyncAppSessionsRequest,
  ): Response<SyncAppSessionsResponse> {
    requests += request
    return responder(request)
  }
}
