package com.megane14916.lifetimeline.domain

import com.megane14916.lifetimeline.collector.RawUsageEvent

enum class UsageEventKind {
  ACTIVITY_RESUMED,
  ACTIVITY_PAUSED,
  SCREEN_NON_INTERACTIVE,
  DEVICE_SHUTDOWN,
  DEVICE_STARTUP,
}

data class UsageEventRecord(
  val timestampMs: Long,
  val packageName: String,
  val className: String,
  val displayName: String,
  val kind: UsageEventKind,
  val eventKey: String,
)

fun usageEventKey(rawEvent: RawUsageEvent): String =
  listOf(
    rawEvent.timestampMs,
    rawEvent.packageName.orEmpty(),
    rawEvent.className.orEmpty(),
    rawEvent.eventType,
  ).joinToString("|")
