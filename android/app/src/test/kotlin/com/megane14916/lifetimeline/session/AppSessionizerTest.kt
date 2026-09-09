package com.megane14916.lifetimeline.session

import com.megane14916.lifetimeline.domain.OpenActivityState
import com.megane14916.lifetimeline.domain.UsageEventKind
import com.megane14916.lifetimeline.domain.UsageEventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSessionizerTest {
  @Test
  fun closesPackageSessionAfterActivitySwitchWithoutSplittingAtSameTimestamp() {
    val result =
      AppSessionizer().process(
        listOf(
          event(100, UsageEventKind.ACTIVITY_RESUMED, "com.example.app", "FirstActivity"),
          event(200, UsageEventKind.ACTIVITY_PAUSED, "com.example.app", "FirstActivity"),
          event(200, UsageEventKind.ACTIVITY_RESUMED, "com.example.app", "SecondActivity"),
          event(300, UsageEventKind.ACTIVITY_PAUSED, "com.example.app", "SecondActivity"),
        ),
      )

    assertEquals(1, result.sessions.size)
    assertEquals("com.example.app", result.sessions.single().packageName)
    assertEquals(100L, result.sessions.single().startedAtMs)
    assertEquals(300L, result.sessions.single().endedAtMs)
    assertTrue(result.openActivities.isEmpty())
  }

  @Test
  fun keepsDuplicateResumeOpenAndClosesScreenOffAtItsTimestamp() {
    val result =
      AppSessionizer().process(
        listOf(
          event(100, UsageEventKind.ACTIVITY_RESUMED),
          event(150, UsageEventKind.ACTIVITY_RESUMED),
          event(250, UsageEventKind.SCREEN_NON_INTERACTIVE),
        ),
      )

    assertEquals(100L, result.sessions.single().startedAtMs)
    assertEquals(250L, result.sessions.single().endedAtMs)
    assertEquals(1, result.diagnostics.duplicateResumes)
    assertTrue(result.openActivities.isEmpty())
  }

  @Test
  fun shutdownClosesOpenStateButStartupDiscardsUncertainState() {
    val result =
      AppSessionizer().process(
        events =
          listOf(
            event(100, UsageEventKind.ACTIVITY_RESUMED),
            event(200, UsageEventKind.DEVICE_SHUTDOWN),
            event(300, UsageEventKind.ACTIVITY_RESUMED, className = "RecoveredActivity"),
            event(350, UsageEventKind.DEVICE_STARTUP),
          ),
      )

    assertEquals(1, result.sessions.size)
    assertEquals(200L, result.sessions.single().endedAtMs)
    assertEquals(1, result.diagnostics.discardedOpenActivities)
    assertTrue(result.openActivities.isEmpty())
  }

  @Test
  fun retainsUnfinishedActivitiesAndCountsInvalidEndEvents() {
    val initial =
      OpenActivityState(
        activityKey = "com.example.initial|InitialActivity",
        packageName = "com.example.initial",
        className = "InitialActivity",
        startedAtMs = 50,
        startEventKey = "50|initial",
      )
    val result =
      AppSessionizer().process(
        events =
          listOf(
            event(100, UsageEventKind.ACTIVITY_PAUSED),
            event(200, UsageEventKind.ACTIVITY_RESUMED, className = "OpenActivity"),
          ),
        initialOpenActivities = listOf(initial),
      )

    assertEquals(1, result.diagnostics.unmatchedPauses)
    assertEquals(2, result.openActivities.size)
    assertTrue(result.sessions.isEmpty())
  }

  private fun event(
    timestampMs: Long,
    kind: UsageEventKind,
    packageName: String = "com.example.app",
    className: String = "MainActivity",
  ) = UsageEventRecord(
    timestampMs = timestampMs,
    packageName = packageName,
    className = className,
    displayName = packageName,
    kind = kind,
    eventKey = "$timestampMs|$packageName|$className|${kind.ordinal}",
  )
}
