package com.megane14916.lifetimeline.repository

import android.content.Context
import android.os.CancellationSignal
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megane14916.lifetimeline.collector.GeneratedPhotoThumbnail
import com.megane14916.lifetimeline.collector.MediaStorePhotoBackend
import com.megane14916.lifetimeline.collector.MediaStorePhotoQueryPlan
import com.megane14916.lifetimeline.collector.MediaStorePhotoRow
import com.megane14916.lifetimeline.collector.MediaStorePhotoSource
import com.megane14916.lifetimeline.collector.PhotoAccessState
import com.megane14916.lifetimeline.collector.PhotoGeoLocation
import com.megane14916.lifetimeline.collector.PhotoThumbnailGenerator
import com.megane14916.lifetimeline.collector.sha256
import com.megane14916.lifetimeline.data.local.AndroidMediaItemEntity
import com.megane14916.lifetimeline.data.local.LifeTimelineDatabase
import com.megane14916.lifetimeline.domain.generateUlid
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class PhotoCollectionRepositoryTest {
  private lateinit var database: LifeTimelineDatabase
  private lateinit var root: File
  private lateinit var generatedIds: Iterator<String>

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database =
      Room
        .inMemoryDatabaseBuilder(context, LifeTimelineDatabase::class.java)
        .addCallback(LifeTimelineDatabase.PHOTO_INTEGRITY_CALLBACK)
        .allowMainThreadQueries()
        .build()
    root = File(context.cacheDir, "photo-thumbnail-test-${System.nanoTime()}")
    generatedIds = (0..10).map { generateUlid(1_780_000_000_000 + it) }.iterator()
  }

  @After
  fun tearDown() {
    database.close()
    root.deleteRecursively()
  }

  @Test
  fun malformedPhotoDoesNotBlockLaterPhotoAndCursorCommitsWithRows() =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val rows = listOf(photoRow(1), photoRow(2))
      val generator =
        object : PhotoThumbnailGenerator(context) {
          override fun generate(
            item: AndroidMediaItemEntity,
            cancellationSignal: CancellationSignal,
          ): GeneratedPhotoThumbnail {
            if (item.mediaStoreId == 1L) throw IOException("synthetic decode failure")
            val bytes = syntheticWebp()
            return GeneratedPhotoThumbnail(bytes, 1, 1, bytes.sha256(), PhotoGeoLocation(35.0, 139.0))
          }
        }
      val repository =
        PhotoCollectionRepository(
          database = database,
          photoSource = MediaStorePhotoSource(28, FakeBackend(rows)),
          thumbnailGenerator = generator,
          thumbnailStore = LocalThumbnailStore(root),
          nowProvider = { 1_780_000_010_000 },
          idGenerator = { generatedIds.next() },
        )

      val result = repository.collect(PhotoAccessState.FULL, collectionStartedAtMs = 1_780_000_000_000)

      assertEquals(PhotoCollectionStatus.COMPLETED, result.status)
      assertEquals(2, result.discoveredCount)
      assertEquals(1, result.generatedThumbnailCount)
      assertEquals(1, result.unavailableThumbnailCount)
      val items = database.androidMediaItemDao().getPendingThumbnails(limit = 10)
      assertTrue(items.isEmpty())
      val unavailable = database.androidMediaItemDao().findBySource(AndroidMediaItemEntity.SOURCE, "external:1")
      val ready = database.androidMediaItemDao().findBySource(AndroidMediaItemEntity.SOURCE, "external:2")
      assertEquals(AndroidMediaItemEntity.THUMBNAIL_UNAVAILABLE, unavailable?.thumbnailState)
      assertEquals(AndroidMediaItemEntity.THUMBNAIL_READY, ready?.thumbnailState)
      assertNotNull(ready?.thumbnailRelativePath)
      assertTrue(File(root, ready!!.thumbnailRelativePath!!).isFile)
      assertEquals(35.0, ready.latitude!!, 0.0)
      assertEquals(139.0, ready.longitude!!, 0.0)
      assertNull(ready.syncedAtMs)
      val state = database.mediaCollectionStateDao().find("external")
      assertEquals(1_780_000_001L, state?.dateAddedCursorSeconds)
      assertEquals(2L, state?.mediaIdCursor)
    }

  @Test
  fun deniedAccessLeavesCollectionStateUntouched() =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val repository =
        PhotoCollectionRepository(
          database = database,
          photoSource = MediaStorePhotoSource(28, FakeBackend(emptyList())),
          thumbnailGenerator = PhotoThumbnailGenerator(context),
          thumbnailStore = LocalThumbnailStore(root),
        )
      val result = repository.collect(PhotoAccessState.DENIED, collectionStartedAtMs = 1_780_000_000_000)
      assertEquals(PhotoCollectionStatus.PHOTO_ACCESS_REQUIRED, result.status)
      assertNull(database.mediaCollectionStateDao().find("external"))
      assertTrue(database.androidMediaItemDao().getReferencedThumbnailPaths().isEmpty())
    }

  private class FakeBackend(
    private val rows: List<MediaStorePhotoRow>,
  ) : MediaStorePhotoBackend {
    override fun supportsGeneration(): Boolean = false

    override fun externalVolumeNames(): List<String> = listOf("external")

    override fun version(volumeName: String): String? = null

    override fun generation(volumeName: String): Long? = null

    override fun query(
      volumeName: String,
      plan: MediaStorePhotoQueryPlan,
    ): List<MediaStorePhotoRow> = rows.take(plan.limit)
  }

  private fun photoRow(id: Long) =
    MediaStorePhotoRow(
      mediaStoreId = id,
      displayName = "synthetic-$id.jpg",
      dateTakenMs = 1_780_000_000_000 + id,
      dateAddedSeconds = 1_780_000_001,
      mimeType = "image/jpeg",
      width = 800,
      height = 600,
      relativePath = null,
      legacyDataPath = "/storage/emulated/0/DCIM/Camera/synthetic-$id.jpg",
      generationAdded = null,
      isPending = false,
      isTrashed = false,
    )

  private fun syntheticWebp(): ByteArray =
    byteArrayOf(
      'R'.code.toByte(),
      'I'.code.toByte(),
      'F'.code.toByte(),
      'F'.code.toByte(),
      4,
      0,
      0,
      0,
      'W'.code.toByte(),
      'E'.code.toByte(),
      'B'.code.toByte(),
      'P'.code.toByte(),
    )
}
