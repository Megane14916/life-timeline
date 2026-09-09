package com.megane14916.lifetimeline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.megane14916.lifetimeline.collector.UsageAccessChecker

class MainActivity : ComponentActivity() {
  private lateinit var usageAccessChecker: UsageAccessChecker
  private var usageAccessGranted by mutableStateOf(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    usageAccessChecker = UsageAccessChecker.from(this)
    usageAccessGranted = usageAccessChecker.isUsageAccessGranted()
    setContent {
      LifeTimelineTheme {
        FoundationStatus(
          usageAccessGranted = usageAccessGranted,
          onOpenUsageAccessSettings = {
            startActivity(usageAccessChecker.usageAccessSettingsIntent())
          },
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    if (::usageAccessChecker.isInitialized) {
      usageAccessGranted = usageAccessChecker.isUsageAccessGranted()
    }
  }
}

@Composable
private fun LifeTimelineTheme(content: @Composable () -> Unit) {
  MaterialTheme(content = content)
}

@Composable
private fun FoundationStatus(
  usageAccessGranted: Boolean,
  onOpenUsageAccessSettings: () -> Unit,
) {
  Surface(modifier = Modifier.fillMaxSize()) {
    Column(
      modifier = Modifier.padding(32.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(text = ApplicationInfo.NAME, style = MaterialTheme.typography.headlineLarge)
      Text(
        text =
          if (usageAccessGranted) {
            ApplicationInfo.READY_MESSAGE
          } else {
            "利用状況へのアクセス権限が必要です。"
          },
        modifier = Modifier.padding(top = 16.dp),
        style = MaterialTheme.typography.bodyLarge,
      )
      if (!usageAccessGranted) {
        Button(
          onClick = onOpenUsageAccessSettings,
          modifier = Modifier.padding(top = 16.dp),
        ) {
          Text("設定を開く")
        }
      }
    }
  }
}

@Preview(showBackground = true)
@Composable
private fun FoundationStatusPreview() {
  LifeTimelineTheme {
    FoundationStatus(usageAccessGranted = false, onOpenUsageAccessSettings = {})
  }
}
