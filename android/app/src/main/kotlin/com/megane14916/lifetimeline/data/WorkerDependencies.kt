package com.megane14916.lifetimeline.data

import android.content.Context
import com.megane14916.lifetimeline.repository.BackgroundExecutionCoordinator
import com.megane14916.lifetimeline.repository.CollectionCoordinator
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
)
