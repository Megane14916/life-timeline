package com.megane14916.lifetimeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TimeFormatterTest {
  @Test
  fun nullTimestampIsDisplayedAsNotExecuted() {
    assertEquals("未実行", formatDeviceTimestamp(null))
  }

  @Test
  fun timestampIsDisplayedAsLocalizedDateTime() {
    val formatted = formatDeviceTimestamp(0L)

    assertFalse(formatted.isBlank())
    assertFalse(formatted == "0")
  }
}
