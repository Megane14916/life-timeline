package com.megane14916.lifetimeline

import com.megane14916.lifetimeline.data.remote.LocationSyncContractFixture
import com.megane14916.lifetimeline.data.remote.LocationSyncContractJson
import com.megane14916.lifetimeline.data.remote.LocationSyncPolicy
import com.megane14916.lifetimeline.data.remote.LocationSyncResponse
import com.megane14916.lifetimeline.data.remote.validateContract
import com.megane14916.lifetimeline.data.remote.validateFor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.InputStream

@OptIn(ExperimentalSerializationApi::class)
class LocationSyncContractFixtureTest {
  @Test
  fun parsesAndSerializesTheSharedVersionOneFixture() {
    val fixture = LocationSyncContractJson.decodeFromString<LocationSyncContractFixture>(fixtureText())
    val request = fixture.request.validateContract()
    val response = fixture.success.validateFor(request)

    assertEquals("/api/v1/sync/locations", fixture.endpoint)
    assertEquals("application/json", fixture.contentType)
    assertEquals(LocationSyncPolicy.MAX_LOCATIONS_PER_BATCH, fixture.policy.maxLocationsPerBatch)
    assertEquals(LocationSyncPolicy.MAX_REQUEST_BYTES, fixture.policy.maxRequestBytes)
    assertEquals(LocationSyncPolicy.SOURCE, fixture.policy.source)
    assertEquals(request.locations.map { it.id }, response.accepted)
    assertEquals(listOf(413, 422, 409, 503, 500), fixture.errors.map { it.status })

    val source = Json.parseToJsonElement(fixtureText()).jsonObject
    assertEquals(source["request"], Json.parseToJsonElement(LocationSyncContractJson.encodeToString(request)))
    assertEquals(source["success"], Json.parseToJsonElement(LocationSyncContractJson.encodeToString(response)))
  }

  @Test
  fun rejectsUnknownFieldsAndInvalidRequestValues() {
    val unknown =
      fixtureText().replaceFirst(
        "\"source\": \"android_fused_location\"",
        "\"unexpected\": \"value\", \"source\": \"android_fused_location\"",
      )
    assertThrows(SerializationException::class.java) {
      LocationSyncContractJson.decodeFromString<LocationSyncContractFixture>(unknown)
    }

    val request = fixture().request.validateContract()
    assertThrows(IllegalArgumentException::class.java) {
      request.copy(locations = listOf(request.locations.single(), request.locations.single())).validateContract()
    }
    assertThrows(IllegalArgumentException::class.java) {
      request.copy(locations = listOf(request.locations.single().copy(latitude = 90.1))).validateContract()
    }
  }

  @Test
  fun acceptsPartialAckAndRejectsUnknownDuplicateOrMissingAck() {
    val request = fixture().request.validateContract()
    val id = request.locations.single().id
    LocationSyncResponse(schemaVersion = 1, accepted = listOf(id)).validateFor(request)

    assertThrows(IllegalArgumentException::class.java) {
      LocationSyncResponse(schemaVersion = 1, accepted = listOf("01K00000000000000000000003"))
        .validateFor(request)
    }
    assertThrows(IllegalArgumentException::class.java) {
      LocationSyncResponse(schemaVersion = 1, accepted = listOf(id, id)).validateFor(request)
    }
    assertThrows(SerializationException::class.java) {
      LocationSyncContractJson.decodeFromString<LocationSyncResponse>("""{"schemaVersion":1}""")
    }
  }

  private fun fixture(): LocationSyncContractFixture = LocationSyncContractJson.decodeFromString<LocationSyncContractFixture>(fixtureText())

  private fun fixtureStream(): InputStream = checkNotNull(javaClass.classLoader?.getResourceAsStream("sync/locations-v1.json"))

  private fun fixtureText(): String = fixtureStream().bufferedReader().use { it.readText() }
}
