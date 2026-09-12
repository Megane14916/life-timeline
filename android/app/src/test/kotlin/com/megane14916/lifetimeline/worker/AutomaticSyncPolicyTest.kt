package com.megane14916.lifetimeline.worker

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticSyncPolicyTest {
  @Test
  fun keepsPhaseThreeSchedulingAndBatchValuesStable() {
    assertEquals("life_timeline_usage_collection_v1", AutomaticSyncPolicy.COLLECTION_WORK_NAME)
    assertEquals("life_timeline_app_session_sync_v1", AutomaticSyncPolicy.SYNC_WORK_NAME)
    assertEquals(15L, AutomaticSyncPolicy.COLLECTION_INTERVAL_MINUTES)
    assertEquals(5L, AutomaticSyncPolicy.COLLECTION_FLEX_MINUTES)
    assertEquals(15L, AutomaticSyncPolicy.SYNC_BACKOFF_MINUTES)
    assertEquals(15L, AutomaticSyncPolicy.LEASE_TTL_MINUTES)
    assertEquals(5L, AutomaticSyncPolicy.MAX_CLOCK_SKEW_MINUTES)
    assertEquals(100, AutomaticSyncPolicy.BATCH_SIZE)
    assertEquals(20, AutomaticSyncPolicy.MAX_BATCHES_PER_RUN)
    assertEquals(8L, AutomaticSyncPolicy.MAX_RUN_MINUTES)
    assertEquals("life_timeline_photo_collection_v1", PhotoWorkPolicy.COLLECTION_WORK_NAME)
    assertEquals("life_timeline_photo_collection_now_v1", PhotoWorkPolicy.IMMEDIATE_COLLECTION_WORK_NAME)
    assertEquals("life_timeline_photo_sync_v1", PhotoWorkPolicy.SYNC_WORK_NAME)
    assertEquals("photo_collection_v1", PhotoWorkPolicy.COLLECTION_LEASE_KEY)
    assertEquals("photo_sync_v1", PhotoWorkPolicy.SYNC_LEASE_KEY)
    assertEquals(15L, PhotoWorkPolicy.COLLECTION_INTERVAL_MINUTES)
    assertEquals(5L, PhotoWorkPolicy.COLLECTION_FLEX_MINUTES)
    assertEquals(30L, PhotoWorkPolicy.SYNC_BACKOFF_MINUTES)
    assertEquals(15L, PhotoWorkPolicy.LEASE_TTL_MINUTES)
    assertEquals(10, PhotoWorkPolicy.MAX_BATCHES_PER_RUN)
    assertEquals(8L, PhotoWorkPolicy.MAX_RUN_MINUTES)
  }
}
