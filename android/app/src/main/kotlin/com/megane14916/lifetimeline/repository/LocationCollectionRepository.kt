package com.megane14916.lifetimeline.repository

import com.megane14916.lifetimeline.data.local.LocationPointEntity
import com.megane14916.lifetimeline.domain.generateDeterministicUlid
import com.megane14916.lifetimeline.domain.validateUlid
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.roundToLong

/** Android-framework-free representation of one Fused Location fix. */
data class LocationFix(
  val recordedAtMs: Long,
  val latitude: Double,
  val longitude: Double,
  val accuracyM: Double? = null,
  val altitudeM: Double? = null,
  val speedMps: Double? = null,
  val elapsedRealtimeNanos: Long,
)

data class LocationPointInsertResult(
  val inserted: Boolean,
  val existing: LocationPointEntity? = null,
)

/** Each call must insert its full batch atomically and report the row found for ignored inserts. */
interface LocationPointStore {
  suspend fun insertBatchIfAbsent(points: List<LocationPointEntity>): List<LocationPointInsertResult>

  suspend fun getPendingBatch(limit: Int): List<LocationPointEntity>

  suspend fun countPending(): Int

  suspend fun latestReceivedAt(): Long? = null

  suspend fun recordLatestReceivedAt(receivedAtMs: Long) {}

  suspend fun markPendingAsSynced(
    ids: List<String>,
    syncedAtMs: Long,
  ): Int

  suspend fun deleteSyncedBatch(limit: Int): Int
}

data class LocationCollectionResult(
  val insertedCount: Int,
  val duplicateCount: Int,
  val invalidCount: Int,
  val conflictCount: Int,
)

data class LocationAcknowledgementResult(
  val markedSyncedCount: Int,
  val cleanedCount: Int,
)

