package com.megane14916.lifetimeline.repository

import android.graphics.BitmapFactory
import com.megane14916.lifetimeline.collector.PhotoThumbnailGenerator
import com.megane14916.lifetimeline.collector.hasWebpSignature
import com.megane14916.lifetimeline.collector.sha256
import com.megane14916.lifetimeline.domain.validateUlid
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

data class StoredPhotoThumbnail(
  val relativePath: String,
  val sha256: String,
  val sizeBytes: Long,
)

data class LocalPhotoThumbnail(
  val bytes: ByteArray,
  val width: Int,
  val height: Int,
)

/** Stores only verified generated WebP bytes below the app-private photo thumbnail root. */
class LocalThumbnailStore(
  private val root: File,
) {
  fun save(
    id: String,
    bytes: ByteArray,
  ): StoredPhotoThumbnail {
    validateUlid(id)
    require(bytes.size in 1..PhotoThumbnailGenerator.MAX_THUMBNAIL_BYTES) { "Thumbnail size is invalid." }
    require(bytes.hasWebpSignature()) { "Thumbnail is not a WebP image." }
    val hash = bytes.sha256()
    val relativePath = relativePath(id)
    val finalFile = resolve(relativePath)
    val parent = finalFile.parentFile ?: throw IOException("Thumbnail parent directory is missing.")
    if (!parent.exists() && !parent.mkdirs()) throw IOException("Unable to create thumbnail directory.")
    if (finalFile.exists()) {
      val existing = finalFile.readBytes()
      if (existing.sha256() == hash && existing.size == bytes.size) {
        return StoredPhotoThumbnail(relativePath, hash, bytes.size.toLong())
      }
      throw IOException("A different thumbnail already exists for this item.")
    }

    val temporaryFile = File(parent, "${finalFile.name}.tmp-${UUID.randomUUID()}")
    try {
      FileOutputStream(temporaryFile).use { output ->
        output.write(bytes)
        output.flush()
        output.fd.sync()
      }
      val storedBytes = temporaryFile.readBytes()
      check(storedBytes.size == bytes.size && storedBytes.sha256() == hash) { "Temporary thumbnail verification failed." }
      if (!temporaryFile.renameTo(finalFile)) throw IOException("Unable to atomically publish thumbnail.")
      return StoredPhotoThumbnail(relativePath, hash, bytes.size.toLong())
    } finally {
      if (temporaryFile.exists()) temporaryFile.delete()
    }
  }

  fun delete(relativePath: String): Boolean {
    val file = resolve(relativePath)
    return !file.exists() || file.delete()
  }

  /** Reads a bounded, hash-checked WebP below the fixed private root for multipart upload. */
  fun readVerified(
    relativePath: String,
    expectedSha256: String,
    expectedSizeBytes: Long,
  ): LocalPhotoThumbnail {
    require(expectedSha256.matches(LOWERCASE_SHA256_PATTERN)) { "Thumbnail digest is invalid." }
    require(expectedSizeBytes in 1..PhotoThumbnailGenerator.MAX_THUMBNAIL_BYTES.toLong()) {
      "Thumbnail size is invalid."
    }
    val file = resolve(relativePath)
    if (!file.isFile || file.length() != expectedSizeBytes) throw IOException("Thumbnail file is unavailable.")
    val bytes = file.readBytes()
    if (bytes.size.toLong() != expectedSizeBytes || bytes.sha256() != expectedSha256 || !bytes.hasWebpSignature()) {
      throw IOException("Thumbnail file verification failed.")
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..PhotoThumbnailGenerator.MAX_EDGE_PX ||
      bounds.outHeight !in 1..PhotoThumbnailGenerator.MAX_EDGE_PX
    ) {
      throw IOException("Thumbnail dimensions are invalid.")
    }
    return LocalPhotoThumbnail(bytes, bounds.outWidth, bounds.outHeight)
  }

  /** Removes only aged thumbnail temp/final files not referenced by Room. */
  fun cleanupOrphans(
    referencedPaths: Set<String>,
    nowMs: Long = System.currentTimeMillis(),
    retentionMs: Long = ORPHAN_RETENTION_MS,
  ): Int {
    require(nowMs >= 0 && retentionMs >= 0)
    if (!root.exists()) return 0
    val cutoff = nowMs - retentionMs
    var deleted = 0
    root.listFiles()?.filter(File::isDirectory)?.forEach { directory ->
      if (!directory.name.matches(ULID_PREFIX_PATTERN)) return@forEach
      directory.listFiles()?.forEach { file ->
        val id = file.name.take(26)
        val isTemp = file.name.matches(TEMP_FILE_PATTERN)
        val isFinal = file.name.matches(FINAL_FILE_PATTERN)
        val path = relativePath(id)
        if (
          file.isFile && (isTemp || isFinal) &&
          file.lastModified() <= cutoff &&
          (isTemp || path !in referencedPaths)
        ) {
          if (file.delete()) deleted += 1
        }
      }
    }
    return deleted
  }

  private fun resolve(relativePath: String): File {
    require(relativePath.matches(RELATIVE_PATH_PATTERN)) { "Invalid thumbnail relative path." }
    val rootPath = root.canonicalFile
    val file = File(rootPath, relativePath.replace('/', File.separatorChar)).canonicalFile
    require(file.toPath().startsWith(rootPath.toPath())) { "Thumbnail path escapes the private root." }
    return file
  }

  private fun relativePath(id: String): String = "${id.take(2)}/$id.webp"

  companion object {
    const val ORPHAN_RETENTION_MS = 24 * 60 * 60 * 1_000L
    val ULID_PREFIX_PATTERN = Regex("[0-9A-HJKMNP-TV-Z]{2}")
    val RELATIVE_PATH_PATTERN = Regex("[0-9A-HJKMNP-TV-Z]{2}/[0-9A-HJKMNP-TV-Z]{26}\\.webp")
    val FINAL_FILE_PATTERN = Regex("[0-9A-HJKMNP-TV-Z]{26}\\.webp")
    val TEMP_FILE_PATTERN = Regex("[0-9A-HJKMNP-TV-Z]{26}\\.webp\\.tmp-[0-9a-fA-F-]{36}")
    val LOWERCASE_SHA256_PATTERN = Regex("[0-9a-f]{64}")
  }
}
