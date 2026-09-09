package com.megane14916.lifetimeline.domain

data class AppSession(
  val id: String,
  val appId: String,
  val startedAtMs: Long,
  val endedAtMs: Long,
  val durationMs: Long,
  val source: String,
)

data class SessionInterval(
  val packageName: String,
  val startedAtMs: Long,
  val endedAtMs: Long,
) {
  val durationMs: Long
    get() = endedAtMs - startedAtMs
}

data class OpenActivityState(
  val activityKey: String,
  val packageName: String,
  val className: String,
  val startedAtMs: Long,
  val startEventKey: String,
)

fun appSessionSourceKey(
  collectorVersion: String,
  deviceId: String,
  packageName: String,
  startedAtMs: Long,
  endedAtMs: Long,
): String =
  listOf(
    collectorVersion,
    deviceId,
    packageName,
    startedAtMs,
    endedAtMs,
  ).joinToString("|")
