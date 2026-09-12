package com.megane14916.lifetimeline.repository

import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.room.withTransaction
import com.megane14916.lifetimeline.collector.MediaStorePhotoCursor
import com.megane14916.lifetimeline.collector.MediaStorePhotoScanStatus
import com.megane14916.lifetimeline.collector.MediaStorePhotoSource
import com.megane14916.lifetimeline.collector.MediaStoreSelectionCursor
import com.megane14916.lifetimeline.collector.PhotoAccessState
import com.megane14916.lifetimeline.collector.PhotoThumbnailGenerator
import com.megane14916.lifetimeline.collector.sha256
import com.megane14916.lifetimeline.data.local.AndroidMediaItemEntity
import com.megane14916.lifetimeline.data.local.LifeTimelineDatabase
import com.megane14916.lifetimeline.data.local.MediaCollectionStateEntity
import com.megane14916.lifetimeline.domain.generateUlid
import kotlinx.coroutines.CancellationException
import java.io.IOException

enum class PhotoCollectionStatus {
  COMPLETED,
  PHOTO_ACCESS_REQUIRED,
  ACCESS_REVOKED,
}

data class PhotoCollectionResult(
  val status: PhotoCollectionStatus,
  val discoveredCount: Int = 0,
  val generatedThumbnailCount: Int = 0,
  val unavailableThumbnailCount: Int = 0,
  val hasMore: Boolean = false,
)

