package com.megane14916.lifetimeline.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.megane14916.lifetimeline.data.WorkerDependencies

fun interface WorkerCreator {
  fun create(
    appContext: Context,
    workerParameters: WorkerParameters,
    dependencies: WorkerDependencies,
  ): ListenableWorker
}

/** Creates workers from application-scoped dependencies without coupling WorkManager to Activities. */
class LifeTimelineWorkerFactory(
  private val dependencies: WorkerDependencies,
  private val creators: Map<String, WorkerCreator> =
    mapOf(
      UsageCollectionWorker::class.java.name to
        WorkerCreator { appContext, workerParameters, workerDependencies ->
          UsageCollectionWorker(appContext, workerParameters, workerDependencies)
        },
      AppSessionSyncWorker::class.java.name to
        WorkerCreator { appContext, workerParameters, workerDependencies ->
          AppSessionSyncWorker(appContext, workerParameters, workerDependencies)
        },
    ),
) : WorkerFactory() {
  override fun createWorker(
    appContext: Context,
    workerClassName: String,
    workerParameters: WorkerParameters,
  ): ListenableWorker? = creators[workerClassName]?.create(appContext, workerParameters, dependencies)
}