/** Validates, orders, fingerprints, and persists Fused fixes without depending on Android APIs. */
class LocationCollectionRepository(
  private val store: LocationPointStore,
  private val nowProvider: () -> Long = System::currentTimeMillis,
) {
  suspend fun persist(
    fixes: List<LocationFix>,
    deviceId: String,
    collectionStartedAtMs: Long,
    nowMs: Long = nowProvider(),
  ): LocationCollectionResult {
    validateUlid(deviceId, "deviceId")
    require(collectionStartedAtMs >= 0) { "Collection start time must be non-negative." }
    require(nowMs >= 0) { "Current time must be non-negative." }

    var invalid = 0
    val normalized =
      fixes
        .mapIndexedNotNull { index, fix ->
          val point = normalize(fix, deviceId, collectionStartedAtMs, nowMs)
          if (point == null) invalid += 1
          point?.let { index to it }
        }.sortedWith(
          compareBy<Pair<Int, LocationPointEntity>> { it.second.recordedAtMs }
            .thenBy { it.second.elapsedRealtimeNanos }
            .thenBy { (it.second.latitude * COORDINATE_SCALE).roundToLong() }
            .thenBy { (it.second.longitude * COORDINATE_SCALE).roundToLong() }
            .thenBy { it.first },
        ).map { it.second }

    var inserted = 0
    var duplicates = 0
    var conflicts = 0
    for (chunk in normalized.chunked(MAX_PERSIST_BATCH)) {
      val outcomes = store.insertBatchIfAbsent(chunk)
      check(outcomes.size == chunk.size) { "Location store returned an incomplete batch result." }
      outcomes.forEachIndexed { index, outcome ->
        if (outcome.inserted) {
          inserted += 1
        } else if (outcome.existing?.hasSameContentAs(chunk[index]) == true) {
          duplicates += 1
        } else {
          // Never replace a row when a deterministic ID/fingerprint is associated with other content.
          conflicts += 1
        }
      }
    }
    if (normalized.isNotEmpty()) store.recordLatestReceivedAt(nowMs)
    return LocationCollectionResult(inserted, duplicates, invalid, conflicts)
  }

  suspend fun pendingBatch(limit: Int = MAX_PENDING_BATCH): List<LocationPointEntity> {
    require(limit in 1..MAX_PENDING_BATCH) { "Pending batch limit must be between 1 and $MAX_PENDING_BATCH." }
    return store.getPendingBatch(limit)
  }

  suspend fun countPending(): Int = store.countPending()

  suspend fun latestReceivedAt(): Long? = store.latestReceivedAt()

  /** Validates ACK scope before any mutation, then marks only pending sent IDs and cleans boundedly. */
  suspend fun acknowledge(
    sentIds: List<String>,
    acceptedIds: List<String>,
    syncedAtMs: Long,
  ): LocationAcknowledgementResult {
    require(sentIds.size <= MAX_PENDING_BATCH) { "Sent IDs exceed the maximum Location batch size." }
    require(sentIds.size == sentIds.toSet().size) { "Sent IDs must not contain duplicates." }
    require(acceptedIds.size == acceptedIds.toSet().size) { "Accepted IDs must not contain duplicates." }
    require(syncedAtMs >= 0) { "Sync timestamp must be non-negative." }
    sentIds.forEach { validateUlid(it, "sentId") }
    acceptedIds.forEach { validateUlid(it, "acceptedId") }
    require(acceptedIds.all { it in sentIds }) { "Accepted IDs must be a subset of the sent batch." }

    val recoveredCleanup = store.deleteSyncedBatch(MAX_CLEANUP_BATCH)
    val marked = if (acceptedIds.isEmpty()) 0 else store.markPendingAsSynced(acceptedIds, syncedAtMs)
    val cleanup = store.deleteSyncedBatch(MAX_CLEANUP_BATCH)
    return LocationAcknowledgementResult(marked, recoveredCleanup + cleanup)
  }

  suspend fun cleanupSynced(): Int = store.deleteSyncedBatch(MAX_CLEANUP_BATCH)

  private fun normalize(
    fix: LocationFix,
    deviceId: String,
    collectionStartedAtMs: Long,
    nowMs: Long,
  ): LocationPointEntity? {
    if (fix.recordedAtMs !in 0..MAX_ULID_TIMESTAMP || fix.recordedAtMs < collectionStartedAtMs) return null
    if (fix.recordedAtMs > nowMs && fix.recordedAtMs - nowMs > FUTURE_TOLERANCE_MS) return null
    if (fix.elapsedRealtimeNanos < 0 || !fix.latitude.isFinite() || !fix.longitude.isFinite()) return null
    if (fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0) return null

    val latitudeE7 = (fix.latitude * COORDINATE_SCALE).roundToLong()
    val longitudeE7 = (fix.longitude * COORDINATE_SCALE).roundToLong()
    val latitude = latitudeE7 / COORDINATE_SCALE
    val longitude = longitudeE7 / COORDINATE_SCALE
    val fingerprint =
      sha256Hex("location-v1:${fix.recordedAtMs}:${fix.elapsedRealtimeNanos}:$latitudeE7:$longitudeE7")
    val idEntropy = sha256("$deviceId$fingerprint")

    return LocationPointEntity(
      id = generateDeterministicUlid(fix.recordedAtMs, idEntropy),
      recordedAtMs = fix.recordedAtMs,
      latitude = latitude,
      longitude = longitude,
      accuracyM = fix.accuracyM.validOptionalOrNull(),
      altitudeM = fix.altitudeM.validOptionalOrNull(),
      speedMps = fix.speedMps.validOptionalOrNull(),
      elapsedRealtimeNanos = fix.elapsedRealtimeNanos,
      sourceFingerprint = fingerprint,
      receivedAtMs = nowMs,
    )
  }

  private fun LocationPointEntity.hasSameContentAs(other: LocationPointEntity): Boolean =
    id == other.id &&
      source == other.source &&
      recordedAtMs == other.recordedAtMs &&
      latitude == other.latitude &&
      longitude == other.longitude &&
      accuracyM == other.accuracyM &&
      altitudeM == other.altitudeM &&
      speedMps == other.speedMps &&
      elapsedRealtimeNanos == other.elapsedRealtimeNanos &&
      sourceFingerprint == other.sourceFingerprint

  private fun Double?.validOptionalOrNull(): Double? = this?.takeIf { it.isFinite() && it >= 0.0 }

  private fun sha256Hex(value: String): String = sha256(value).joinToString("") { "%02x".format(it) }

  private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))

  private companion object {
    const val COORDINATE_SCALE = 10_000_000.0
    const val FUTURE_TOLERANCE_MS = 5 * 60 * 1000L
    const val MAX_PERSIST_BATCH = 200
    const val MAX_PENDING_BATCH = 200
    const val MAX_CLEANUP_BATCH = 500
    const val MAX_ULID_TIMESTAMP = (1L shl 48) - 1
  }
}
