package com.megane14916.lifetimeline

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun displaysSyncConfigurationAndAction() {
    composeRule.onNodeWithText(ApplicationInfo.NAME).assertIsDisplayed()
    composeRule.onNodeWithText("Usage access: 要設定").assertIsDisplayed()
    composeRule.onNodeWithText("写真の収集").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithText("写真へのアクセス: 権限が必要").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithText("写真収集を有効にする").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithText("PC endpoint (HTTPS)").assertIsDisplayed()
    composeRule
      .onNodeWithText("自動収集スケジュール:", substring = true)
      .performScrollTo()
      .assertIsDisplayed()
    composeRule
      .onNodeWithText("次回の自動収集:", substring = true)
      .performScrollTo()
      .assertIsDisplayed()
    composeRule
      .onNodeWithText("次回の写真確認:", substring = true)
      .performScrollTo()
      .assertIsDisplayed()
    composeRule
      .onNodeWithText("thumbnail生成待ち:", substring = true)
      .performScrollTo()
      .assertIsDisplayed()
    composeRule
      .onNodeWithText("写真同期状態:", substring = true)
      .performScrollTo()
      .assertIsDisplayed()
    composeRule.onNodeWithText("収集して同期").performScrollTo().assertIsDisplayed()
  }
}
