package com.megane14916.lifetimeline.data

import android.content.Context
import android.os.Build
import androidx.room.Room
import com.megane14916.lifetimeline.collector.AndroidPackageLabelResolver
import com.megane14916.lifetimeline.collector.AndroidUsageEventsSource
import com.megane14916.lifetimeline.collector.UsageAccessChecker
import com.megane14916.lifetimeline.collector.UsageEventMapper
import com.megane14916.lifetimeline.collector.UsageEventsCollector
import com.megane14916.lifetimeline.data.local.LifeTimelineDatabase
import com.megane14916.lifetimeline.data.preferences.AppPreferences
import com.megane14916.lifetimeline.data.remote.SyncApiFactory
import com.megane14916.lifetimeline.data.remote.SyncAppDto
import com.megane14916.lifetimeline.data.remote.SyncContractJson
import com.megane14916.lifetimeline.data.remote.SyncDeviceDto
import com.megane14916.lifetimeline.repository.CollectionCoordinator
import com.megane14916.lifetimeline.repository.CollectionRepository
import com.megane14916.lifetimeline.repository.LocalDataRepository
import com.megane14916.lifetimeline.repository.SyncRepository
import com.megane14916.lifetimeline.worker.LifeTimelineWorkerFactory
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
  val localDataRepository: LocalDataRepository
  val workerDependencies: WorkerDependencies
  val workerFactory: LifeTimelineWorkerFactory

  fun createCollectionCoordinator(context: Context): CollectionCoordinator

  suspend fun createSyncRepository(
    context: Context,
    endpoint: String,
  ): SyncRepository
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
    Room
      .databaseBuilder(context, LifeTimelineDatabase::class.java, DATABASE_NAME)
      .addMigrations(LifeTimelineDatabase.MIGRATION_1_2)
      .build()
  }
  override val preferences: AppPreferences = AppPreferences.create(context)
  override val localDataRepository: LocalDataRepository by lazy { LocalDataRepository(database) }
  override val workerDependencies: WorkerDependencies by lazy {
    WorkerDependencies(
      collectionCoordinatorFactory = ::createCollectionCoordinator,
      syncRepositoryFactory = ::createSyncRepository,
    )
  }
  override val workerFactory: LifeTimelineWorkerFactory by lazy {
    LifeTimelineWorkerFactory(workerDependencies)
  }

  override fun createCollectionCoordinator(context: Context): CollectionCoordinator {
    val accessChecker = UsageAccessChecker.from(context)
    val collector =
      UsageEventsCollector(
        accessChecker = accessChecker,
        source = AndroidUsageEventsSource.from(context),
        mapper = UsageEventMapper(Build.VERSION.SDK_INT),
        labelResolver = AndroidPackageLabelResolver(context),
        selfPackageName = context.packageName,
      )
    return CollectionCoordinator(
      database = database,
      preferences = preferences,
      accessChecker = accessChecker,
      collector = collector,
      collectionRepository = CollectionRepository(database),
    )
  }

  override suspend fun createSyncRepository(
    context: Context,
    endpoint: String,
  ): SyncRepository {
    val localRepository = localDataRepository
    return SyncRepository(
      pendingStore = localRepository,
      appProvider = { ids ->
        localRepository.getAppsByIds(ids.toList()).map { app ->
          SyncAppDto(
            id = app.id,
            identifier = app.packageName,
            displayName = app.displayName,
          )
        }
      },
      syncApi =
        SyncApiFactory.create(
          baseUrl = endpoint,
          retrofitBuilder = Retrofit.Builder().client(httpClient),
        ),
      device =
        SyncDeviceDto(
          id = preferences.ensureDeviceId(),
          name = deviceName(context),
          platform = "android",
        ),
    )
  }

  private fun deviceName(context: Context): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android device" }

  private companion object {
    const val DATABASE_NAME = "lifetimeline.db"
    const val PLACEHOLDER_BASE_URL = "https://placeholder.invalid/"
    val JSON_MEDIA_TYPE = "application/json".toMediaType()
  }
}
