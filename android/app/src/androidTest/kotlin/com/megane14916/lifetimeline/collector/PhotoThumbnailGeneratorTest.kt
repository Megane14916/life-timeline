package com.megane14916.lifetimeline.collector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megane14916.lifetimeline.data.local.AndroidMediaItemEntity
import com.megane14916.lifetimeline.domain.generateUlid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class PhotoThumbnailGeneratorTest {
  @Test
  fun appliesLegacyExifRotationBoundsLongEdgeAndNeverUpscales() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val rotatedFile = File(context.cacheDir, "synthetic-rotated-${System.nanoTime()}.jpg")
    val smallFile = File(context.cacheDir, "synthetic-small-${System.nanoTime()}.jpg")
    try {
      writeJpeg(rotatedFile, width = 1_200, height = 600, orientation = ExifInterface.ORIENTATION_ROTATE_90)
      writeJpeg(smallFile, width = 64, height = 32)
      val generator =
        PhotoThumbnailGenerator(
          context = context,
          contentResolver = context.contentResolver,
          apiLevel = 28,
          sourceUriProvider = { item -> Uri.fromFile(if (item.mediaStoreId == 1L) rotatedFile else smallFile) },
          openInputStream = { uri -> FileInputStream(checkNotNull(uri.path)) },
        )

      val rotated = generator.generate(mediaItem(1))
      val rotatedBounds = decodeBounds(rotated.bytes)
      assertEquals(256, rotatedBounds.outWidth)
      assertEquals(512, rotatedBounds.outHeight)
      assertTrue(rotated.bytes.size <= PhotoThumbnailGenerator.MAX_THUMBNAIL_BYTES)
      assertTrue(hasWebpSignature(rotated.bytes))
      assertEquals(rotated.bytes.sha256(), rotated.sha256)

      val small = generator.generate(mediaItem(2))
      val smallBounds = decodeBounds(small.bytes)
      assertEquals(64, smallBounds.outWidth)
      assertEquals(32, smallBounds.outHeight)
    } finally {
      rotatedFile.delete()
      smallFile.delete()
    }
  }

  private fun writeJpeg(
    file: File,
    width: Int,
    height: Int,
    orientation: Int = ExifInterface.ORIENTATION_NORMAL,
  ) {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
      FileOutputStream(file).use { output ->
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
      }
    } finally {
      bitmap.recycle()
    }
    ExifInterface(file).apply {
      setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
      saveAttributes()
    }
  }

  private fun decodeBounds(bytes: ByteArray): BitmapFactory.Options =
    BitmapFactory.Options().also { options ->
      options.inJustDecodeBounds = true
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

  private fun hasWebpSignature(bytes: ByteArray): Boolean =
    bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" && bytes.copyOfRange(8, 12).decodeToString() == "WEBP"

  private fun mediaItem(id: Long) =
    AndroidMediaItemEntity(
      id = generateUlid(1_780_000_000_000 + id),
      sourceId = "external:$id",
      volumeName = "external",
      mediaStoreId = id,
      filename = "synthetic-$id.jpg",
      capturedAtMs = 1_780_000_000_000,
      capturedAtSource = "date_taken",
      mimeType = "image/jpeg",
      discoveredAtMs = 1_780_000_000_100,
    )
}
