package com.megane14916.lifetimeline.repository

import com.megane14916.lifetimeline.data.local.LocationPointEntity
import com.megane14916.lifetimeline.data.remote.LocationSyncContractJson
import com.megane14916.lifetimeline.data.remote.LocationSyncDevice
import com.megane14916.lifetimeline.data.remote.LocationSyncFailureKind
import com.megane14916.lifetimeline.data.remote.LocationSyncLocation
import com.megane14916.lifetimeline.data.remote.LocationSyncPolicy
import com.megane14916.lifetimeline.data.remote.LocationSyncRemoteException
import com.megane14916.lifetimeline.data.remote.LocationSyncRequest
import com.megane14916.lifetimeline.data.remote.LocationSyncUploader
import com.megane14916.lifetimeline.data.remote.validateContract
import com.megane14916.lifetimeline.data.remote.validateFor
import com.megane14916.lifetimeline.worker.LocationWorkPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import java.io.IOException

enum class LocationSyncRunStatus {
  SUCCESS,
  NO_PENDING,
  RETRY_LIMIT_REACHED,
  LEASE_LOST,
  FAILED,
}

data class LocationSyncFailure(
  val kind: LocationSyncFailureKind,
  val retryable: Boolean,
)

data class LocationSyncResult(
  val status: LocationSyncRunStatus,
  val acceptedCount: Int = 0,
  val failure: LocationSyncFailure? = null,
)

/** Uploads bounded pending batches and removes only IDs explicitly accepted by the PC. */
class LocationSyncRepository(
  private val collectionRepository: LocationCollectionRepository,
  private val uploader: LocationSyncUploader,
  private val device: LocationSyncDevice,
  private val nowMs: () -> Long = System::currentTimeMillis,
  private val nanoTime: () -> Long = System::nanoTime,
) {
  suspend fun syncPending(
    maxBatches: Int = LocationWorkPolicy.MAX_BATCHES_PER_RUN,
    deadlineNanos: Long = Long.MAX_VALUE,
    onBatchCompleted: suspend () -> Boolean = { true },
  ): LocationSyncResult {
    require(maxBatches in 1..LocationWorkPolicy.MAX_BATCHES_PER_RUN)
    var acceptedCount = 0
    var completedBatches = 0
    while (completedBatches < maxBatches && nanoTime() < deadlineNanos) {
      val candidates = collectionRepository.pendingBatch(LocationSyncPolicy.MAX_LOCATIONS_PER_BATCH)
      if (candidates.isEmpty()) {
        collectionRepository.cleanupSynced()
        return LocationSyncResult(
          if (completedBatches == 0) LocationSyncRunStatus.NO_PENDING else LocationSyncRunStatus.SUCCESS,
          acceptedCount,
        )
      }

      val request =
        try {
          candidates.toRequest(device).validateContract().also { payload ->
            require(
              LocationSyncContractJson.encodeToString(payload).toByteArray(Charsets.UTF_8).size <= LocationSyncPolicy.MAX_REQUEST_BYTES,
            ) {
              "Location request exceeds the contract byte limit."
            }
          }
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (_: IllegalArgumentException) {
          return protocolFailure(acceptedCount)
        }

      val response =
        try {
          uploader.upload(request)
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (failure: LocationSyncRemoteException) {
          return LocationSyncResult(LocationSyncRunStatus.FAILED, acceptedCount, LocationSyncFailure(failure.kind, failure.retryable))
        } catch (_: IOException) {
          return LocationSyncResult(
            LocationSyncRunStatus.FAILED,
            acceptedCount,
            LocationSyncFailure(LocationSyncFailureKind.NETWORK, retryable = true),
          )
        } catch (_: IllegalArgumentException) {
          return protocolFailure(acceptedCount)
        } catch (_: SerializationException) {
          return protocolFailure(acceptedCount)
        }

      val accepted =
        try {
          response.validateFor(request)
        } catch (_: IllegalArgumentException) {
          return protocolFailure(acceptedCount)
        }
      if (accepted.accepted.isEmpty()) return protocolFailure(acceptedCount)

      try {
        val acknowledgement =
          collectionRepository.acknowledge(
            sentIds = request.locations.map { it.id },
            acceptedIds = accepted.accepted,
            syncedAtMs = nowMs(),
          )
        check(acknowledgement.markedSyncedCount == accepted.accepted.size) {
          "Location ACK no longer matches pending Room rows."
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Throwable) {
        return protocolFailure(acceptedCount)
      }

      acceptedCount += accepted.accepted.size
      completedBatches += 1
      if (!onBatchCompleted()) return LocationSyncResult(LocationSyncRunStatus.LEASE_LOST, acceptedCount)
    }

    return if (collectionRepository.countPending() > 0) {
      LocationSyncResult(LocationSyncRunStatus.RETRY_LIMIT_REACHED, acceptedCount)
    } else {
      LocationSyncResult(LocationSyncRunStatus.SUCCESS, acceptedCount)
    }
  }

  private fun protocolFailure(acceptedCount: Int) =
    LocationSyncResult(
      LocationSyncRunStatus.FAILED,
      acceptedCount,
      LocationSyncFailure(LocationSyncFailureKind.PROTOCOL, retryable = false),
    )

  private fun List<LocationPointEntity>.toRequest(device: LocationSyncDevice) =
    LocationSyncRequest(
      schemaVersion = 1,
      device = device,
      locations =
        map { point ->
          LocationSyncLocation(
            id = point.id,
            recordedAtMs = point.recordedAtMs,
            latitude = point.latitude,
            longitude = point.longitude,
            accuracyM = point.accuracyM,
            altitudeM = point.altitudeM,
            speedMps = point.speedMps,
            source = point.source,
          )
        },
    )
}
