package com.megane14916.lifetimeline.repository

import androidx.room.withTransaction
import com.megane14916.lifetimeline.data.local.AndroidMediaItemEntity
import com.megane14916.lifetimeline.data.local.LifeTimelineDatabase
import com.megane14916.lifetimeline.data.remote.PhotoSyncContractJson
import com.megane14916.lifetimeline.data.remote.PhotoSyncDevice
import com.megane14916.lifetimeline.data.remote.PhotoSyncFailureKind
import com.megane14916.lifetimeline.data.remote.PhotoSyncPhoto
import com.megane14916.lifetimeline.data.remote.PhotoSyncPolicy
import com.megane14916.lifetimeline.data.remote.PhotoSyncRemoteException
import com.megane14916.lifetimeline.data.remote.PhotoSyncRequest
import com.megane14916.lifetimeline.data.remote.PhotoSyncUploader
import com.megane14916.lifetimeline.data.remote.PhotoThumbnailMetadata
import com.megane14916.lifetimeline.data.remote.PhotoThumbnailPart
import com.megane14916.lifetimeline.data.remote.expectedThumbnailPartNames
import com.megane14916.lifetimeline.data.remote.validateContract
import com.megane14916.lifetimeline.data.remote.validateFor
import com.megane14916.lifetimeline.data.remote.validateThumbnailParts
import com.megane14916.lifetimeline.worker.PhotoWorkPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import java.io.IOException

enum class PhotoSyncRunStatus {
  SUCCESS,
  NO_PENDING,
  RETRY_LIMIT_REACHED,
  LEASE_LOST,
  FAILED,
}

data class PhotoSyncFailure(
  val kind: PhotoSyncFailureKind,
  val retryable: Boolean,
)

data class PhotoSyncResult(
  val status: PhotoSyncRunStatus,
  val acceptedCount: Int = 0,
  val failure: PhotoSyncFailure? = null,
)

