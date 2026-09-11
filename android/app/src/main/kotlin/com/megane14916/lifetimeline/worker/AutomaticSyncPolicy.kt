package com.megane14916.lifetimeline.worker

/** Stable values shared by the background scheduler and workers. */
object AutomaticSyncPolicy {
  const val COLLECTION_WORK_NAME = "life_timeline_usage_collection_v1"
  const val SYNC_WORK_NAME = "life_timeline_app_session_sync_v1"

  const val COLLECTION_INTERVAL_MINUTES = 15L
  const val COLLECTION_FLEX_MINUTES = 5L
  const val SYNC_BACKOFF_MINUTES = 15L
  const val LEASE_TTL_MINUTES = 15L
  const val BATCH_SIZE = 100
  const val MAX_BATCHES_PER_RUN = 20
  const val MAX_RUN_MINUTES = 8L
}
