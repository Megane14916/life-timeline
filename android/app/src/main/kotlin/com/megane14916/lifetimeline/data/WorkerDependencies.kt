package com.megane14916.lifetimeline.data

import android.content.Context
import com.megane14916.lifetimeline.collector.PhotoAccessChecker
import com.megane14916.lifetimeline.repository.BackgroundExecutionCoordinator
import com.megane14916.lifetimeline.repository.CollectionCoordinator
import com.megane14916.lifetimeline.repository.PhotoCollectionRepository
import com.megane14916.lifetimeline.repository.PhotoSyncRepository
import com.megane14916.lifetimeline.repository.SyncRepository

/** Application-scoped dependency factories shared by UI and background workers. */
data class WorkerDependencies(
  val collectionCoordinatorFactory: (Context) -> CollectionCoordinator,
  val syncRepositoryFactory: suspend (Context, String) -> SyncRepository,
  val backgroundExecutionCoordinatorFactory: (Context) -> BackgroundExecutionCoordinator = {
    error("BackgroundExecutionCoordinator factory is not configured.")
  },
  val pcBaseUrlProvider: suspend () -> String? = { null },
  val syncTrigger: () -> Unit = {},
  val syncSuccessRecorder: suspend (Long) -> Unit = {},
  val photoAccessCheckerFactory: (Context) -> PhotoAccessChecker = {
    error("PhotoAccessChecker factory is not configured.")
  },
  val photoCollectionRepositoryFactory: (Context) -> PhotoCollectionRepository = {
    error("PhotoCollectionRepository factory is not configured.")
  },
  val photoCollectionEnabledProvider: suspend () -> Boolean = { false },
  val photoCollectionStartedAtProvider: suspend () -> Long? = { null },
  val photoSyncRepositoryFactory: suspend (Context, String) -> PhotoSyncRepository = { _, _ ->
    error("PhotoSyncRepository factory is not configured.")
  },
  val photoCollectionTrigger: () -> Unit = {},
  val photoSyncTrigger: () -> Unit = {},
  val pendingPhotoSyncableCountProvider: suspend () -> Int = { 0 },
  val pendingPhotoThumbnailCountProvider: suspend () -> Int = { 0 },
)
