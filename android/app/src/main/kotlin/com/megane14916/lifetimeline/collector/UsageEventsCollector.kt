package com.megane14916.lifetimeline.collector

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import com.megane14916.lifetimeline.domain.UsageEventKind
import com.megane14916.lifetimeline.domain.UsageEventRecord
import com.megane14916.lifetimeline.domain.usageEventKey

data class RawUsageEvent(
  val timestampMs: Long,
  val packageName: String?,
  val className: String?,
  val eventType: Int,
)

fun interface UsageEventsSource {
  fun queryEvents(
    beginAtMs: Long,
    endAtMs: Long,
  ): List<RawUsageEvent>?
}

fun interface PackageLabelResolver {
  fun labelFor(packageName: String): String
}

class AndroidUsageEventsSource(
  private val usageStatsManager: UsageStatsManager,
) : UsageEventsSource {
  override fun queryEvents(
    beginAtMs: Long,
    endAtMs: Long,
  ): List<RawUsageEvent>? {
    val usageEvents = usageStatsManager.queryEvents(beginAtMs, endAtMs) ?: return null
    val event = UsageEvents.Event()
    val records = mutableListOf<RawUsageEvent>()
    while (usageEvents.hasNextEvent()) {
      if (!usageEvents.getNextEvent(event)) {
        break
      }
      records +=
        RawUsageEvent(
          timestampMs = event.timeStamp,
          packageName = event.packageName,
          className = event.className,
          eventType = event.eventType,
        )
    }
    return records
  }

  companion object {
    fun from(context: Context): AndroidUsageEventsSource = AndroidUsageEventsSource(context.getSystemService(UsageStatsManager::class.java))
  }
}

class AndroidPackageLabelResolver(
  context: Context,
) : PackageLabelResolver {
  private val packageManager = context.packageManager

  override fun labelFor(packageName: String): String =
    try {
      packageManager
        .getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))
        .toString()
        .takeIf { it.isNotBlank() }
        ?: packageName
    } catch (_: PackageManager.NameNotFoundException) {
      packageName
    }
}

class UsageEventMapper(
  private val apiLevel: Int,
) {
  fun map(rawEvent: RawUsageEvent): UsageEventRecord? {
    val kind =
      if (apiLevel >= 29) {
        when (rawEvent.eventType) {
          UsageEvents.Event.ACTIVITY_RESUMED -> {
            UsageEventKind.ACTIVITY_RESUMED
          }

          UsageEvents.Event.ACTIVITY_PAUSED -> {
            UsageEventKind.ACTIVITY_PAUSED
          }

          UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
            if (apiLevel >= 28) UsageEventKind.SCREEN_NON_INTERACTIVE else null
          }

          UsageEvents.Event.DEVICE_SHUTDOWN -> {
            UsageEventKind.DEVICE_SHUTDOWN
          }

          UsageEvents.Event.DEVICE_STARTUP -> {
            UsageEventKind.DEVICE_STARTUP
          }

          else -> {
            null
          }
        }
      } else {
        when (rawEvent.eventType) {
          UsageEvents.Event.MOVE_TO_FOREGROUND -> {
            UsageEventKind.ACTIVITY_RESUMED
          }

          UsageEvents.Event.MOVE_TO_BACKGROUND -> {
            UsageEventKind.ACTIVITY_PAUSED
          }

          UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
            if (apiLevel >= 28) UsageEventKind.SCREEN_NON_INTERACTIVE else null
          }

          else -> {
            null
          }
        }
      }
    val packageName = rawEvent.packageName?.takeIf { it.isNotBlank() } ?: return null
    return kind?.let {
      UsageEventRecord(
        timestampMs = rawEvent.timestampMs,
        packageName = packageName,
        className = rawEvent.className.orEmpty(),
        displayName = packageName,
        kind = it,
        eventKey = usageEventKey(rawEvent.copy(packageName = packageName)),
      )
    }
  }
}

data class UsageQueryWindow(
  val beginAtMs: Long,
  val endAtMs: Long,
  val cursorAtMs: Long = beginAtMs,
  val cursorKey: String = "",
) {
  init {
    require(beginAtMs >= 0) { "beginAtMs must be non-negative" }
    require(endAtMs > beginAtMs) { "endAtMs must be after beginAtMs" }
    require(cursorAtMs >= 0) { "cursorAtMs must be non-negative" }
  }
}

