package com.megane14916.lifetimeline

import com.megane14916.lifetimeline.data.preferences.AppPreferences
import com.megane14916.lifetimeline.domain.generateUlid
import com.megane14916.lifetimeline.domain.validateUlid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UlidAndEndpointTest {
  @Test
  fun generatesValidUniqueUlids() {
    val first = generateUlid(nowMs = 1_780_000_000_000)
    val second = generateUlid(nowMs = 1_780_000_000_000)

    assertEquals(26, first.length)
    assertEquals(first, validateUlid(first))
    assertNotEquals(first, second)
  }

  @Test
  fun rejectsInvalidUlids() {
    assertThrows(IllegalArgumentException::class.java) { validateUlid("not-a-ulid") }
    assertThrows(IllegalArgumentException::class.java) {
      validateUlid("81K4N6Q2N6N8YJ7W4M2D3A9B5C")
    }
  }

  @Test
  fun normalizesAndValidatesPcEndpoint() {
    assertEquals(
      "https://pc.example.ts.net/api/",
      AppPreferences.normalizePcBaseUrl(" https://pc.example.ts.net/api/// "),
    )
    assertThrows(IllegalArgumentException::class.java) {
      AppPreferences.normalizePcBaseUrl("http://pc.example.ts.net")
    }
    assertThrows(IllegalArgumentException::class.java) {
      AppPreferences.normalizePcBaseUrl("https://user:pass@pc.example.ts.net")
    }
    assertThrows(IllegalArgumentException::class.java) {
      AppPreferences.normalizePcBaseUrl("https://pc.example.ts.net?debug=true")
    }
  }
}
