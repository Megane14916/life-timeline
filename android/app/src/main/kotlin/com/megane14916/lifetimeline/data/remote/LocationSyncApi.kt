package com.megane14916.lifetimeline.data.remote

import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

interface LocationSyncApi {
  @POST("api/v1/sync/locations")
  suspend fun syncLocations(
    @Body request: LocationSyncRequest,
  ): Response<LocationSyncResponse>
}

fun interface LocationSyncUploader {
  suspend fun upload(request: LocationSyncRequest): LocationSyncResponse
}

enum class LocationSyncFailureKind {
  NETWORK,
  SERVER,
  PROTOCOL,
}

class LocationSyncRemoteException(
  val kind: LocationSyncFailureKind,
  val retryable: Boolean,
) : Exception("Location sync request failed.")

/** Sends only the validated Location contract and classifies HTTP failures without retaining bodies. */
class RetrofitLocationSyncUploader(
  private val api: LocationSyncApi,
) : LocationSyncUploader {
  override suspend fun upload(request: LocationSyncRequest): LocationSyncResponse {
    val encoded = LocationSyncContractJson.encodeToString(request)
    require(encoded.toByteArray(Charsets.UTF_8).size <= LocationSyncPolicy.MAX_REQUEST_BYTES) {
      "Location request exceeds the contract byte limit."
    }
    val response = api.syncLocations(request)
    if (!response.isSuccessful) {
      val retryable = response.code() in RETRYABLE_STATUS_CODES || response.code() in 500..599
      throw LocationSyncRemoteException(
        kind = if (retryable) LocationSyncFailureKind.SERVER else LocationSyncFailureKind.PROTOCOL,
        retryable = retryable,
      )
    }
    return response.body() ?: throw LocationSyncRemoteException(LocationSyncFailureKind.PROTOCOL, retryable = false)
  }

  companion object {
    private val RETRYABLE_STATUS_CODES = setOf(408, 425, 429)
  }
}

object LocationSyncApiFactory {
  fun create(
    baseUrl: String,
    retrofitBuilder: Retrofit.Builder = Retrofit.Builder(),
  ): LocationSyncApi =
    retrofitBuilder
      .baseUrl(baseUrl)
      .addConverterFactory(LocationSyncContractJson.asConverterFactory(JSON_MEDIA_TYPE))
      .build()
      .create(LocationSyncApi::class.java)

  private val JSON_MEDIA_TYPE = "application/json".toMediaType()
}
