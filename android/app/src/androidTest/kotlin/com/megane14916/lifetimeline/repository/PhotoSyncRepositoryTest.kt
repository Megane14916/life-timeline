package com.megane14916.lifetimeline.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megane14916.lifetimeline.data.local.AndroidMediaItemEntity
import com.megane14916.lifetimeline.data.local.LifeTimelineDatabase
import com.megane14916.lifetimeline.data.remote.PhotoSyncDevice
import com.megane14916.lifetimeline.data.remote.PhotoSyncFailureKind
import com.megane14916.lifetimeline.data.remote.PhotoSyncRequest
import com.megane14916.lifetimeline.data.remote.PhotoSyncResponse
import com.megane14916.lifetimeline.data.remote.PhotoSyncUploader
import com.megane14916.lifetimeline.data.remote.PhotoThumbnailPart
import com.megane14916.lifetimeline.domain.generateUlid
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class PhotoSyncRepositoryTest {
  private lateinit var database: LifeTimelineDatabase
  private lateinit var root: File
  private lateinit var thumbnailStore: LocalThumbnailStore

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database =
      Room
        .inMemoryDatabaseBuilder(context, LifeTimelineDatabase::class.java)
        .addCallback(LifeTimelineDatabase.PHOTO_INTEGRITY_CALLBACK)
        .allowMainThreadQueries()
        .build()
    root = File(context.cacheDir, "photo-sync-test-${System.nanoTime()}")
    thumbnailStore = LocalThumbnailStore(root)
  }

  @After
  fun tearDown() {
    database.close()
    root.deleteRecursively()
  }

  @Test
  fun partialAckThenNetworkFailureKeepsOnlyUnacknowledgedPhotoPending() =
    runBlocking {
      val ready = addReadyPhoto(1, FIRST_ID)
      val unavailable = addUnavailablePhoto(2, SECOND_ID)
      val originalPath = checkNotNull(ready.thumbnailRelativePath)
      var uploadCount = 0
      val uploader =
        RecordingUploader { request, parts ->
          uploadCount += 1
          if (uploadCount == 1) {
            assertEquals(listOf(FIRST_ID, SECOND_ID), request.photos.map { it.id })
            assertEquals(setOf("thumbnail_$FIRST_ID"), parts.map { it.name }.toSet())
            assertEquals(
              1,
              request.photos
                .first()
                .thumbnail
                ?.width,
            )
            PhotoSyncResponse(schemaVersion = 1, accepted = listOf(FIRST_ID))
          } else {
            assertEquals(listOf(SECOND_ID), request.photos.map { it.id })
            assertNull(request.photos.single().thumbnail)
            throw IOException("synthetic network interruption")
          }
        }

      val result = repository(uploader).syncPending()

      assertEquals(PhotoSyncRunStatus.FAILED, result.status)
      assertEquals(PhotoSyncFailureKind.NETWORK, result.failure?.kind)
      assertTrue(result.failure?.retryable == true)
      assertEquals(2, uploadCount)
      val accepted = checkNotNull(database.androidMediaItemDao().findById(FIRST_ID))
      assertEquals(AndroidMediaItemEntity.SYNCED, accepted.syncStatus)
      assertEquals(AndroidMediaItemEntity.THUMBNAIL_CLEANED, accepted.thumbnailState)
      assertNull(accepted.thumbnailRelativePath)
      assertFalse(File(root, originalPath).exists())
      val pending = checkNotNull(database.androidMediaItemDao().findById(SECOND_ID))
      assertEquals(AndroidMediaItemEntity.SYNC_PENDING, pending.syncStatus)
      assertEquals(AndroidMediaItemEntity.THUMBNAIL_UNAVAILABLE, pending.thumbnailState)
      assertEquals(1, database.androidMediaItemDao().countPendingSync())
      assertEquals(0, database.androidMediaItemDao().countPendingSyncable())
      assertNotNull(unavailable.id)
    }

  @Test
  fun unknownAckDoesNotChangeRoomOrDeleteTheLocalThumbnail() =
    runBlocking {
      val ready = addReadyPhoto(1, FIRST_ID)
      val path = checkNotNull(ready.thumbnailRelativePath)
      val uploader =
        RecordingUploader { _, _ ->
          PhotoSyncResponse(schemaVersion = 1, accepted = listOf(UNKNOWN_ID))
        }

      val result = repository(uploader).syncPending()

      assertEquals(PhotoSyncRunStatus.FAILED, result.status)
      assertEquals(PhotoSyncFailureKind.PROTOCOL, result.failure?.kind)
      val unchanged = checkNotNull(database.androidMediaItemDao().findById(FIRST_ID))
      assertEquals(AndroidMediaItemEntity.SYNC_PENDING, unchanged.syncStatus)
      assertEquals(AndroidMediaItemEntity.THUMBNAIL_READY, unchanged.thumbnailState)
      assertTrue(File(root, path).isFile)
    }

  private fun repository(uploader: PhotoSyncUploader) =
    PhotoSyncRepository(
      database = database,
      thumbnailStore = thumbnailStore,
      uploader = uploader,
      device = PhotoSyncDevice(DEVICE_ID, "Synthetic Android device", "android"),
      nowMs = { 1_800_000_000_000 },
    )

  private suspend fun addReadyPhoto(
    mediaStoreId: Long,
    id: String,
  ): AndroidMediaItemEntity {
    val bytes = syntheticWebp()
    val stored = thumbnailStore.save(id, bytes)
    val item =
      photo(id, mediaStoreId).copy(
        thumbnailState = AndroidMediaItemEntity.THUMBNAIL_READY,
        thumbnailRelativePath = stored.relativePath,
        thumbnailSha256 = stored.sha256,
        thumbnailSizeBytes = stored.sizeBytes,
      )
    database.androidMediaItemDao().insertIfAbsent(item)
    return item
  }

  private suspend fun addUnavailablePhoto(
    mediaStoreId: Long,
    id: String,
  ): AndroidMediaItemEntity {
    val item = photo(id, mediaStoreId).copy(thumbnailState = AndroidMediaItemEntity.THUMBNAIL_UNAVAILABLE)
    database.androidMediaItemDao().insertIfAbsent(item)
    return item
  }

  private fun photo(
    id: String,
    mediaStoreId: Long,
  ) = AndroidMediaItemEntity(
    id = id,
    sourceId = "external:$mediaStoreId",
    volumeName = "external",
    mediaStoreId = mediaStoreId,
    filename = "synthetic-$mediaStoreId.jpg",
    capturedAtMs = 1_780_000_000_000 + mediaStoreId,
    capturedAtSource = "date_taken",
    width = 640,
    height = 480,
    mimeType = "image/jpeg",
    discoveredAtMs = 1_780_000_000_000,
  )

  private fun syntheticWebp(): ByteArray =
    ByteArrayOutputStream().use { output ->
      Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.BLUE)
        check(compress(Bitmap.CompressFormat.WEBP, 65, output))
        recycle()
      }
      output.toByteArray()
    }

  private class RecordingUploader(
    private val response: suspend (PhotoSyncRequest, List<PhotoThumbnailPart>) -> PhotoSyncResponse,
  ) : PhotoSyncUploader {
    override suspend fun upload(
      request: PhotoSyncRequest,
      thumbnails: List<PhotoThumbnailPart>,
    ): PhotoSyncResponse = response(request, thumbnails)
  }

  companion object {
    private const val DEVICE_ID = "01K4N6Q2N6N8YJ7W4M2D3A9B5C"
    private const val FIRST_ID = "01K4N70E3Q6N9D6E6G0C8M2H1P"
    private const val SECOND_ID = "01K4N70E3Q6N9D6E6G0C8M2H1Q"
    private val UNKNOWN_ID = generateUlid(1_780_000_000_001)
  }
}
