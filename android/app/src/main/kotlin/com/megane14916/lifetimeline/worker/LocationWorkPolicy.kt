package com.megane14916.lifetimeline.worker

import com.megane14916.lifetimeline.data.remote.LocationSyncPolicy

object LocationWorkPolicy {
  const val REGISTRATION_WORK_NAME = "life_timeline_location_registration_v1"
  const val WATCHDOG_WORK_NAME = "life_timeline_location_registration_watchdog_v1"
  const val SYNC_WORK_NAME = "life_timeline_location_sync_v1"
  const val REGISTRATION_LEASE_KEY = "location_registration_v1"
  const val SYNC_LEASE_KEY = "location_sync_v1"
  const val WATCHDOG_INTERVAL_HOURS = 12L
  const val WATCHDOG_FLEX_HOURS = 1L
  const val SYNC_BACKOFF_MINUTES = 15L
  const val LEASE_TTL_MINUTES = 15L
  const val MAX_BATCHES_PER_RUN = 20
  const val MAX_RUN_MINUTES = 8L
  const val MAX_LOCATIONS_PER_BATCH = LocationSyncPolicy.MAX_LOCATIONS_PER_BATCH
}
