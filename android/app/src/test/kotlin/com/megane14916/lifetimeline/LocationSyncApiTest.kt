package com.megane14916.lifetimeline

import com.megane14916.lifetimeline.data.remote.LocationSyncApi
import com.megane14916.lifetimeline.data.remote.LocationSyncDevice
import com.megane14916.lifetimeline.data.remote.LocationSyncFailureKind
import com.megane14916.lifetimeline.data.remote.LocationSyncPolicy
import com.megane14916.lifetimeline.data.remote.LocationSyncRemoteException
import com.megane14916.lifetimeline.data.remote.LocationSyncRequest
import com.megane14916.lifetimeline.data.remote.LocationSyncResponse
import com.megane14916.lifetimeline.data.remote.RetrofitLocationSyncUploader
import com.megane14916.lifetimeline.domain.generateUlid
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.Response

class LocationSyncApiTest {
  @Test
  fun retriesOnlyTransientHttpFailures() {
    listOf(408, 425, 429, 500, 503).forEach { status ->
      val exception =
        assertThrows(LocationSyncRemoteException::class.java) {
          runSuspend { uploader(status).upload(request()) }
        }
      assertEquals(status.toString(), true, exception.retryable)
      assertEquals(LocationSyncFailureKind.SERVER, exception.kind)
    }

    listOf(400, 404, 409, 413, 422).forEach { status ->
      val exception =
        assertThrows(LocationSyncRemoteException::class.java) {
          runSuspend { uploader(status).upload(request()) }
        }
      assertEquals(status.toString(), false, exception.retryable)
      assertEquals(LocationSyncFailureKind.PROTOCOL, exception.kind)
    }
  }

  @Test
  fun successfulResponseRequiresNonNullBody() {
    val exception =
      assertThrows(LocationSyncRemoteException::class.java) {
        runSuspend {
          RetrofitLocationSyncUploader(
            object : LocationSyncApi {
              override suspend fun syncLocations(request: LocationSyncRequest): Response<LocationSyncResponse> = Response.success(null)
            },
          ).upload(request())
        }
      }
    assertEquals(LocationSyncFailureKind.PROTOCOL, exception.kind)
    assertEquals(false, exception.retryable)
  }

  private fun uploader(status: Int) =
    RetrofitLocationSyncUploader(
      object : LocationSyncApi {
        override suspend fun syncLocations(request: LocationSyncRequest): Response<LocationSyncResponse> =
          Response.error(
            status,
            "error".toResponseBody("text/plain".toMediaType()),
          )
      },
    )

  private fun request() =
    LocationSyncRequest(
      schemaVersion = 1,
      device = LocationSyncDevice(generateUlid(1_000L), "Test", "android"),
      locations =
        listOf(
          com.megane14916.lifetimeline.data.remote.LocationSyncLocation(
            id = generateUlid(2_000L),
            recordedAtMs = 2_000L,
            latitude = 35.0,
            longitude = 139.0,
            accuracyM = null,
            altitudeM = null,
            speedMps = null,
            source = LocationSyncPolicy.SOURCE,
          ),
        ),
    )

  private companion object {
    fun <T> runSuspend(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }
  }
}
