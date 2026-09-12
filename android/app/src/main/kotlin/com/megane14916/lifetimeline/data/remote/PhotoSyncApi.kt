package com.megane14916.lifetimeline.data.remote

import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface PhotoSyncApi {
  @Multipart
  @POST("api/v1/sync/photos")
  suspend fun syncPhotos(
    @Part("metadata") metadata: RequestBody,
    @Part thumbnails: List<MultipartBody.Part>,
  ): Response<PhotoSyncResponse>
}

fun interface PhotoSyncUploader {
  suspend fun upload(
    request: PhotoSyncRequest,
    thumbnails: List<PhotoThumbnailPart>,
  ): PhotoSyncResponse
}

class PhotoSyncRemoteException(
  val kind: PhotoSyncFailureKind,
  val retryable: Boolean,
) : Exception("Photo sync request failed.")

enum class PhotoSyncFailureKind {
  NETWORK,
  SERVER,
  PROTOCOL,
}

/** Encodes only the contract metadata and generated thumbnail bytes into the multipart request. */
class RetrofitPhotoSyncUploader(
  private val api: PhotoSyncApi,
) : PhotoSyncUploader {
  override suspend fun upload(
    request: PhotoSyncRequest,
    thumbnails: List<PhotoThumbnailPart>,
  ): PhotoSyncResponse {
    val metadata = PhotoSyncContractJson.encodeToString(request)
    val metadataBody = metadata.toRequestBody(JSON_MEDIA_TYPE)
    val thumbnailParts =
      thumbnails.map { thumbnail ->
        MultipartBody.Part.createFormData(
          thumbnail.name,
          THUMBNAIL_PART_FILENAME,
          thumbnail.bytes.toRequestBody(WEBP_MEDIA_TYPE),
        )
      }
    val response = api.syncPhotos(metadataBody, thumbnailParts)
    if (!response.isSuccessful) {
      val status = response.code()
      val retryable = status == 408 || status == 429 || status >= 500
      throw PhotoSyncRemoteException(
        kind = if (retryable) PhotoSyncFailureKind.SERVER else PhotoSyncFailureKind.PROTOCOL,
        retryable = retryable,
      )
    }
    return response.body() ?: throw PhotoSyncRemoteException(PhotoSyncFailureKind.PROTOCOL, retryable = false)
  }

  companion object {
    const val THUMBNAIL_PART_FILENAME = "thumbnail.webp"
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    private val WEBP_MEDIA_TYPE = PhotoSyncPolicy.THUMBNAIL_MIME_TYPE.toMediaType()
  }
}

object PhotoSyncApiFactory {
  fun create(
    baseUrl: String,
    retrofitBuilder: Retrofit.Builder = Retrofit.Builder(),
  ): PhotoSyncApi =
    retrofitBuilder
      .baseUrl(baseUrl)
      .addConverterFactory(PhotoSyncContractJson.asConverterFactory(JSON_MEDIA_TYPE))
      .build()
      .create(PhotoSyncApi::class.java)

  private val JSON_MEDIA_TYPE = "application/json".toMediaType()
}
