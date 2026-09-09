package com.megane14916.lifetimeline.collector

import android.app.usage.UsageEvents
import com.megane14916.lifetimeline.domain.UsageEventKind
import com.megane14916.lifetimeline.domain.usageEventKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageEventsCollectorTest {
  @Test
  fun mapsLegacyForegroundEventsAndKeepsScreenOffApiBoundary() {
    val mapper = UsageEventMapper(apiLevel = 27)

    assertEquals(
      UsageEventKind.ACTIVITY_RESUMED,
      mapper.map(rawEvent(100, UsageEvents.Event.MOVE_TO_FOREGROUND))?.kind,
    )
    assertEquals(
      UsageEventKind.ACTIVITY_PAUSED,
      mapper.map(rawEvent(200, UsageEvents.Event.MOVE_TO_BACKGROUND))?.kind,
    )
    assertEquals(null, mapper.map(rawEvent(300, UsageEvents.Event.SCREEN_NON_INTERACTIVE)))
  }

  @Test
  fun mapsModernActivityAndDeviceEvents() {
    val mapper = UsageEventMapper(apiLevel = 29)

    assertEquals(
      UsageEventKind.ACTIVITY_RESUMED,
      mapper.map(rawEvent(100, UsageEvents.Event.ACTIVITY_RESUMED))?.kind,
    )
    assertEquals(
      UsageEventKind.ACTIVITY_PAUSED,
      mapper.map(rawEvent(200, UsageEvents.Event.ACTIVITY_PAUSED))?.kind,
    )
    assertEquals(
      UsageEventKind.SCREEN_NON_INTERACTIVE,
      mapper.map(rawEvent(300, UsageEvents.Event.SCREEN_NON_INTERACTIVE))?.kind,
    )
    assertEquals(
      UsageEventKind.DEVICE_SHUTDOWN,
      mapper.map(rawEvent(400, UsageEvents.Event.DEVICE_SHUTDOWN))?.kind,
    )
    assertEquals(
      UsageEventKind.DEVICE_STARTUP,
      mapper.map(rawEvent(500, UsageEvents.Event.DEVICE_STARTUP))?.kind,
    )
    assertEquals(
      UsageEventKind.ACTIVITY_RESUMED,
      mapper.map(rawEvent(600, UsageEvents.Event.MOVE_TO_FOREGROUND))?.kind,
    )
  }

  @Test
  fun filtersSelfInvalidUnsupportedAndCursorEventsAndSortsByStableKey() {
    var sourceCalls = 0
    val source =
      UsageEventsSource { _, _ ->
        sourceCalls += 1
        listOf(
          rawEvent(200, UsageEvents.Event.ACTIVITY_RESUMED, "com.example.browser"),
          rawEvent(100, UsageEvents.Event.ACTIVITY_RESUMED, "com.example.browser"),
          rawEvent(150, UsageEvents.Event.ACTIVITY_RESUMED, "com.megane14916.lifetimeline"),
          rawEvent(250, 999, "com.example.unsupported"),
          rawEvent(300, UsageEvents.Event.ACTIVITY_RESUMED, null),
          rawEvent(600, UsageEvents.Event.ACTIVITY_RESUMED, "com.example.outside"),
        )
      }
    val collector =
      UsageEventsCollector(
        accessChecker = UsageAccessChecker { true },
        source = source,
        mapper = UsageEventMapper(apiLevel = 29),
        labelResolver =
          PackageLabelResolver { packageName ->
            if (packageName == "com.example.browser") "Browser" else packageName
          },
        selfPackageName = "com.megane14916.lifetimeline",
      )

    val result = collector.collect(UsageQueryWindow(100, 500))

    assertEquals(1, sourceCalls)
    assertEquals(UsageCollectionStatus.SUCCESS, result.status)
    assertEquals(listOf(100L, 200L), result.events.map { it.timestampMs })
    assertEquals("Browser", result.events.first().displayName)
    assertEquals(200L, result.nextCursorAtMs)
    assertEquals(result.events.last().eventKey, result.nextCursorKey)
    assertTrue(result.shouldAdvanceCursor)
    assertEquals(6, result.diagnostics.rawEventCount)
    assertEquals(2, result.diagnostics.mappedEventCount)
    assertEquals(1, result.diagnostics.ignoredSelfEvents)
    assertEquals(1, result.diagnostics.ignoredInvalidEvents)
    assertEquals(1, result.diagnostics.ignoredUnsupportedEvents)
    assertEquals(1, result.diagnostics.ignoredOutOfRangeEvents)

    val replay =
      collector.collect(
        UsageQueryWindow(
          beginAtMs = 100,
          endAtMs = 500,
          cursorAtMs = result.nextCursorAtMs!!,
          cursorKey = result.nextCursorKey!!,
        ),
      )
    assertEquals(UsageCollectionStatus.NO_DATA, replay.status)
    assertFalse(replay.shouldAdvanceCursor)
  }

  @Test
  fun permissionDeniedAndUnavailableQueriesDoNotAdvanceCursor() {
    var sourceCalls = 0
    val denied =
      UsageEventsCollector(
        accessChecker = UsageAccessChecker { false },
        source =
          UsageEventsSource { _, _ ->
            sourceCalls += 1
            emptyList()
          },
        mapper = UsageEventMapper(29),
        labelResolver = PackageLabelResolver { it },
        selfPackageName = "com.megane14916.lifetimeline",
      ).collect(UsageQueryWindow(100, 200))
    assertEquals(UsageCollectionStatus.PERMISSION_DENIED, denied.status)
    assertEquals(0, sourceCalls)
    assertFalse(denied.shouldAdvanceCursor)

    val unavailable =
      UsageEventsCollector(
        accessChecker = UsageAccessChecker { true },
        source = UsageEventsSource { _, _ -> throw SecurityException() },
        mapper = UsageEventMapper(29),
        labelResolver = PackageLabelResolver { it },
        selfPackageName = "com.megane14916.lifetimeline",
      ).collect(UsageQueryWindow(100, 200))
    assertEquals(UsageCollectionStatus.UNAVAILABLE, unavailable.status)
    assertFalse(unavailable.shouldAdvanceCursor)
  }

  @Test
  fun nullAndEmptyQueriesReturnNoDataWithoutCursor() {
    listOf<List<RawUsageEvent>?>(null, emptyList()).forEach { events ->
      val result =
        UsageEventsCollector(
          accessChecker = UsageAccessChecker { true },
          source = UsageEventsSource { _, _ -> events },
          mapper = UsageEventMapper(29),
          labelResolver = PackageLabelResolver { it },
          selfPackageName = "com.megane14916.lifetimeline",
        ).collect(UsageQueryWindow(100, 200))
      assertEquals(UsageCollectionStatus.NO_DATA, result.status)
      assertFalse(result.shouldAdvanceCursor)
    }
  }

  private fun rawEvent(
    timestampMs: Long,
    eventType: Int,
    packageName: String? = "com.example.browser",
  ) = RawUsageEvent(
    timestampMs = timestampMs,
    packageName = packageName,
    className = "MainActivity",
    eventType = eventType,
  )
}

class UsageAccessCheckerTest {
  @Test
  fun reflectsPermissionProviderAndExposesSettingsIntent() {
    val checker = UsageAccessChecker { false }
    assertFalse(checker.isUsageAccessGranted())
    assertEquals(
      "android.settings.USAGE_ACCESS_SETTINGS",
      checker.usageAccessSettingsAction(),
    )
    assertTrue(UsageAccessChecker { true }.isUsageAccessGranted())
  }
}
