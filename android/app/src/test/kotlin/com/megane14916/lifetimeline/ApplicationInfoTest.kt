package com.megane14916.lifetimeline

import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationInfoTest {
  @Test
  fun exposesProductNameAndReadyMessage() {
    assertEquals("life-timeline", ApplicationInfo.NAME)
    assertEquals("開発基盤の初期化が完了しました。", ApplicationInfo.READY_MESSAGE)
  }
}
