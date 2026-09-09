package com.megane14916.lifetimeline

import com.megane14916.lifetimeline.data.remote.SyncContractFixture
import com.megane14916.lifetimeline.data.remote.SyncContractJson
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

@OptIn(ExperimentalSerializationApi::class)
class SyncContractFixtureTest {
  @Test
  fun parsesSharedVersionOneFixture() {
    val fixture = SyncContractJson.decodeFromStream<SyncContractFixture>(fixtureStream())
    val request = fixture.request
    val session = request.sessions.single()

    assertEquals("/api/v1/sync/app-sessions", fixture.endpoint)
    assertEquals("application/json", fixture.contentType)
    assertEquals(100, fixture.maxSessions)
    assertEquals(1, request.schemaVersion)
    assertEquals("android", request.device.platform)
    assertEquals("android_usage_stats", session.source)
    assertEquals(session.endedAtMs - session.startedAtMs, session.durationMs)
    assertEquals(listOf(session.id), fixture.success.accepted)
    assertEquals(listOf(422, 409, 503, 500), fixture.errors.map { it.status })
    assertTrue(
      fixture.errors.all {
        it.payload.error.message
          .isNotBlank()
      },
    )
  }

  @Test
  fun rejectsUnknownContractFields() {
    val json =
      fixtureStream()
        .bufferedReader()
        .use { it.readText() }
        .replaceFirst("\"endpoint\":", "\"unexpected\":\"value\",\"endpoint\":")

    try {
      SyncContractJson.decodeFromString<SyncContractFixture>(json)
      throw AssertionError("unknown fixture fields must be rejected")
    } catch (error: kotlinx.serialization.SerializationException) {
      assertTrue(error.message.orEmpty().contains("unexpected"))
    }
  }

  private fun fixtureStream(): InputStream =
    checkNotNull(
      javaClass.classLoader?.getResourceAsStream("sync/app-sessions-v1.json"),
    )
}
