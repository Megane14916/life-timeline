package com.megane14916.lifetimeline

import com.megane14916.lifetimeline.data.remote.PhotoSyncContractFixture
import com.megane14916.lifetimeline.data.remote.PhotoSyncContractJson
import com.megane14916.lifetimeline.data.remote.PhotoSyncPolicy
import com.megane14916.lifetimeline.data.remote.PhotoSyncRequest
import com.megane14916.lifetimeline.data.remote.PhotoSyncResponse
import com.megane14916.lifetimeline.data.remote.PhotoThumbnailPart
import com.megane14916.lifetimeline.data.remote.expectedThumbnailPartNames
import com.megane14916.lifetimeline.data.remote.validateContract
import com.megane14916.lifetimeline.data.remote.validateFor
import com.megane14916.lifetimeline.data.remote.validateThumbnailParts
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

@OptIn(ExperimentalSerializationApi::class)
class PhotoSyncContractFixtureTest {
  @Test
  fun parsesAndSerializesTheSharedVersionOneFixture() {
    val fixture = PhotoSyncContractJson.decodeFromString<PhotoSyncContractFixture>(fixtureText())
    val request = fixture.request.validateContract()
    val response = fixture.success.validateFor(request)

    assertEquals("/api/v1/sync/photos", fixture.endpoint)
    assertEquals("multipart/form-data", fixture.contentType)
    assertEquals("metadata", fixture.metadataPart.name)
    assertEquals("application/json", fixture.metadataPart.contentType)
    assertEquals("thumbnail_<photo-id>", fixture.thumbnailPartNameFormat)
    assertEquals("thumbnail.webp", fixture.thumbnailPartFilename)
    assertEquals(20, fixture.policy.maxPhotosPerBatch)
    assertEquals(PhotoSyncPolicy.MAX_PHOTOS_PER_BATCH, fixture.policy.maxPhotosPerBatch)
    assertEquals(PhotoSyncPolicy.MAX_THUMBNAIL_BYTES, fixture.policy.maxThumbnailBytes)
    assertEquals(PhotoSyncPolicy.MAX_REQUEST_BYTES, fixture.policy.maxRequestBytes)
    assertEquals(
      PhotoSyncPolicy.MAX_THUMBNAIL_DIMENSION_PX,
      fixture.policy.maxThumbnailDimensionPx,
    )
    assertEquals(PhotoSyncPolicy.THUMBNAIL_MIME_TYPE, fixture.policy.thumbnailMimeType)
    assertEquals(PhotoSyncPolicy.THUMBNAIL_QUALITY, fixture.policy.thumbnailQuality)
    assertEquals(PhotoSyncPolicy.MAX_FILENAME_LENGTH, fixture.policy.maxFilenameLength)
    assertEquals(setOf("thumbnail_${request.photos.first().id}"), request.expectedThumbnailPartNames())
    assertEquals(request.photos.map { it.id }, response.accepted)
    assertEquals(listOf(413, 422, 409, 503, 500), fixture.errors.map { it.status })

    val source = Json.parseToJsonElement(fixtureText()).jsonObject
    assertEquals(source["request"], Json.parseToJsonElement(PhotoSyncContractJson.encodeToString(request)))
    assertEquals(source["success"], Json.parseToJsonElement(PhotoSyncContractJson.encodeToString(response)))
  }

  @Test
  fun rejectsUnknownFieldsAcrossTheNestedContract() {
    val json =
      fixtureText().replaceFirst(
        "\"source\": \"android_media_store\"",
        "\"unexpected\": \"value\", \"source\": \"android_media_store\"",
      )

    assertThrows(SerializationException::class.java) {
      PhotoSyncContractJson.decodeFromString<PhotoSyncContractFixture>(json)
    }
  }

  @Test
  fun rejectsDuplicateIdsAndMismatchedMultipartFields() {
    val request = fixture().request.validateContract()
    val firstPhoto = request.photos.first()
    val duplicateRequest = request.copy(photos = listOf(firstPhoto, request.photos.last().copy(id = firstPhoto.id)))

    assertThrows(IllegalArgumentException::class.java) { duplicateRequest.validateContract() }
    assertThrows(IllegalArgumentException::class.java) {
      request.validateThumbnailParts(emptyList())
    }
    assertThrows(IllegalArgumentException::class.java) {
      request.validateThumbnailParts(
        listOf(
          PhotoThumbnailPart("thumbnail_${firstPhoto.id}", thumbnailBytes()),
          PhotoThumbnailPart("thumbnail_${firstPhoto.id}", thumbnailBytes()),
        ),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      request.validateThumbnailParts(
        listOf(PhotoThumbnailPart("thumbnail_unknown", thumbnailBytes())),
      )
    }
  }

  @Test
  fun validatesFixtureWebpDigestAndRejectsHashOrSizeMismatch() {
    val request = fixture().request.validateContract()
    val photo = request.photos.first()
    val bytes = thumbnailBytes()
    request.validateThumbnailParts(listOf(PhotoThumbnailPart("thumbnail_${photo.id}", bytes)))

    val wrongHash =
      request.copy(
        photos =
          listOf(
            photo.copy(thumbnail = photo.thumbnail?.copy(sha256 = "0".repeat(64))),
            request.photos.last(),
          ),
      )
    assertThrows(IllegalArgumentException::class.java) {
      wrongHash.validateThumbnailParts(listOf(PhotoThumbnailPart("thumbnail_${photo.id}", bytes)))
    }

    val wrongSize =
      request.copy(
        photos =
          listOf(
            photo.copy(thumbnail = photo.thumbnail?.copy(byteSize = bytes.size + 1)),
            request.photos.last(),
          ),
      )
    assertThrows(IllegalArgumentException::class.java) {
      wrongSize.validateThumbnailParts(listOf(PhotoThumbnailPart("thumbnail_${photo.id}", bytes)))
    }
  }

  @Test
  fun acceptsPartialAckAndRejectsUnknownDuplicateOrMissingAck() {
    val request = fixture().request.validateContract()
    val firstId = request.photos.first().id
    PhotoSyncResponse(schemaVersion = 1, accepted = listOf(firstId)).validateFor(request)

    assertThrows(IllegalArgumentException::class.java) {
      PhotoSyncResponse(schemaVersion = 1, accepted = listOf("01K4N70E3Q6N9D6E6G0C8M2H1R"))
        .validateFor(request)
    }
    assertThrows(IllegalArgumentException::class.java) {
      PhotoSyncResponse(schemaVersion = 1, accepted = listOf(firstId, firstId)).validateFor(request)
    }
    assertThrows(SerializationException::class.java) {
      PhotoSyncContractJson.decodeFromString<PhotoSyncResponse>("""{"schemaVersion":1}""")
    }
    assertTrue(request.photos.last().thumbnail == null)
  }

  private fun fixture(): PhotoSyncContractFixture = PhotoSyncContractJson.decodeFromString<PhotoSyncContractFixture>(fixtureText())

  private fun fixtureStream(): InputStream = checkNotNull(javaClass.classLoader?.getResourceAsStream("sync/photos-v1.json"))

  private fun fixtureText(): String = fixtureStream().bufferedReader().use { it.readText() }

  private fun thumbnailBytes(): ByteArray =
    checkNotNull(
      javaClass.classLoader?.getResourceAsStream("sync/fixtures/synthetic-thumbnail.webp"),
    ).use { it.readBytes() }
}
