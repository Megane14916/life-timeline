package com.megane14916.lifetimeline.collector

import android.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoAccessCheckerTest {
  @Test
  fun legacyPermissionIsFullAccessThroughApi32() {
    for (apiLevel in listOf(26, 29, 30, 32)) {
      val state =
        PhotoAccessChecker.resolveAccess(apiLevel) { permission ->
          permission == Manifest.permission.READ_EXTERNAL_STORAGE
        }
      assertEquals("API $apiLevel", PhotoAccessState.FULL, state)
    }
  }

  @Test
  fun api33UsesReadImagesAndDoesNotReportPartialAccess() {
    assertEquals(
      PhotoAccessState.FULL,
      PhotoAccessChecker.resolveAccess(33) { it == Manifest.permission.READ_MEDIA_IMAGES },
    )
    assertEquals(
      PhotoAccessState.DENIED,
      PhotoAccessChecker.resolveAccess(33) { it == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED },
    )
  }

  @Test
  fun api34AndLaterDistinguishFullPartialAndDenied() {
    for (apiLevel in listOf(34, 36)) {
      assertEquals(
        "API $apiLevel full",
        PhotoAccessState.FULL,
        PhotoAccessChecker.resolveAccess(apiLevel) { it == Manifest.permission.READ_MEDIA_IMAGES },
      )
      assertEquals(
        "API $apiLevel partial",
        PhotoAccessState.PARTIAL,
        PhotoAccessChecker.resolveAccess(apiLevel) {
          it == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        },
      )
      assertEquals(
        "API $apiLevel denied",
        PhotoAccessState.DENIED,
        PhotoAccessChecker.resolveAccess(apiLevel) { false },
      )
    }
  }

  @Test
  fun requestsPermissionSetForEachPlatformGeneration() {
    assertArrayEquals(
      arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
      PhotoAccessChecker.permissionsFor(29),
    )
    assertArrayEquals(
      arrayOf(Manifest.permission.READ_MEDIA_IMAGES),
      PhotoAccessChecker.permissionsFor(33),
    )
    assertArrayEquals(
      arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
      ),
      PhotoAccessChecker.permissionsFor(34),
    )
  }
}
