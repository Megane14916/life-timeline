package com.megane14916.lifetimeline.data.remote

import com.megane14916.lifetimeline.domain.validateUlid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

object LocationSyncPolicy {
  const val MAX_LOCATIONS_PER_BATCH = 200
  const val MAX_REQUEST_BYTES = 262_144
  const val SOURCE = "android_fused_location"
}

@Serializable
data class LocationSyncRequest(
  val schemaVersion: Int,
  val device: LocationSyncDevice,
  val locations: List<LocationSyncLocation>,
)

@Serializable
data class LocationSyncDevice(
  val id: String,
  val name: String,
  val platform: String,
)

@Serializable
data class LocationSyncLocation(
  val id: String,
  val recordedAtMs: Long,
  val latitude: Double,
  val longitude: Double,
  val accuracyM: Double?,
  val altitudeM: Double?,
  val speedMps: Double?,
  val source: String,
)

@Serializable
data class LocationSyncResponse(
  val schemaVersion: Int,
  val accepted: List<String>,
)

@Serializable
data class LocationSyncPolicyContract(
  val maxLocationsPerBatch: Int,
  val maxRequestBytes: Int,
  val source: String,
)

@Serializable
data class LocationSyncErrorFixture(
  val status: Int,
  val payload: SyncErrorResponse,
)

@Serializable
data class LocationSyncContractFixture(
  val endpoint: String,
  val contentType: String,
  val policy: LocationSyncPolicyContract,
  val request: LocationSyncRequest,
  val success: LocationSyncResponse,
  val errors: List<LocationSyncErrorFixture>,
)

val LocationSyncContractJson =
  Json {
    ignoreUnknownKeys = false
    explicitNulls = true
  }

fun LocationSyncRequest.validateContract(): LocationSyncRequest {
  require(schemaVersion == 1) { "schemaVersion must be 1." }
  validateUlid(device.id, "device.id")
  require(device.name.isNotBlank()) { "device.name must not be blank." }
  require(device.name.codePointCount(0, device.name.length) <= 200) {
    "device.name must be at most 200 characters."
  }
  require(device.platform == "android") { "device.platform must be android." }
  require(locations.size in 1..LocationSyncPolicy.MAX_LOCATIONS_PER_BATCH) {
    "locations must contain between 1 and ${LocationSyncPolicy.MAX_LOCATIONS_PER_BATCH} items."
  }

  val locationIds = locations.map { it.id }
  require(locationIds.size == locationIds.toSet().size) { "locations must not contain duplicate IDs." }

  locations.forEach { location ->
    validateUlid(location.id, "location.id")
    require(location.recordedAtMs >= 0) { "location.recordedAtMs must be nonnegative." }
    require(location.latitude.isFinite() && location.latitude in -90.0..90.0) {
      "location.latitude is out of range."
    }
    require(location.longitude.isFinite() && location.longitude in -180.0..180.0) {
      "location.longitude is out of range."
    }
    require(location.accuracyM == null || (location.accuracyM.isFinite() && location.accuracyM >= 0)) {
      "location.accuracyM must be finite and nonnegative when set."
    }
    require(location.altitudeM == null || location.altitudeM.isFinite()) {
      "location.altitudeM must be finite when set."
    }
    require(location.speedMps == null || (location.speedMps.isFinite() && location.speedMps >= 0)) {
      "location.speedMps must be finite and nonnegative when set."
    }
    require(location.source == LocationSyncPolicy.SOURCE) { "location.source is unsupported." }
  }
  return this
}

fun LocationSyncResponse.validateFor(request: LocationSyncRequest): LocationSyncResponse {
  require(schemaVersion == 1) { "schemaVersion must be 1." }
  require(accepted.size == accepted.toSet().size) { "accepted must not contain duplicate IDs." }
  accepted.forEach { validateUlid(it, "accepted") }

  val requestIds = request.locations.map { it.id }
  require(accepted.all { it in requestIds }) { "accepted IDs must be a subset of the request." }
  val acceptedPositions = accepted.map(requestIds::indexOf)
  require(acceptedPositions == acceptedPositions.sorted()) {
    "accepted IDs must preserve request order."
  }
  return this
}
