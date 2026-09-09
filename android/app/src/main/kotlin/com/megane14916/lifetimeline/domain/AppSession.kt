package com.megane14916.lifetimeline.domain

data class AppSession(
  val id: String,
  val appId: String,
  val startedAtMs: Long,
  val endedAtMs: Long,
  val durationMs: Long,
  val source: String,
)
