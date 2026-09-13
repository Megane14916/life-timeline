package com.megane14916.lifetimeline.repository

import android.Manifest
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.megane14916.lifetimeline.collector.AndroidMediaStorePhotoBackend
import com.megane14916.lifetimeline.collector.MediaStorePhotoSource
import com.megane14916.lifetimeline.collector.PhotoAccessState
import com.megane14916.lifetimeline.collector.PhotoThumbnailGenerator
import com.megane14916.lifetimeline.collector.hasWebpSignature
import com.megane14916.lifetimeline.collector.sha256
import com.megane14916.lifetimeline.data.local.AndroidMediaItemEntity
import com.megane14916.lifetimeline.data.local.LifeTimelineDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhotoMediaStoreIntegrationTest {
  @get:Rule
  val readStoragePermission: GrantPermissionRule =
    GrantPermissionRule.grant(Manifest.permission.READ_EXTERNAL_STORAGE)

  private lateinit var database: LifeTimelineDatabase
  private lateinit var thumbnailRoot: File

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database =
      Room
        .inMemoryDatabaseBuilder(context, LifeTimelineDatabase::class.java)
        .allowMainThreadQueries()
        .addCallback(LifeTimelineDatabase.PHOTO_INTEGRITY_CALLBACK)
        .build()
    thumbnailRoot = File(context.cacheDir, "photo-mediastore-ci-${System.nanoTime()}")
  }

  @After
  fun tearDown() {
    database.close()
    thumbnailRoot.deleteRecursively()
  }

  @Test
  fun syntheticDcimImageFlowsFromMediaStoreIntoRoomAndPrivateWebpStorage() =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val repository =
        PhotoCollectionRepository(
          database = database,
          photoSource = MediaStorePhotoSource(Build.VERSION.SDK_INT, AndroidMediaStorePhotoBackend(context)),
          thumbnailGenerator = PhotoThumbnailGenerator(context),
          thumbnailStore = LocalThumbnailStore(thumbnailRoot),
        )

      val result =
        repository.collect(
          access = PhotoAccessState.FULL,
          collectionStartedAtMs = collectionStartBeforeSyntheticPhoto(context),
        )

      assertEquals(PhotoCollectionStatus.COMPLETED, result.status)
      assertTrue(result.discoveredCount > 0)
      assertTrue(result.generatedThumbnailCount > 0)
      val item =
        checkNotNull(
          database
            .androidMediaItemDao()
            .getPendingSyncBatch(limit = 200)
            .firstOrNull { it.filename == SYNTHETIC_FILENAME },
        )
      assertEquals("image/jpeg", item.mimeType)
      assertEquals(AndroidMediaItemEntity.THUMBNAIL_READY, item.thumbnailState)
      assertTrue(checkNotNull(item.width) in 1..512)
      assertTrue(checkNotNull(item.height) in 1..512)
      assertNotNull(database.mediaCollectionStateDao().find("external"))

      val relativePath = checkNotNull(item.thumbnailRelativePath)
      assertTrue(!File(relativePath).isAbsolute)
      val storedThumbnail = File(thumbnailRoot, relativePath)
      assertTrue(storedThumbnail.isFile)
      val bytes = storedThumbnail.readBytes()
      assertTrue(bytes.hasWebpSignature())
      assertEquals(item.thumbnailSha256, bytes.sha256())
      assertEquals(item.thumbnailSizeBytes, bytes.size.toLong())
      assertTrue(bytes.size in 1..PhotoThumbnailGenerator.MAX_THUMBNAIL_BYTES)
      assertEquals(listOf(storedThumbnail), thumbnailRoot.walkTopDown().filter(File::isFile).toList())
    }

  private companion object {
    const val SYNTHETIC_FILENAME = "synthetic-photo.jpg"

    fun collectionStartBeforeSyntheticPhoto(context: Context): Long {
      val cursor =
        context.contentResolver.query(
          MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
          arrayOf(MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATA),
          "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
          arrayOf(SYNTHETIC_FILENAME),
          null,
        ) ?: error("Synthetic MediaStore image is unavailable.")
      return cursor.use {
        check(it.moveToFirst()) { "Synthetic MediaStore image is unavailable." }
        val path = it.getString(1)
        check(path.split('/').any { segment -> segment.equals("DCIM", ignoreCase = true) }) {
          "Synthetic MediaStore image is outside DCIM."
        }
        (it.getLong(0) * 1_000L - 60_000L).coerceAtLeast(0L)
      }
    }
  }
}
