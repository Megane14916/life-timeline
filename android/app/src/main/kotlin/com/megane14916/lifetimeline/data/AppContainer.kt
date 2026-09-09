package com.megane14916.lifetimeline.data

import android.content.Context
import androidx.room.Room
import com.megane14916.lifetimeline.data.local.LifeTimelineDatabase
import com.megane14916.lifetimeline.data.preferences.AppPreferences
import com.megane14916.lifetimeline.data.remote.SyncContractJson
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

interface AppContainer {
  val json: Json
  val httpClient: OkHttpClient
  val retrofit: Retrofit
  val database: LifeTimelineDatabase
  val preferences: AppPreferences
}

class DefaultAppContainer(
  context: Context,
) : AppContainer {
  override val json: Json = SyncContractJson
  override val httpClient: OkHttpClient =
    OkHttpClient
      .Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(10, TimeUnit.SECONDS)
      .writeTimeout(10, TimeUnit.SECONDS)
      .build()
  override val retrofit: Retrofit =
    Retrofit
      .Builder()
      .baseUrl(PLACEHOLDER_BASE_URL)
      .client(httpClient)
      .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE))
      .build()
  override val database: LifeTimelineDatabase by lazy {
    Room.databaseBuilder(context, LifeTimelineDatabase::class.java, DATABASE_NAME).build()
  }
  override val preferences: AppPreferences = AppPreferences.create(context)

  private companion object {
    const val DATABASE_NAME = "lifetimeline.db"
    const val PLACEHOLDER_BASE_URL = "https://placeholder.invalid/"
    val JSON_MEDIA_TYPE = "application/json".toMediaType()
  }
}
