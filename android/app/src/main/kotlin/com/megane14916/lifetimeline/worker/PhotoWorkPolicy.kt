package com.megane14916.lifetimeline.worker

import com.megane14916.lifetimeline.data.remote.PhotoSyncPolicy

/** Stable, photo-specific scheduling and lease values. Keep AppSession policy unchanged. */
object PhotoWorkPolicy {
  const val COLLECTION_WORK_NAME = "life_timeline_photo_collection_v1"
  const val IMMEDIATE_COLLECTION_WORK_NAME = "life_timeline_photo_collection_now_v1"
  const val SYNC_WORK_NAME = "life_timeline_photo_sync_v1"
  const val COLLECTION_LEASE_KEY = "photo_collection_v1"
  const val SYNC_LEASE_KEY = "photo_sync_v1"

  const val COLLECTION_INTERVAL_MINUTES = PhotoSyncPolicy.COLLECTION_INTERVAL_MINUTES
  const val COLLECTION_FLEX_MINUTES = PhotoSyncPolicy.COLLECTION_FLEX_MINUTES
  const val SYNC_BACKOFF_MINUTES = PhotoSyncPolicy.SYNC_BACKOFF_MINUTES
  const val LEASE_TTL_MINUTES = PhotoSyncPolicy.LEASE_TTL_MINUTES
  const val MAX_BATCHES_PER_RUN = PhotoSyncPolicy.MAX_BATCHES_PER_RUN
  const val MAX_RUN_MINUTES = PhotoSyncPolicy.MAX_RUN_MINUTES
}
