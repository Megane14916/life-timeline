package com.megane14916.lifetimeline.data.remote

import com.megane14916.lifetimeline.domain.validateUlid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

object PhotoSyncPolicy {
  const val MAX_PHOTOS_PER_BATCH = 20
  const val MAX_BATCHES_PER_RUN = 10
  const val MAX_RUN_MINUTES = 8L
  const val COLLECTION_INTERVAL_MINUTES = 15L
  const val COLLECTION_FLEX_MINUTES = 5L
  const val SYNC_BACKOFF_MINUTES = 30L
  const val LEASE_TTL_MINUTES = 15L
  const val MAX_THUMBNAIL_BYTES = 1_048_576
  const val MAX_REQUEST_BYTES = 20_971_520
  const val MAX_THUMBNAIL_DIMENSION_PX = 512
  const val THUMBNAIL_MIME_TYPE = "image/webp"
  const val THUMBNAIL_QUALITY = 65
  const val MAX_FILENAME_LENGTH = 255
}

@Serializable
data class PhotoSyncRequest(
  val schemaVersion: Int,
  val device: PhotoSyncDevice,
  val photos: List<PhotoSyncPhoto>,
)

@Serializable
data class PhotoSyncDevice(
  val id: String,
  val name: String,
  val platform: String,
)

@Serializable
data class PhotoSyncPhoto(
  val id: String,
  val source: String,
  val sourceId: String,
  val filename: String,
  val capturedAtMs: Long,
  val width: Int?,
  val height: Int?,
  val mimeType: String,
  val latitude: Double?,
  val longitude: Double?,
  val thumbnail: PhotoThumbnailMetadata?,
)

@Serializable
data class PhotoThumbnailMetadata(
  val mimeType: String,
  val width: Int,
  val height: Int,
  val byteSize: Int,
  val sha256: String,
)

@Serializable
data class PhotoSyncResponse(
  val schemaVersion: Int,
  val accepted: List<String>,
)

@Serializable
data class PhotoSyncMultipartContract(
  val name: String,
  val contentType: String,
)

@Serializable
data class PhotoSyncPolicyContract(
  val maxPhotosPerBatch: Int,
  val maxThumbnailBytes: Int,
  val maxRequestBytes: Int,
  val maxThumbnailDimensionPx: Int,
  val thumbnailMimeType: String,
  val thumbnailQuality: Int,
  val maxFilenameLength: Int,
)

@Serializable
data class PhotoSyncErrorFixture(
  val status: Int,
  val payload: SyncErrorResponse,
)

@Serializable
data class PhotoSyncContractFixture(
  val endpoint: String,
  val contentType: String,
  val metadataPart: PhotoSyncMultipartContract,
  val thumbnailPartNameFormat: String,
  val thumbnailPartFilename: String,
  val policy: PhotoSyncPolicyContract,
  val request: PhotoSyncRequest,
  val success: PhotoSyncResponse,
  val errors: List<PhotoSyncErrorFixture>,
)

data class PhotoThumbnailPart(
  val name: String,
  val bytes: ByteArray,
)

val PhotoSyncContractJson =
  Json {
    ignoreUnknownKeys = false
    explicitNulls = true
  }

