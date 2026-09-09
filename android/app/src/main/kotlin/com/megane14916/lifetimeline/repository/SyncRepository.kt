package com.megane14916.lifetimeline.repository

import com.megane14916.lifetimeline.data.local.AndroidAppSessionEntity
import com.megane14916.lifetimeline.data.remote.SyncApi
import com.megane14916.lifetimeline.data.remote.SyncAppDto
import com.megane14916.lifetimeline.data.remote.SyncAppSessionDto
import com.megane14916.lifetimeline.data.remote.SyncAppSessionsRequest
import com.megane14916.lifetimeline.data.remote.SyncAppSessionsResponse
import com.megane14916.lifetimeline.data.remote.SyncDeviceDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import java.io.IOException
import javax.net.ssl.SSLException

enum class SyncFailureKind {
  NETWORK,
  SERVER,
  PROTOCOL,
}

data class SyncFailure(
  val kind: SyncFailureKind,
  val message: String,
  val httpStatus: Int? = null,
)

enum class SyncRunStatus {
  SUCCESS,
  NO_PENDING,
  PARTIAL_SUCCESS,
  FAILED,
}

data class SyncResult(
  val status: SyncRunStatus,
  val batchesSucceeded: Int,
  val sessionsSynced: Int,
  val pendingCount: Int,
  val failure: SyncFailure? = null,
)

class SyncRepository(
  private val pendingStore: PendingSessionStore,
  private val appProvider: suspend (Set<String>) -> List<SyncAppDto>,
  private val syncApi: SyncApi,
  private val device: SyncDeviceDto,
  private val nowMs: () -> Long = { System.currentTimeMillis() },
  private val mutex: Mutex = Mutex(),
) {
  suspend fun syncAll(): SyncResult = mutex.withLock { syncAllLocked() }

  private suspend fun syncAllLocked(): SyncResult {
    var batchesSucceeded = 0
    var sessionsSynced = 0

    while (true) {
      val pending = pendingStore.getPendingSessions()
      if (pending.isEmpty()) {
        return SyncResult(
          status = if (batchesSucceeded == 0) SyncRunStatus.NO_PENDING else SyncRunStatus.SUCCESS,
          batchesSucceeded = batchesSucceeded,
          sessionsSynced = sessionsSynced,
          pendingCount = 0,
        )
      }

      val request =
        try {
          buildRequest(pending)
        } catch (failure: SyncRepositoryException) {
          return failureResult(batchesSucceeded, sessionsSynced, failure.failure)
        }
      val response =
        try {
          syncApi.syncAppSessions(request)
        } catch (_: SSLException) {
          return failureResult(
            batchesSucceeded,
            sessionsSynced,
            SyncFailure(SyncFailureKind.NETWORK, NETWORK_ERROR_MESSAGE),
          )
        } catch (_: IOException) {
          return failureResult(
            batchesSucceeded,
            sessionsSynced,
            SyncFailure(SyncFailureKind.NETWORK, NETWORK_ERROR_MESSAGE),
          )
        } catch (_: RuntimeException) {
          return failureResult(
            batchesSucceeded,
            sessionsSynced,
            SyncFailure(SyncFailureKind.NETWORK, NETWORK_ERROR_MESSAGE),
          )
        }

      val accepted =
        try {
          validateResponse(response, request.sessions.map { it.id }.toSet())
        } catch (failure: SyncRepositoryException) {
          return failureResult(batchesSucceeded, sessionsSynced, failure.failure)
        } ?: return failureResult(
          batchesSucceeded,
          sessionsSynced,
          SyncFailure(SyncFailureKind.PROTOCOL, PROTOCOL_ERROR_MESSAGE),
        )
      val updated = pendingStore.markAcceptedAsSynced(accepted, nowMs())
      if (updated != accepted.size) {
        return failureResult(
          batchesSucceeded,
          sessionsSynced,
          SyncFailure(SyncFailureKind.PROTOCOL, PROTOCOL_ERROR_MESSAGE),
        )
      }
      batchesSucceeded += 1
      sessionsSynced += accepted.size
    }
  }

  private suspend fun buildRequest(sessions: List<AndroidAppSessionEntity>): SyncAppSessionsRequest {
    val appIds = sessions.map { it.appId }.toSet()
    val apps = appProvider(appIds)
    if (apps.map { it.id }.toSet() != appIds || apps.size != appIds.size) {
      throw SyncRepositoryException(
        SyncFailure(SyncFailureKind.PROTOCOL, "同期対象のアプリ情報を解決できません。"),
      )
    }
    return SyncAppSessionsRequest(
      schemaVersion = SCHEMA_VERSION,
      device = device,
      apps = apps,
      sessions =
        sessions.map { session ->
          SyncAppSessionDto(
            id = session.id,
            appId = session.appId,
            startedAtMs = session.startedAtMs,
            endedAtMs = session.endedAtMs,
            durationMs = session.durationMs,
            source = session.source,
          )
        },
    )
  }

  private fun validateResponse(
    response: Response<SyncAppSessionsResponse>,
    requestedIds: Set<String>,
  ): List<String>? {
    if (!response.isSuccessful) {
      throw SyncRepositoryException(serverFailure(response.code()))
    }
    val body = response.body() ?: return null
    if (body.schemaVersion != SCHEMA_VERSION) return null
    val accepted = body.accepted
    if (accepted.size != accepted.toSet().size || accepted.toSet() != requestedIds) return null
    return accepted
  }

  private suspend fun failureResult(
    batchesSucceeded: Int,
    sessionsSynced: Int,
    failure: SyncFailure,
  ): SyncResult {
    val pendingCount =
      try {
        pendingStore.countPending()
      } catch (_: RuntimeException) {
        0
      }
    return SyncResult(
      status = if (batchesSucceeded == 0) SyncRunStatus.FAILED else SyncRunStatus.PARTIAL_SUCCESS,
      batchesSucceeded = batchesSucceeded,
      sessionsSynced = sessionsSynced,
      pendingCount = pendingCount,
      failure = failure,
    )
  }

  private fun serverFailure(statusCode: Int): SyncFailure =
    SyncFailure(
      kind = SyncFailureKind.SERVER,
      message =
        when (statusCode) {
          422 -> "リクエストを確認してください。"
          409 -> "既存データと競合しました。"
          503 -> "PCが一時的に利用できません。"
          else -> "PCで同期処理に失敗しました。"
        },
      httpStatus = statusCode,
    )

  private class SyncRepositoryException(
    val failure: SyncFailure,
  ) : IllegalStateException(failure.message)

  private companion object {
    const val SCHEMA_VERSION = 1
    const val NETWORK_ERROR_MESSAGE = "PCへ接続できません。URL、Tailscale、PCの状態を確認してください。"
    const val PROTOCOL_ERROR_MESSAGE = "PCから不正な同期応答を受け取りました。"
  }
}
