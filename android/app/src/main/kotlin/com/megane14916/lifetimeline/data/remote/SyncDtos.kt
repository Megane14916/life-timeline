package com.megane14916.lifetimeline.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class SyncAppSessionsRequest(
  val schemaVersion: Int,
  val device: SyncDeviceDto,
  val apps: List<SyncAppDto>,
  val sessions: List<SyncAppSessionDto>,
)

@Serializable
data class SyncDeviceDto(
  val id: String,
  val name: String,
  val platform: String,
)

@Serializable
data class SyncAppDto(
  val id: String,
  val identifier: String,
  val displayName: String,
)

@Serializable
data class SyncAppSessionDto(
  val id: String,
  val appId: String,
  val startedAtMs: Long,
  val endedAtMs: Long,
  val durationMs: Long,
  val source: String,
)

@Serializable
data class SyncAppSessionsResponse(
  val schemaVersion: Int,
  val accepted: List<String>,
)

@Serializable
data class SyncErrorResponse(
  val error: SyncErrorDto,
)

@Serializable
data class SyncErrorDto(
  val code: String,
  val message: String,
  val field: String? = null,
)

@Serializable
data class SyncContractFixture(
  val endpoint: String,
  val contentType: String,
  val maxSessions: Int,
  val request: SyncAppSessionsRequest,
  val success: SyncAppSessionsResponse,
  val errors: List<SyncErrorFixture>,
)

@Serializable
data class SyncErrorFixture(
  val status: Int,
  val payload: SyncErrorResponse,
)

val SyncContractJson =
  kotlinx.serialization.json.Json {
    ignoreUnknownKeys = false
    explicitNulls = true
  }
