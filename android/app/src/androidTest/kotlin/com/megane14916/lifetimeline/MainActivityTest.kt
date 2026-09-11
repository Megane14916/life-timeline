package com.megane14916.lifetimeline

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
    composeRule.onNodeWithText("PC endpoint (HTTPS)").assertIsDisplayed()
    composeRule.onNodeWithText("自動収集:", substring = true).assertIsDisplayed()
    composeRule.onNodeWithText("次回自動収集:", substring = true).assertIsDisplayed()
    composeRule.onNodeWithText("収集して同期").assertIsDisplayed()
  }
}
