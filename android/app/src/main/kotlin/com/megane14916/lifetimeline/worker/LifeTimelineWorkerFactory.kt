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

/**
 * Creates workers from application-scoped dependencies without coupling WorkManager to Activities.
 * P3-04 and P3-05 will register the concrete worker creators.
 */
class LifeTimelineWorkerFactory(
  private val dependencies: WorkerDependencies,
  private val creators: Map<String, WorkerCreator> = emptyMap(),
) : WorkerFactory() {
  override fun createWorker(
    appContext: Context,
    workerClassName: String,
    workerParameters: WorkerParameters,
  ): ListenableWorker? = creators[workerClassName]?.create(appContext, workerParameters, dependencies)
}
