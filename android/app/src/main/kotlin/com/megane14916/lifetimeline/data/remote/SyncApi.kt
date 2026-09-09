package com.megane14916.lifetimeline.data.remote

import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

interface SyncApi {
  @POST("api/v1/sync/app-sessions")
  suspend fun syncAppSessions(
    @Body request: SyncAppSessionsRequest,
  ): Response<SyncAppSessionsResponse>
}

object SyncApiFactory {
  fun create(
    baseUrl: String,
    retrofitBuilder: Retrofit.Builder = Retrofit.Builder(),
  ): SyncApi =
    retrofitBuilder
      .baseUrl(baseUrl)
      .addConverterFactory(SyncContractJson.asConverterFactory(JSON_MEDIA_TYPE))
      .build()
      .create(SyncApi::class.java)

  private val JSON_MEDIA_TYPE = "application/json".toMediaType()
}
