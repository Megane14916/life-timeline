package com.megane14916.lifetimeline.session

import com.megane14916.lifetimeline.domain.OpenActivityState
import com.megane14916.lifetimeline.domain.SessionInterval
import com.megane14916.lifetimeline.domain.UsageEventKind
import com.megane14916.lifetimeline.domain.UsageEventRecord

data class SessionizerDiagnostics(
  val duplicateResumes: Int = 0,
  val unmatchedPauses: Int = 0,
  val invalidPackages: Int = 0,
  val invalidTimestamps: Int = 0,
  val invalidIntervals: Int = 0,
  val unsupportedEvents: Int = 0,
  val discardedOpenActivities: Int = 0,
)

data class SessionizerResult(
  val sessions: List<SessionInterval>,
  val openActivities: List<OpenActivityState>,
  val diagnostics: SessionizerDiagnostics,
)

class AppSessionizer {
  fun process(
    events: List<UsageEventRecord>,
    initialOpenActivities: List<OpenActivityState> = emptyList(),
  ): SessionizerResult {
    val activeActivities = linkedMapOf<String, OpenActivityState>()
    val packageStarts = linkedMapOf<String, Long>()
    var diagnostics = SessionizerDiagnostics()

    initialOpenActivities
      .sortedWith(compareBy<OpenActivityState> { it.startedAtMs }.thenBy { it.startEventKey })
      .forEach { activity ->
        if (activity.packageName.isBlank() || activity.startedAtMs < 0) {
          diagnostics =
            diagnostics.copy(
              invalidPackages = diagnostics.invalidPackages + if (activity.packageName.isBlank()) 1 else 0,
              invalidTimestamps = diagnostics.invalidTimestamps + if (activity.startedAtMs < 0) 1 else 0,
            )
        } else if (activeActivities.putIfAbsent(activity.activityKey, activity) != null) {
          diagnostics = diagnostics.copy(duplicateResumes = diagnostics.duplicateResumes + 1)
        } else {
          packageStarts[activity.packageName] =
            minOf(packageStarts[activity.packageName] ?: activity.startedAtMs, activity.startedAtMs)
        }
      }

    val sessions = mutableListOf<SessionInterval>()
    val orderedEvents = events.sortedWith(compareBy<UsageEventRecord> { it.timestampMs }.thenBy { it.eventKey })
    orderedEvents.groupBy { it.timestampMs }.forEach { (timestampMs, eventsAtTimestamp) ->
      if (timestampMs < 0) {
        diagnostics = diagnostics.copy(invalidTimestamps = diagnostics.invalidTimestamps + eventsAtTimestamp.size)
        return@forEach
      }

      eventsAtTimestamp.forEach { event ->
        when (event.kind) {
          UsageEventKind.ACTIVITY_RESUMED -> {
            if (event.packageName.isBlank()) {
              diagnostics = diagnostics.copy(invalidPackages = diagnostics.invalidPackages + 1)
              return@forEach
            }
            val activityKey = activityKey(event.packageName, event.className)
            if (activeActivities.containsKey(activityKey)) {
              diagnostics = diagnostics.copy(duplicateResumes = diagnostics.duplicateResumes + 1)
              return@forEach
            }
            activeActivities[activityKey] =
              OpenActivityState(
                activityKey = activityKey,
                packageName = event.packageName,
                className = event.className,
                startedAtMs = timestampMs,
                startEventKey = event.eventKey,
              )
            packageStarts.putIfAbsent(event.packageName, timestampMs)
          }

          UsageEventKind.ACTIVITY_PAUSED -> {
            if (event.packageName.isBlank()) {
              diagnostics = diagnostics.copy(invalidPackages = diagnostics.invalidPackages + 1)
              return@forEach
            }
            val activityKey = activityKey(event.packageName, event.className)
            if (activeActivities.remove(activityKey) == null) {
              diagnostics = diagnostics.copy(unmatchedPauses = diagnostics.unmatchedPauses + 1)
            }
          }

          UsageEventKind.SCREEN_NON_INTERACTIVE,
          UsageEventKind.DEVICE_SHUTDOWN,
          -> {
            closeAllPackages(timestampMs, activeActivities, packageStarts, sessions) { count ->
              diagnostics = diagnostics.copy(invalidIntervals = diagnostics.invalidIntervals + count)
            }
          }

          UsageEventKind.DEVICE_STARTUP -> {
            diagnostics =
              diagnostics.copy(discardedOpenActivities = diagnostics.discardedOpenActivities + activeActivities.size)
            activeActivities.clear()
            packageStarts.clear()
          }
        }
      }

      packageStarts.keys.toList().forEach { packageName ->
        if (activeActivities.values.none { it.packageName == packageName }) {
          closePackage(packageName, timestampMs, packageStarts, sessions) { count ->
            diagnostics = diagnostics.copy(invalidIntervals = diagnostics.invalidIntervals + count)
          }
        }
      }
    }

    return SessionizerResult(
      sessions = sessions.sortedWith(compareBy<SessionInterval> { it.startedAtMs }.thenBy { it.endedAtMs }.thenBy { it.packageName }),
      openActivities = activeActivities.values.sortedWith(compareBy<OpenActivityState> { it.startedAtMs }.thenBy { it.activityKey }),
      diagnostics = diagnostics,
    )
  }

  private fun activityKey(
    packageName: String,
    className: String,
  ): String = "$packageName|$className"

  private fun closeAllPackages(
    endedAtMs: Long,
    activeActivities: MutableMap<String, OpenActivityState>,
    packageStarts: MutableMap<String, Long>,
    sessions: MutableList<SessionInterval>,
    onInvalidInterval: (Int) -> Unit,
  ) {
    packageStarts.keys.toList().forEach { packageName ->
      closePackage(packageName, endedAtMs, packageStarts, sessions, onInvalidInterval)
    }
    activeActivities.clear()
  }

  private fun closePackage(
    packageName: String,
    endedAtMs: Long,
    packageStarts: MutableMap<String, Long>,
    sessions: MutableList<SessionInterval>,
    onInvalidInterval: (Int) -> Unit,
  ) {
    val startedAtMs = packageStarts.remove(packageName) ?: return
    if (endedAtMs <= startedAtMs) {
      onInvalidInterval(1)
    } else {
      sessions += SessionInterval(packageName, startedAtMs, endedAtMs)
    }
  }
}
