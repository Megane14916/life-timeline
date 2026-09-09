package com.megane14916.lifetimeline.data

import com.megane14916.lifetimeline.data.remote.SyncContractJson
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

interface AppContainer {
  val json: Json
  val httpClient: OkHttpClient
  val retrofit: Retrofit
}

class DefaultAppContainer : AppContainer {
  override val json: Json = SyncContractJson
  override val httpClient: OkHttpClient = OkHttpClient.Builder().build()
  override val retrofit: Retrofit =
    Retrofit
      .Builder()
      .baseUrl(PLACEHOLDER_BASE_URL)
      .client(httpClient)
      .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE))
      .build()

  private companion object {
    const val PLACEHOLDER_BASE_URL = "https://placeholder.invalid/"
    val JSON_MEDIA_TYPE = "application/json".toMediaType()
  }
}
