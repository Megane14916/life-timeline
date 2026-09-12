package com.megane14916.lifetimeline.collector

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.MediaStore
import android.util.Size
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import com.megane14916.lifetimeline.data.local.AndroidMediaItemEntity
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

data class PhotoGeoLocation(
  val latitude: Double,
  val longitude: Double,
)

data class GeneratedPhotoThumbnail(
  val bytes: ByteArray,
  val width: Int,
  val height: Int,
  val sha256: String,
  val location: PhotoGeoLocation?,
)

open class PhotoThumbnailGenerator(
  private val context: Context,
  private val contentResolver: ContentResolver = context.contentResolver,
  private val apiLevel: Int = Build.VERSION.SDK_INT,
  private val sourceUriProvider: ((AndroidMediaItemEntity) -> Uri)? = null,
  private val openInputStream: (Uri) -> InputStream? = contentResolver::openInputStream,
) {
  @SuppressLint("NewApi")
  @Suppress("DEPRECATION")
  open fun generate(
    item: AndroidMediaItemEntity,
    cancellationSignal: CancellationSignal = CancellationSignal(),
  ): GeneratedPhotoThumbnail {
    cancellationSignal.throwIfCanceled()
    val uri = sourceUriProvider?.invoke(item) ?: mediaStoreUri(item)
    val image =
      if (apiLevel >= Build.VERSION_CODES.Q) {
        contentResolver.loadThumbnail(uri, Size(MAX_EDGE_PX, MAX_EDGE_PX), cancellationSignal)
      } else {
        decodeLegacy(uri)
      }

    val bitmaps = mutableListOf<Bitmap>(image)
    try {
      val oriented = if (apiLevel < Build.VERSION_CODES.Q) applyExifOrientation(uri, image) else image
      if (oriented !== image) bitmaps += oriented
      val bounded = downscaleWithoutUpscaling(oriented)
      if (bounded !== oriented) bitmaps += bounded
      cancellationSignal.throwIfCanceled()

      val encoded =
        ByteArrayOutputStream().use { output ->
          val format = if (apiLevel >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
          check(bounded.compress(format, WEBP_QUALITY, output)) { "Unable to encode thumbnail." }
          output.toByteArray()
        }
      validateEncoded(encoded, bounded.width, bounded.height)
      val geoLocation = readGeoLocation(uri)
      cancellationSignal.throwIfCanceled()
      return GeneratedPhotoThumbnail(
        bytes = encoded,
        width = bounded.width,
        height = bounded.height,
        sha256 = encoded.sha256(),
        location = geoLocation,
      )
    } finally {
      bitmaps.distinctBy(System::identityHashCode).forEach(Bitmap::recycle)
    }
  }

  private fun mediaStoreUri(item: AndroidMediaItemEntity): Uri {
    val collectionUri =
      if (apiLevel >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(item.volumeName)
      } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
      }
    return Uri.withAppendedPath(collectionUri, item.mediaStoreId.toString())
  }

  private fun decodeLegacy(uri: Uri): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsStream = openInputStream(uri) ?: throw IOException("Photo is no longer available.")
    boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid source image dimensions." }
    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_EDGE_PX * 2) sampleSize *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
      ?: throw IOException("Photo is no longer available.")
  }

  private fun applyExifOrientation(
    uri: Uri,
    bitmap: Bitmap,
  ): Bitmap {
    val orientation =
      try {
        openInputStream(uri)?.use { stream ->
          ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
      } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
      } catch (_: IllegalArgumentException) {
        ExifInterface.ORIENTATION_NORMAL
      }
    val matrix = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
        matrix.setScale(-1f, 1f)
      }

      ExifInterface.ORIENTATION_ROTATE_180 -> {
        matrix.setRotate(180f)
      }

      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        matrix.setScale(1f, -1f)
      }

      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.setRotate(90f)
        matrix.postScale(-1f, 1f)
      }

      ExifInterface.ORIENTATION_ROTATE_90 -> {
        matrix.setRotate(90f)
      }

      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.setRotate(-90f)
        matrix.postScale(-1f, 1f)
      }

      ExifInterface.ORIENTATION_ROTATE_270 -> {
        matrix.setRotate(-90f)
      }

      else -> {
        return bitmap
      }
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
  }

  private fun downscaleWithoutUpscaling(bitmap: Bitmap): Bitmap {
    val longestEdge = max(bitmap.width, bitmap.height)
    if (longestEdge <= MAX_EDGE_PX) return bitmap
    val scale = MAX_EDGE_PX.toFloat() / longestEdge
    val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    return bitmap.scale(width, height, filter = true)
  }

  @SuppressLint("NewApi")
  private fun readGeoLocation(uri: Uri): PhotoGeoLocation? {
    if (apiLevel >= Build.VERSION_CODES.Q &&
      context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED
    ) {
      return null
    }
    return try {
      val sourceUri = if (apiLevel >= Build.VERSION_CODES.Q) MediaStore.setRequireOriginal(uri) else uri
      openInputStream(sourceUri)?.use { stream ->
        val coordinates = ExifInterface(stream).latLong ?: return@use null
        val latitude = coordinates[0]
        val longitude = coordinates[1]
        if (
          latitude.isFinite() && longitude.isFinite() &&
          latitude in -90.0..90.0 && longitude in -180.0..180.0
        ) {
          PhotoGeoLocation(latitude, longitude)
        } else {
          null
        }
      }
    } catch (error: OperationCanceledException) {
      throw error
    } catch (_: Exception) {
      null
    }
  }

  private fun validateEncoded(
    bytes: ByteArray,
    width: Int,
    height: Int,
  ) {
    require(bytes.size in 1..MAX_THUMBNAIL_BYTES) { "Encoded thumbnail exceeds the size limit." }
    require(width in 1..MAX_EDGE_PX && height in 1..MAX_EDGE_PX) { "Encoded thumbnail dimensions are invalid." }
    require(bytes.hasWebpSignature()) { "Encoded thumbnail is not WebP." }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth == width && bounds.outHeight == height) { "Encoded thumbnail dimensions do not match." }
  }

  companion object {
    const val MAX_EDGE_PX = 512
    const val WEBP_QUALITY = 65
    const val MAX_THUMBNAIL_BYTES = 1_048_576
  }
}

internal fun ByteArray.hasWebpSignature(): Boolean =
  size >= 12 &&
    this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() &&
    this[2] == 'F'.code.toByte() && this[3] == 'F'.code.toByte() &&
    this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte() &&
    this[10] == 'B'.code.toByte() && this[11] == 'P'.code.toByte()

internal fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
