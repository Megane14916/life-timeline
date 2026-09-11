package com.megane14916.lifetimeline.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megane14916.lifetimeline.data.local.BackgroundWorkStateEntity
import com.megane14916.lifetimeline.data.local.LifeTimelineDatabase
import com.megane14916.lifetimeline.worker.AutomaticSyncPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BackgroundExecutionCoordinatorTest {
  private lateinit var database: LifeTimelineDatabase

  @Before
  fun setUp() {
    database =
      Room
        .inMemoryDatabaseBuilder(
          ApplicationProvider.getApplicationContext(),
          LifeTimelineDatabase::class.java,
        ).allowMainThreadQueries()
        .build()
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun acquiresOnlyOneLeaseWhenTwoExecutionsStartTogether() =
    runBlocking {
      val first = coordinator(owner = "owner-a")
      val second = coordinator(owner = "owner-b")

      val leases =
        coroutineScope {
          listOf(
            async { first.acquire("work") },
            async { second.acquire("work") },
          ).awaitAll()
        }

      assertEquals(1, leases.count { it != null })
      assertNotNull(database.backgroundWorkStateDao().find("work")?.leaseOwner)
    }

  @Test
  fun heartbeatAndDiagnosticsRequireTheCurrentLeaseOwner() =
    runBlocking {
      var nowMs = 1_000_000L
      val coordinator = coordinator(owner = "owner-a", now = { nowMs })
      val lease = checkNotNull(coordinator.acquire("work"))
      val wrongLease = BackgroundLease("work", "owner-b")

      assertFalse(coordinator.heartbeat(wrongLease))
      assertFalse(coordinator.recordFailure(wrongLease, "retry", "network"))
      assertFalse(coordinator.release(wrongLease))

      nowMs += 1_000
      assertTrue(coordinator.heartbeat(lease))
      assertTrue(coordinator.recordFailure(lease, "retry", "network"))
      assertTrue(coordinator.recordSuccess(lease, "success"))

      val state = checkNotNull(coordinator.findState("work"))
      assertEquals("owner-a", state.leaseOwner)
      assertEquals("success", state.lastResult)
      assertNull(state.lastErrorKind)
      assertEquals(0, state.consecutiveFailures)
      assertEquals(nowMs, state.lastSuccessAtMs)

      assertTrue(coordinator.release(lease))
      val released = checkNotNull(coordinator.findState("work"))
      assertNull(released.leaseOwner)
      assertNull(released.leaseAcquiredAtMs)
      assertNull(released.leaseExpiresAtMs)
    }

  @Test
  fun expiredAndFutureLeasesAreReclaimedByTheNextExecution() =
    runBlocking {
      var nowMs = 2_000_000L
      val first = coordinator(owner = "owner-old", now = { nowMs })
      val second = coordinator(owner = "owner-new", now = { nowMs })
      checkNotNull(first.acquire("expired-work"))

      nowMs += TimeUnit.MINUTES.toMillis(AutomaticSyncPolicy.LEASE_TTL_MINUTES) + 1
      val reclaimedExpired = second.acquire("expired-work")
      assertNotNull(reclaimedExpired)
      assertEquals("owner-new", database.backgroundWorkStateDao().find("expired-work")?.leaseOwner)
      assertTrue(second.release(checkNotNull(reclaimedExpired)))

      database.backgroundWorkStateDao().insertIfAbsent(
        BackgroundWorkStateEntity(
          workKey = "future-work",
          leaseOwner = "owner-future",
          leaseAcquiredAtMs = nowMs + TimeUnit.MINUTES.toMillis(AutomaticSyncPolicy.MAX_CLOCK_SKEW_MINUTES) + 1,
          leaseExpiresAtMs = nowMs + TimeUnit.MINUTES.toMillis(30),
          lastAttemptAtMs = nowMs,
          updatedAtMs = nowMs,
        ),
      )
      val reclaimedFuture = second.acquire("future-work")

      assertNotNull(reclaimedFuture)
      assertEquals("owner-new", database.backgroundWorkStateDao().find("future-work")?.leaseOwner)
    }

  @Test
  fun processRecreationCanRecoverAStaleLease() =
    runBlocking {
      var nowMs = 3_000_000L
      val original = coordinator(owner = "owner-original", now = { nowMs })
      checkNotNull(original.acquire("work"))

      nowMs += TimeUnit.MINUTES.toMillis(AutomaticSyncPolicy.LEASE_TTL_MINUTES) + 1
      val recreated = coordinator(owner = "owner-recreated", now = { nowMs })

      val lease = recreated.acquire("work")

      assertNotNull(lease)
      assertEquals("owner-recreated", database.backgroundWorkStateDao().find("work")?.leaseOwner)
    }

  private fun coordinator(
    owner: String,
    now: () -> Long = { 1_000_000L },
  ) = BackgroundExecutionCoordinator(
    database = database,
    nowMs = now,
    ownerGenerator = { owner },
  )
}
