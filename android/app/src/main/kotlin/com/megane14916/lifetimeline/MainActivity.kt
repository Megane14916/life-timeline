package com.megane14916.lifetimeline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      LifeTimelineTheme {
        FoundationStatus()
      }
    }
  }
}

@Composable
private fun LifeTimelineTheme(content: @Composable () -> Unit) {
  MaterialTheme(content = content)
}

@Composable
private fun FoundationStatus() {
  Surface(modifier = Modifier.fillMaxSize()) {
    Column(
      modifier = Modifier.padding(32.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(text = ApplicationInfo.NAME, style = MaterialTheme.typography.headlineLarge)
      Text(
        text = ApplicationInfo.READY_MESSAGE,
        modifier = Modifier.padding(top = 16.dp),
        style = MaterialTheme.typography.bodyLarge,
      )
    }
  }
}

@Preview(showBackground = true)
@Composable
private fun FoundationStatusPreview() {
  LifeTimelineTheme {
    FoundationStatus()
  }
}
