package com.megane14916.lifetimeline.worker

object LocationWorkPolicy {
  const val REGISTRATION_WORK_NAME = "life_timeline_location_registration_v1"
  const val WATCHDOG_WORK_NAME = "life_timeline_location_registration_watchdog_v1"
  const val REGISTRATION_LEASE_KEY = "location_registration_v1"
  const val WATCHDOG_INTERVAL_HOURS = 12L
  const val WATCHDOG_FLEX_HOURS = 1L
}
