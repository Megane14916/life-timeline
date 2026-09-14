package com.megane14916.lifetimeline.data

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.work.WorkManager
import com.megane14916.lifetimeline.collector.AndroidMediaStorePhotoBackend
import com.megane14916.lifetimeline.collector.AndroidPackageLabelResolver
import com.megane14916.lifetimeline.collector.AndroidUsageEventsSource
import com.megane14916.lifetimeline.collector.LocationPermissionChecker
import com.megane14916.lifetimeline.collector.LocationRequestController
import com.megane14916.lifetimeline.collector.MediaStorePhotoSource
import com.megane14916.lifetimeline.collector.PhotoAccessChecker
import com.megane14916.lifetimeline.collector.PhotoThumbnailGenerator
import com.megane14916.lifetimeline.collector.UsageAccessChecker
import com.megane14916.lifetimeline.collector.UsageEventMapper
import com.megane14916.lifetimeline.collector.UsageEventsCollector
import com.megane14916.lifetimeline.data.local.LifeTimelineDatabase
import com.megane14916.lifetimeline.data.preferences.AppPreferences
import com.megane14916.lifetimeline.data.remote.LocationSyncApiFactory
import com.megane14916.lifetimeline.data.remote.LocationSyncDevice
import com.megane14916.lifetimeline.data.remote.PhotoSyncApiFactory
import com.megane14916.lifetimeline.data.remote.PhotoSyncDevice
import com.megane14916.lifetimeline.data.remote.RetrofitLocationSyncUploader
import com.megane14916.lifetimeline.data.remote.RetrofitPhotoSyncUploader
import com.megane14916.lifetimeline.data.remote.SyncApiFactory
import com.megane14916.lifetimeline.data.remote.SyncAppDto
import com.megane14916.lifetimeline.data.remote.SyncContractJson
import com.megane14916.lifetimeline.data.remote.SyncDeviceDto
import com.megane14916.lifetimeline.repository.BackgroundExecutionCoordinator
import com.megane14916.lifetimeline.repository.CollectionCoordinator
import com.megane14916.lifetimeline.repository.CollectionRepository
import com.megane14916.lifetimeline.repository.LocalDataRepository
import com.megane14916.lifetimeline.repository.LocalThumbnailStore
import com.megane14916.lifetimeline.repository.LocationCollectionRepository
import com.megane14916.lifetimeline.repository.LocationSyncRepository
import com.megane14916.lifetimeline.repository.LocationUpdateProcessor
import com.megane14916.lifetimeline.repository.PhotoCollectionRepository
import com.megane14916.lifetimeline.repository.PhotoSyncRepository
import com.megane14916.lifetimeline.repository.RoomLocationPointStore
import com.megane14916.lifetimeline.repository.SyncRepository
import com.megane14916.lifetimeline.worker.BackgroundWorkScheduler
import com.megane14916.lifetimeline.worker.LifeTimelineWorkerFactory
import kotlinx.coroutines.flow.first
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
  val backgroundWorkScheduler: BackgroundWorkScheduler
  val backgroundExecutionCoordinator: BackgroundExecutionCoordinator
  val photoCollectionRepository: PhotoCollectionRepository
  val locationCollectionRepository: LocationCollectionRepository
  val locationUpdateProcessor: LocationUpdateProcessor

  fun createCollectionCoordinator(context: Context): CollectionCoordinator

  suspend fun createSyncRepository(
    context: Context,
    endpoint: String,
  ): SyncRepository

  suspend fun createPhotoSyncRepository(
    context: Context,
    endpoint: String,
  ): PhotoSyncRepository

  suspend fun createLocationSyncRepository(
    context: Context,
    endpoint: String,
  ): LocationSyncRepository
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
      .addMigrations(
        LifeTimelineDatabase.MIGRATION_1_2,
        LifeTimelineDatabase.MIGRATION_2_3,
        LifeTimelineDatabase.MIGRATION_3_4,
        LifeTimelineDatabase.MIGRATION_4_5,
      ).addCallback(LifeTimelineDatabase.PHOTO_INTEGRITY_CALLBACK)
      .build()
  }
  override val preferences: AppPreferences = AppPreferences.create(context)
  override val localDataRepository: LocalDataRepository by lazy { LocalDataRepository(database) }
  override val backgroundExecutionCoordinator: BackgroundExecutionCoordinator by lazy {
    BackgroundExecutionCoordinator(database)
  }
  override val photoCollectionRepository: PhotoCollectionRepository by lazy {
    PhotoCollectionRepository(
      database = database,
      photoSource = MediaStorePhotoSource(Build.VERSION.SDK_INT, AndroidMediaStorePhotoBackend(context)),
      thumbnailGenerator = PhotoThumbnailGenerator(context),
      thumbnailStore = LocalThumbnailStore(java.io.File(context.filesDir, PHOTO_THUMBNAIL_DIRECTORY)),
    )
  }
  override val locationCollectionRepository: LocationCollectionRepository by lazy {
    LocationCollectionRepository(RoomLocationPointStore(database))
  }
  override val locationUpdateProcessor: LocationUpdateProcessor by lazy {
    LocationUpdateProcessor(
      settingsProvider = { preferences.settings.first() },
      permissionChecker = LocationPermissionChecker.from(context),
      repository = locationCollectionRepository,
      deviceIdProvider = preferences::ensureDeviceId,
    )
  }
  override val workerDependencies: WorkerDependencies by lazy {
    WorkerDependencies(
      collectionCoordinatorFactory = ::createCollectionCoordinator,
      syncRepositoryFactory = ::createSyncRepository,
      backgroundExecutionCoordinatorFactory = { backgroundExecutionCoordinator },
      pcBaseUrlProvider = preferences::getPcBaseUrl,
      syncTrigger = { backgroundWorkScheduler.enqueueSync() },
      syncSuccessRecorder = preferences::recordSync,
      photoAccessCheckerFactory = { photoContext -> PhotoAccessChecker.from(photoContext) },
      photoCollectionRepositoryFactory = { photoCollectionRepository },
      photoCollectionEnabledProvider = { preferences.settings.first().photoCollectionEnabled },
      photoCollectionStartedAtProvider = { preferences.settings.first().photoCollectionStartedAtMs },
      photoSyncRepositoryFactory = ::createPhotoSyncRepository,
      photoCollectionTrigger = { backgroundWorkScheduler.enqueuePhotoCollectionContinuation() },
      photoSyncTrigger = { backgroundWorkScheduler.enqueuePhotoSync() },
      pendingPhotoSyncableCountProvider = { database.androidMediaItemDao().countPendingSyncable() },
      pendingPhotoThumbnailCountProvider = { database.androidMediaItemDao().countPendingThumbnails() },
      locationPermissionCheckerFactory = { locationContext -> LocationPermissionChecker.from(locationContext) },
      locationRegistrationClientFactory = { locationContext ->
        LocationRequestController(locationContext, LocationPermissionChecker.from(locationContext))
      },
      locationCollectionEnabledProvider = { preferences.settings.first().locationCollectionEnabled },
      locationSyncRepositoryFactory = ::createLocationSyncRepository,
    )
  }
  override val workerFactory: LifeTimelineWorkerFactory by lazy {
    LifeTimelineWorkerFactory(workerDependencies)
  }
  override val backgroundWorkScheduler: BackgroundWorkScheduler by lazy {
    BackgroundWorkScheduler(WorkManager.getInstance(context))
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

  override suspend fun createPhotoSyncRepository(
    context: Context,
    endpoint: String,
  ): PhotoSyncRepository =
    PhotoSyncRepository(
      database = database,
      thumbnailStore = LocalThumbnailStore(java.io.File(context.filesDir, PHOTO_THUMBNAIL_DIRECTORY)),
      uploader =
        RetrofitPhotoSyncUploader(
          PhotoSyncApiFactory.create(
            baseUrl = endpoint,
            retrofitBuilder =
              Retrofit
                .Builder()
                .client(httpClient.newBuilder().callTimeout(90, TimeUnit.SECONDS).build()),
          ),
        ),
      device =
        PhotoSyncDevice(
          id = preferences.ensureDeviceId(),
          name = deviceName(context),
          platform = "android",
        ),
    )

  override suspend fun createLocationSyncRepository(
    context: Context,
    endpoint: String,
  ): LocationSyncRepository =
    LocationSyncRepository(
      collectionRepository = locationCollectionRepository,
      uploader =
        RetrofitLocationSyncUploader(
          LocationSyncApiFactory.create(
            baseUrl = endpoint,
            retrofitBuilder = Retrofit.Builder().client(httpClient),
          ),
        ),
      device =
        LocationSyncDevice(
          id = preferences.ensureDeviceId(),
          name = deviceName(context),
          platform = "android",
        ),
    )

  private fun deviceName(context: Context): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android device" }

  private companion object {
    const val DATABASE_NAME = "lifetimeline.db"
    const val PHOTO_THUMBNAIL_DIRECTORY = "photo-thumbnails"
    const val PLACEHOLDER_BASE_URL = "https://placeholder.invalid/"
    val JSON_MEDIA_TYPE = "application/json".toMediaType()
  }
}
