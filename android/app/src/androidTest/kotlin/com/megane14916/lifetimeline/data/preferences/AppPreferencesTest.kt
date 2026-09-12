package com.megane14916.lifetimeline.data.preferences

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megane14916.lifetimeline.domain.validateUlid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppPreferencesTest {
  @Test
  fun preservesDeviceIdAndPcEndpointAcrossReads() =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<android.content.Context>()
      val preferences = AppPreferences.forTest(context, "preferences-test")

      val firstId = preferences.ensureDeviceId()
      assertEquals(firstId, preferences.ensureDeviceId())
      assertEquals(firstId, validateUlid(firstId))

      preferences.setPcBaseUrl("https://pc.example.ts.net///")

      assertEquals("https://pc.example.ts.net/", preferences.getPcBaseUrl())
      assertEquals(firstId, preferences.settings.first().deviceId)
      assertNotEquals(null, preferences.settings.first().pcBaseUrl)
    }

  @Test
  fun photoCollectionOptInStoresStartTimeWithoutPersistingOsPermissionState() =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<android.content.Context>()
      val preferences = AppPreferences.forTest(context, "photo-preferences-test")

      val initial = preferences.settings.first()
      assertFalse(initial.photoCollectionEnabled)
      assertNull(initial.photoCollectionStartedAtMs)

      preferences.enablePhotoCollection(1_800_000_000_000)
      assertTrue(preferences.settings.first().photoCollectionEnabled)
      assertEquals(1_800_000_000_000, preferences.settings.first().photoCollectionStartedAtMs)

      preferences.enablePhotoCollection(1_800_000_010_000)
      assertEquals(1_800_000_000_000, preferences.settings.first().photoCollectionStartedAtMs)

      preferences.disablePhotoCollection()
      assertFalse(preferences.settings.first().photoCollectionEnabled)
      preferences.enablePhotoCollection(1_800_000_020_000)
      assertTrue(preferences.settings.first().photoCollectionEnabled)
      assertEquals(1_800_000_020_000, preferences.settings.first().photoCollectionStartedAtMs)
    }
}