fun PhotoSyncRequest.validateContract(): PhotoSyncRequest {
  require(schemaVersion == 1) { "schemaVersion must be 1." }
  validateUlid(device.id, "device.id")
  require(device.name.isNotBlank()) { "device.name must not be blank." }
  require(device.name.codePointCount(0, device.name.length) <= 200) {
    "device.name must be at most 200 characters."
  }
  require(device.platform == "android") { "device.platform must be android." }
  require(photos.size in 1..PhotoSyncPolicy.MAX_PHOTOS_PER_BATCH) {
    "photos must contain between 1 and ${PhotoSyncPolicy.MAX_PHOTOS_PER_BATCH} items."
  }

  val photoIds = photos.map { it.id }
  require(photoIds.size == photoIds.toSet().size) { "photos must not contain duplicate IDs." }
  val sourceIds = photos.map { it.sourceId }
  require(sourceIds.size == sourceIds.toSet().size) {
    "photos must not contain duplicate source IDs."
  }

  photos.forEach { photo ->
    validateUlid(photo.id, "photo.id")
    require(photo.source == "android_media_store") { "photo.source is unsupported." }
    require(
      photo.sourceId.isNotBlank() && photo.sourceId.codePointCount(0, photo.sourceId.length) <= 255,
    ) {
      "photo.sourceId must be nonblank and at most 255 characters."
    }
    require(
      photo.filename.isNotBlank() &&
        photo.filename.codePointCount(0, photo.filename.length) <= PhotoSyncPolicy.MAX_FILENAME_LENGTH,
    ) {
      "photo.filename must be nonblank and within the length limit."
    }
    require(photo.filename.none { it.code < 32 || it.code == 127 }) {
      "photo.filename must not contain control characters."
    }
    require('/' !in photo.filename && '\\' !in photo.filename) {
      "photo.filename must not contain path separators."
    }
    require(photo.capturedAtMs >= 0) { "photo.capturedAtMs must be nonnegative." }
    require(photo.width == null || photo.width > 0) { "photo.width must be positive when set." }
    require(photo.height == null || photo.height > 0) { "photo.height must be positive when set." }
    require(photo.mimeType.startsWith("image/") && photo.mimeType.length <= 100) {
      "photo.mimeType must identify an image."
    }
    require((photo.latitude == null) == (photo.longitude == null)) {
      "photo coordinates must be provided together."
    }
    require(photo.latitude == null || photo.latitude in -90.0..90.0) {
      "photo.latitude is out of range."
    }
    require(photo.longitude == null || photo.longitude in -180.0..180.0) {
      "photo.longitude is out of range."
    }

    photo.thumbnail?.let { thumbnail ->
      require(thumbnail.mimeType == PhotoSyncPolicy.THUMBNAIL_MIME_TYPE) {
        "thumbnail.mimeType must be image/webp."
      }
      require(thumbnail.width in 1..PhotoSyncPolicy.MAX_THUMBNAIL_DIMENSION_PX) {
        "thumbnail.width is outside the allowed range."
      }
      require(thumbnail.height in 1..PhotoSyncPolicy.MAX_THUMBNAIL_DIMENSION_PX) {
        "thumbnail.height is outside the allowed range."
      }
      require(thumbnail.byteSize in 1..PhotoSyncPolicy.MAX_THUMBNAIL_BYTES) {
        "thumbnail.byteSize is outside the allowed range."
      }
      require(LOWERCASE_SHA256.matches(thumbnail.sha256)) {
        "thumbnail.sha256 must be 64 lowercase hexadecimal characters."
      }
    }
  }
  return this
}

fun PhotoSyncRequest.expectedThumbnailPartNames(): Set<String> =
  photos
    .filter { it.thumbnail != null }
    .map { "thumbnail_${it.id}" }
    .toSet()

fun PhotoSyncRequest.validateThumbnailParts(parts: List<PhotoThumbnailPart>) {
  val receivedNames = parts.map { it.name }
  require(receivedNames.size == receivedNames.toSet().size) {
    "thumbnail parts must not contain duplicate names."
  }
  require(receivedNames.toSet() == expectedThumbnailPartNames()) {
    "thumbnail parts must exactly match thumbnail metadata."
  }

  val photosByPartName =
    photos
      .filter { it.thumbnail != null }
      .associateBy { "thumbnail_${it.id}" }
  parts.forEach { part ->
    val photo = requireNotNull(photosByPartName[part.name])
    val metadata = requireNotNull(photo.thumbnail)
    require(part.bytes.size <= PhotoSyncPolicy.MAX_THUMBNAIL_BYTES) {
      "thumbnail exceeds the per-file size limit."
    }
    require(part.bytes.size == metadata.byteSize) {
      "thumbnail byte count does not match metadata."
    }
    require(part.bytes.sha256() == metadata.sha256) {
      "thumbnail digest does not match metadata."
    }
    require(part.bytes.isWebpContainer()) { "thumbnail is not a WebP container." }
  }
}

fun PhotoSyncResponse.validateFor(request: PhotoSyncRequest): PhotoSyncResponse {
  require(schemaVersion == 1) { "schemaVersion must be 1." }
  require(accepted.size == accepted.toSet().size) { "accepted must not contain duplicate IDs." }
  accepted.forEach { validateUlid(it, "accepted") }

  val requestIds = request.photos.map { it.id }
  require(accepted.all { it in requestIds }) { "accepted IDs must be a subset of the request." }
  val acceptedPositions = accepted.map(requestIds::indexOf)
  require(acceptedPositions == acceptedPositions.sorted()) {
    "accepted IDs must preserve request order."
  }
  return this
}

private fun ByteArray.sha256(): String =
  MessageDigest
    .getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun ByteArray.isWebpContainer(): Boolean =
  size >= 12 &&
    copyOfRange(0, 4).decodeToString() == "RIFF" &&
    copyOfRange(8, 12).decodeToString() == "WEBP"

private val LOWERCASE_SHA256 = Regex("^[0-9a-f]{64}$")