/** Uploads old, uploadable photo rows in bounded batches and applies only explicitly accepted IDs. */
class PhotoSyncRepository(
  private val database: LifeTimelineDatabase,
  private val thumbnailStore: LocalThumbnailStore,
  private val uploader: PhotoSyncUploader,
  private val device: PhotoSyncDevice,
  private val nowMs: () -> Long = System::currentTimeMillis,
  private val nanoTime: () -> Long = System::nanoTime,
) {
  suspend fun syncPending(
    maxBatches: Int = PhotoWorkPolicy.MAX_BATCHES_PER_RUN,
    deadlineNanos: Long = Long.MAX_VALUE,
    onBatchCompleted: suspend () -> Boolean = { true },
  ): PhotoSyncResult {
    require(maxBatches in 1..PhotoWorkPolicy.MAX_BATCHES_PER_RUN)
    var acceptedCount = 0
    var completedBatches = 0
    while (completedBatches < maxBatches && nanoTime() < deadlineNanos) {
      val candidates = database.androidMediaItemDao().getPendingSyncBatch(PhotoSyncPolicy.MAX_PHOTOS_PER_BATCH)
      if (candidates.isEmpty()) {
        return PhotoSyncResult(if (completedBatches == 0) PhotoSyncRunStatus.NO_PENDING else PhotoSyncRunStatus.SUCCESS, acceptedCount)
      }

      val batch =
        try {
          buildBatch(candidates)
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (_: IOException) {
          return PhotoSyncResult(
            PhotoSyncRunStatus.FAILED,
            acceptedCount,
            PhotoSyncFailure(PhotoSyncFailureKind.PROTOCOL, retryable = false),
          )
        } catch (_: IllegalArgumentException) {
          return PhotoSyncResult(
            PhotoSyncRunStatus.FAILED,
            acceptedCount,
            PhotoSyncFailure(PhotoSyncFailureKind.PROTOCOL, retryable = false),
          )
        }
      val response =
        try {
          uploader.upload(batch.request, batch.parts)
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (failure: PhotoSyncRemoteException) {
          return PhotoSyncResult(PhotoSyncRunStatus.FAILED, acceptedCount, PhotoSyncFailure(failure.kind, failure.retryable))
        } catch (_: IOException) {
          return PhotoSyncResult(
            PhotoSyncRunStatus.FAILED,
            acceptedCount,
            PhotoSyncFailure(PhotoSyncFailureKind.NETWORK, retryable = true),
          )
        } catch (_: IllegalArgumentException) {
          return PhotoSyncResult(
            PhotoSyncRunStatus.FAILED,
            acceptedCount,
            PhotoSyncFailure(PhotoSyncFailureKind.PROTOCOL, retryable = false),
          )
        }
      val validatedResponse =
        try {
          response.validateFor(batch.request)
        } catch (_: IllegalArgumentException) {
          return PhotoSyncResult(
            PhotoSyncRunStatus.FAILED,
            acceptedCount,
            PhotoSyncFailure(PhotoSyncFailureKind.PROTOCOL, retryable = false),
          )
        }
      if (validatedResponse.accepted.isEmpty()) {
        return PhotoSyncResult(
          PhotoSyncRunStatus.FAILED,
          acceptedCount,
          PhotoSyncFailure(PhotoSyncFailureKind.PROTOCOL, retryable = false),
        )
      }

      val acceptedIds = validatedResponse.accepted.toSet()
      val acknowledged = batch.items.filter { it.entity.id in acceptedIds }
      val pathsToDelete =
        database.withTransaction {
          acknowledged.mapNotNull { item ->
            val updated =
              database.androidMediaItemDao().acknowledgeSynced(
                id = item.entity.id,
                expectedThumbnailState = item.entity.thumbnailState,
                expectedSha256 = item.entity.thumbnailSha256,
                expectedRelativePath = item.entity.thumbnailRelativePath,
                syncedAtMs = nowMs(),
              )
            check(updated == 1) { "Photo ACK no longer matches its pending Room row." }
            item.entity.thumbnailRelativePath.takeIf { item.entity.thumbnailState == AndroidMediaItemEntity.THUMBNAIL_READY }
          }
        }
      pathsToDelete.forEach { path -> runCatching { thumbnailStore.delete(path) } }
      acceptedCount += acknowledged.size
      completedBatches += 1
      if (!onBatchCompleted()) return PhotoSyncResult(PhotoSyncRunStatus.LEASE_LOST, acceptedCount)
    }

    val hasMore = database.androidMediaItemDao().getPendingSyncBatch(1).isNotEmpty()
    return if (hasMore) {
      PhotoSyncResult(PhotoSyncRunStatus.RETRY_LIMIT_REACHED, acceptedCount)
    } else {
      PhotoSyncResult(PhotoSyncRunStatus.SUCCESS, acceptedCount)
    }
  }

  private fun buildBatch(candidates: List<AndroidMediaItemEntity>): PhotoUploadBatch {
    val items = mutableListOf<PhotoUploadItem>()
    val parts = mutableListOf<PhotoThumbnailPart>()
    var thumbnailBytes = 0L
    val maxThumbnailBytes = (PhotoSyncPolicy.MAX_REQUEST_BYTES - MAX_MULTIPART_OVERHEAD_BYTES).toLong()
    for (entity in candidates) {
      val thumbnail =
        when (entity.thumbnailState) {
          AndroidMediaItemEntity.THUMBNAIL_READY -> {
            val path = requireNotNull(entity.thumbnailRelativePath)
            val hash = requireNotNull(entity.thumbnailSha256)
            val size = requireNotNull(entity.thumbnailSizeBytes)
            val file = thumbnailStore.readVerified(path, hash, size)
            if (thumbnailBytes + file.bytes.size > maxThumbnailBytes) break
            thumbnailBytes += file.bytes.size
            PhotoThumbnailMetadata(
              mimeType = PhotoSyncPolicy.THUMBNAIL_MIME_TYPE,
              width = file.width,
              height = file.height,
              byteSize = file.bytes.size,
              sha256 = hash,
            ).also {
              parts += PhotoThumbnailPart("thumbnail_${entity.id}", file.bytes)
            }
          }

          AndroidMediaItemEntity.THUMBNAIL_UNAVAILABLE -> {
            null
          }

          else -> {
            throw IllegalArgumentException("Photo is not ready for synchronization.")
          }
        }
      items += PhotoUploadItem(entity, thumbnail)
    }
    require(items.isNotEmpty()) { "No photo fits within the multipart request limit." }
    val request =
      PhotoSyncRequest(
        schemaVersion = 1,
        device = device,
        photos = items.map { it.entity.toPhotoSyncPhoto(it.thumbnail) },
      ).validateContract()
    request.validateThumbnailParts(parts)
    require(PhotoSyncContractJson.encodeToString(request).toByteArray(Charsets.UTF_8).size <= MAX_METADATA_BYTES) {
      "Photo metadata exceeds the multipart request limit."
    }
    require(parts.map { it.name }.toSet() == request.expectedThumbnailPartNames()) {
      "Photo thumbnail parts do not match the request."
    }
    return PhotoUploadBatch(items, parts, request)
  }

  private fun AndroidMediaItemEntity.toPhotoSyncPhoto(thumbnail: PhotoThumbnailMetadata?) =
    PhotoSyncPhoto(
      id = id,
      source = source,
      sourceId = sourceId,
      filename = filename,
      capturedAtMs = capturedAtMs,
      width = width,
      height = height,
      mimeType = mimeType,
      latitude = latitude,
      longitude = longitude,
      thumbnail = thumbnail,
    )

  private data class PhotoUploadItem(
    val entity: AndroidMediaItemEntity,
    val thumbnail: PhotoThumbnailMetadata?,
  )

  private data class PhotoUploadBatch(
    val items: List<PhotoUploadItem>,
    val parts: List<PhotoThumbnailPart>,
    val request: PhotoSyncRequest,
  )

  companion object {
    const val MAX_MULTIPART_OVERHEAD_BYTES = 64 * 1_024
    const val MAX_METADATA_BYTES = 64 * 1_024
  }
}