data class UsageDiagnostics(
  val rawEventCount: Int = 0,
  val mappedEventCount: Int = 0,
  val ignoredSelfEvents: Int = 0,
  val ignoredInvalidEvents: Int = 0,
  val ignoredUnsupportedEvents: Int = 0,
  val ignoredOutOfRangeEvents: Int = 0,
)

enum class UsageCollectionStatus {
  SUCCESS,
  PERMISSION_DENIED,
  NO_DATA,
  UNAVAILABLE,
}

data class UsageCollectionResult(
  val status: UsageCollectionStatus,
  val events: List<UsageEventRecord> = emptyList(),
  val nextCursorAtMs: Long? = null,
  val nextCursorKey: String? = null,
  val diagnostics: UsageDiagnostics = UsageDiagnostics(),
) {
  val shouldAdvanceCursor: Boolean
    get() = status == UsageCollectionStatus.SUCCESS && events.isNotEmpty()
}

class UsageEventsCollector(
  private val accessChecker: UsageAccessChecker,
  private val source: UsageEventsSource,
  private val mapper: UsageEventMapper,
  private val labelResolver: PackageLabelResolver,
  private val selfPackageName: String,
) {
  fun collect(window: UsageQueryWindow): UsageCollectionResult {
    if (!accessChecker.isUsageAccessGranted()) {
      return UsageCollectionResult(status = UsageCollectionStatus.PERMISSION_DENIED)
    }

    val rawEvents =
      try {
        source.queryEvents(window.beginAtMs, window.endAtMs)
      } catch (_: SecurityException) {
        return UsageCollectionResult(status = UsageCollectionStatus.UNAVAILABLE)
      } catch (_: IllegalStateException) {
        return UsageCollectionResult(status = UsageCollectionStatus.UNAVAILABLE)
      } ?: return UsageCollectionResult(status = UsageCollectionStatus.NO_DATA)

    var diagnostics = UsageDiagnostics(rawEventCount = rawEvents.size)
    val events =
      buildList {
        rawEvents.forEach { rawEvent ->
          if (rawEvent.timestampMs !in window.beginAtMs until window.endAtMs) {
            diagnostics =
              diagnostics.copy(
                ignoredOutOfRangeEvents = diagnostics.ignoredOutOfRangeEvents + 1,
              )
            return@forEach
          }
          val eventKey = usageEventKey(rawEvent)
          if (
            rawEvent.timestampMs < window.cursorAtMs ||
            (rawEvent.timestampMs == window.cursorAtMs && eventKey <= window.cursorKey)
          ) {
            return@forEach
          }
          if (rawEvent.packageName == selfPackageName) {
            diagnostics =
              diagnostics.copy(
                ignoredSelfEvents = diagnostics.ignoredSelfEvents + 1,
              )
            return@forEach
          }
          val mapped = mapper.map(rawEvent)
          if (mapped == null) {
            diagnostics =
              diagnostics.copy(
                ignoredInvalidEvents =
                  diagnostics.ignoredInvalidEvents +
                    if (rawEvent.packageName.isNullOrBlank()) 1 else 0,
                ignoredUnsupportedEvents =
                  diagnostics.ignoredUnsupportedEvents +
                    if (rawEvent.packageName.isNullOrBlank()) 0 else 1,
              )
            return@forEach
          }
          diagnostics =
            diagnostics.copy(
              mappedEventCount = diagnostics.mappedEventCount + 1,
            )
          add(mapped.copy(displayName = labelResolver.labelFor(mapped.packageName)))
        }
      }.sortedWith(
        compareBy<UsageEventRecord> { it.timestampMs }.thenBy { it.eventKey },
      )

    if (events.isEmpty()) {
      return UsageCollectionResult(
        status = UsageCollectionStatus.NO_DATA,
        diagnostics = diagnostics,
      )
    }
    val lastEvent = events.last()
    return UsageCollectionResult(
      status = UsageCollectionStatus.SUCCESS,
      events = events,
      nextCursorAtMs = lastEvent.timestampMs,
      nextCursorKey = lastEvent.eventKey,
      diagnostics = diagnostics,
    )
  }
}
