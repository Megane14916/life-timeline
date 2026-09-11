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
    assertEquals(100, AutomaticSyncPolicy.BATCH_SIZE)
    assertEquals(20, AutomaticSyncPolicy.MAX_BATCHES_PER_RUN)
    assertEquals(8L, AutomaticSyncPolicy.MAX_RUN_MINUTES)
  }
}