/** Persists each bounded MediaStore page before advancing that volume's durable cursor. */
class PhotoCollectionRepository(
  private val database: LifeTimelineDatabase,
  private val photoSource: MediaStorePhotoSource,
  private val thumbnailGenerator: PhotoThumbnailGenerator,
  private val thumbnailStore: LocalThumbnailStore,
  private val nowProvider: () -> Long = System::currentTimeMillis,
  private val idGenerator: (Long) -> String = ::generateUlid,
) {
  suspend fun collect(
    access: PhotoAccessState,
    collectionStartedAtMs: Long,
    maxDiscovered: Int = MAX_DISCOVERY_PER_RUN,
    maxThumbnails: Int = MAX_THUMBNAILS_PER_RUN,
    cancellationSignal: CancellationSignal = CancellationSignal(),
  ): PhotoCollectionResult {
    require(collectionStartedAtMs >= 0) { "Collection start time must be non-negative." }
    require(maxDiscovered in 1..MAX_DISCOVERY_PER_RUN)
    require(maxThumbnails in 0..MAX_THUMBNAILS_PER_RUN)
    if (access == PhotoAccessState.DENIED) return PhotoCollectionResult(PhotoCollectionStatus.PHOTO_ACCESS_REQUIRED)

    val volumes =
      try {
        photoSource.externalVolumeNames()
      } catch (_: SecurityException) {
        return PhotoCollectionResult(PhotoCollectionStatus.ACCESS_REVOKED)
      }
    var discovered = 0
    var scanned = 0
    var hasMore = false
    for (volumeName in volumes) {
      cancellationSignal.throwIfCanceled()
      val state = database.mediaCollectionStateDao().find(volumeName)
      val startedAt = state?.collectionStartedAtMs ?: collectionStartedAtMs
      val cursor = state?.toPhotoCursor()
      var selectionCursor: MediaStoreSelectionCursor? = null
      var continueVolume = true
      while (continueVolume && scanned < MAX_RAW_SCAN_PER_RUN && discovered < maxDiscovered) {
        cancellationSignal.throwIfCanceled()
        val page =
          photoSource.queryPage(
            volumeName = volumeName,
            access = access,
            cursor = cursor,
            selectionCursor = selectionCursor,
            collectionStartedAtMs = startedAt,
            limit = minOf(MAX_PAGE_SIZE, maxDiscovered - discovered, MAX_RAW_SCAN_PER_RUN - scanned),
          )
        when (page.status) {
          MediaStorePhotoScanStatus.PHOTO_ACCESS_REQUIRED -> {
            return PhotoCollectionResult(PhotoCollectionStatus.PHOTO_ACCESS_REQUIRED, discoveredCount = discovered)
          }

          MediaStorePhotoScanStatus.ACCESS_REVOKED -> {
            return PhotoCollectionResult(PhotoCollectionStatus.ACCESS_REVOKED, discoveredCount = discovered)
          }

          MediaStorePhotoScanStatus.SUCCESS -> {}
        }

        val nowMs = nowProvider()
        val pendingEntities =
          page.photos.map { candidate ->
            AndroidMediaItemEntity(
              id = idGenerator(nowMs),
              sourceId = candidate.sourceId,
              volumeName = candidate.volumeName,
              mediaStoreId = candidate.mediaStoreId,
              filename = candidate.filename,
              capturedAtMs = candidate.capturedAtMs,
              capturedAtSource = candidate.capturedAtSource,
              width = candidate.width,
              height = candidate.height,
              mimeType = candidate.mimeType,
              discoveredAtMs = nowMs,
            )
          }
        val insertedCount =
          database.withTransaction {
            val insertedIds = database.androidMediaItemDao().insertAllIfAbsent(pendingEntities)
            val next = page.nextCursor
            database.mediaCollectionStateDao().upsert(
              MediaCollectionStateEntity(
                volumeName = volumeName,
                mediaStoreVersion = next?.mediaStoreVersion,
                generationCursor = next?.generationCursor,
                dateAddedCursorSeconds = next?.dateAddedCursorSeconds,
                mediaIdCursor = next?.mediaStoreIdCursor,
                collectionStartedAtMs = startedAt,
                lastScanAtMs = nowMs,
                updatedAtMs = nowMs,
              ),
            )
            insertedIds.count { it != -1L }
          }
        scanned += page.scannedRowCount
        discovered += insertedCount
        hasMore = hasMore || page.hasMore
        selectionCursor = page.nextSelectionCursor
        continueVolume =
          access == PhotoAccessState.PARTIAL && page.hasMore && selectionCursor != null &&
          discovered < maxDiscovered && scanned < MAX_RAW_SCAN_PER_RUN
      }
      if (discovered >= maxDiscovered) {
        hasMore = hasMore || volumes.lastOrNull() != volumeName
        break
      }
      if (scanned >= MAX_RAW_SCAN_PER_RUN) {
        hasMore = true
        break
      }
    }

    var generated = 0
    var unavailable = 0
    for (item in database.androidMediaItemDao().getPendingThumbnails(maxThumbnails)) {
      cancellationSignal.throwIfCanceled()
      try {
        val thumbnail = thumbnailGenerator.generate(item, cancellationSignal)
        check(thumbnail.bytes.sha256() == thumbnail.sha256) { "Generated thumbnail hash does not match its bytes." }
        val stored = thumbnailStore.save(item.id, thumbnail.bytes)
        val updated =
          database.withTransaction {
            database.androidMediaItemDao().updateThumbnail(
              id = item.id,
              state = AndroidMediaItemEntity.THUMBNAIL_READY,
              relativePath = stored.relativePath,
              sha256 = stored.sha256,
              sizeBytes = stored.sizeBytes,
              latitude = thumbnail.location?.latitude,
              longitude = thumbnail.location?.longitude,
              errorKind = null,
            )
          }
        if (updated > 0) {
          generated += 1
        } else {
          if (database.androidMediaItemDao().findById(item.id)?.thumbnailRelativePath != stored.relativePath) {
            thumbnailStore.delete(stored.relativePath)
          }
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: OperationCanceledException) {
        throw error
      } catch (error: Exception) {
        unavailable += markUnavailable(item.id, error)
      }
    }

    val references = database.androidMediaItemDao().getReferencedThumbnailPaths().toSet()
    thumbnailStore.cleanupOrphans(references, nowProvider())
    return PhotoCollectionResult(
      status = PhotoCollectionStatus.COMPLETED,
      discoveredCount = discovered,
      generatedThumbnailCount = generated,
      unavailableThumbnailCount = unavailable,
      hasMore = hasMore,
    )
  }

  private suspend fun markUnavailable(
    id: String,
    error: Exception,
  ): Int =
    database.androidMediaItemDao().updateThumbnail(
      id = id,
      state = AndroidMediaItemEntity.THUMBNAIL_UNAVAILABLE,
      relativePath = null,
      sha256 = null,
      sizeBytes = null,
      latitude = null,
      longitude = null,
      errorKind = if (error is IOException) "source_unavailable" else "thumbnail_generation_failed",
    )

  private fun MediaCollectionStateEntity.toPhotoCursor() =
    MediaStorePhotoCursor(
      mediaStoreVersion = mediaStoreVersion,
      generationCursor = generationCursor,
      dateAddedCursorSeconds = dateAddedCursorSeconds,
      mediaStoreIdCursor = mediaIdCursor,
    )

  private companion object {
    const val MAX_PAGE_SIZE = 200
    const val MAX_DISCOVERY_PER_RUN = 200
    const val MAX_THUMBNAILS_PER_RUN = 20
    const val MAX_RAW_SCAN_PER_RUN = 2_000
  }
}
