package com.megane14916.lifetimeline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megane14916.lifetimeline.collector.UsageAccessChecker

class MainActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels { MainViewModel.Factory { createMainViewModel() } }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      LifeTimelineTheme {
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        MainScreen(
          state = state,
          onOpenUsageAccessSettings = {
            startActivity(UsageAccessChecker.from(this).usageAccessSettingsIntent())
          },
          onSaveEndpoint = viewModel::savePcBaseUrl,
          onCollectAndSync = viewModel::collectAndSync,
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.refreshUsageAccess()
    viewModel.refreshBackgroundState()
  }

  private fun createMainViewModel(): MainViewModel {
    val container = (application as LifeTimelineApplication).appContainer
    val appContext = applicationContext
    val accessChecker = UsageAccessChecker.from(appContext)
    return MainViewModel(
      preferences = container.preferences,
      usageAccessChecker = accessChecker,
      collectionCoordinator = container.createCollectionCoordinator(appContext),
      pendingCount = container.localDataRepository::countPending,
      syncRepositoryFactory = { endpoint -> container.createSyncRepository(appContext, endpoint) },
      onPcBaseUrlSaved = container.backgroundWorkScheduler::enqueueSync,
      backgroundExecutionCoordinator = container.backgroundExecutionCoordinator,
      backgroundWorkScheduler = container.backgroundWorkScheduler,
    )
  }
}

@Composable
private fun LifeTimelineTheme(content: @Composable () -> Unit) {
  MaterialTheme(content = content)
}

@Composable
private fun MainScreen(
  state: MainUiState,
  onOpenUsageAccessSettings: () -> Unit,
  onSaveEndpoint: (String) -> Unit,
  onCollectAndSync: () -> Unit,
) {
  var endpoint by rememberSaveable(state.pcBaseUrl) { mutableStateOf(state.pcBaseUrl.orEmpty()) }
  val busy =
    state.status == MainStatus.COLLECTING ||
      state.status == MainStatus.SYNCING ||
      state.status == MainStatus.BACKGROUND_BUSY ||
      state.backgroundBusy

  Surface(modifier = Modifier.fillMaxSize()) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(text = ApplicationInfo.NAME, style = MaterialTheme.typography.headlineLarge)
      Text(text = "PCに利用状況を同期します。", style = MaterialTheme.typography.bodyLarge)

      Text(
        text = "Usage access: ${if (state.usageAccessGranted) "許可済み" else "要設定"}",
        style = MaterialTheme.typography.titleMedium,
      )
      if (!state.usageAccessGranted) {
        Button(onClick = onOpenUsageAccessSettings, enabled = !busy) {
          Text("利用状況へのアクセス設定")
        }
      }

      OutlinedTextField(
        value = endpoint,
        onValueChange = { endpoint = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("PC endpoint (HTTPS)") },
        supportingText = { Text("Tailscale ServeなどのHTTPS URLを入力してください。") },
        maxLines = 3,
        enabled = !busy,
      )
      Button(onClick = { onSaveEndpoint(endpoint) }, enabled = !busy && endpoint.isNotBlank()) {
        Text("PC URLを保存")
      }

      Text("最終収集: ${formatDeviceTimestamp(state.lastCollectionAtMs)}")
      Text("最終同期: ${formatDeviceTimestamp(state.lastSyncAtMs)}")
      Text("自動収集スケジュール: ${if (state.collectionScheduled) "スケジュール済み" else "未スケジュール"}")
      Text("次回の自動収集: ${formatDeviceTimestamp(state.nextCollectionAtMs)}")
      Text(
        "自動収集の直近結果: ${state.automaticCollectionResult ?: "未実行"} " +
          "(${formatDeviceTimestamp(state.automaticCollectionSuccessAtMs)})",
      )
      Text(
        "自動同期の直近結果: ${state.automaticSyncResult ?: "未実行"} " +
          "(${formatDeviceTimestamp(state.automaticSyncSuccessAtMs)})",
      )
      state.recentAutomaticErrorKind?.let { errorKind ->
        Text("自動処理エラー: $errorKind", color = MaterialTheme.colorScheme.error)
      }
      Text("Pending: ${state.pendingCount}")
      Text("状態: ${state.status.toDisplayText()}")
      state.errorMessage?.let { error ->
        Text(text = error, color = MaterialTheme.colorScheme.error)
      }

      Button(
        onClick = onCollectAndSync,
        enabled = !busy && state.usageAccessGranted && endpoint.isNotBlank(),
      ) {
        Text(if (busy) "処理中..." else "収集して同期")
      }
    }
  }
}

private fun MainStatus.toDisplayText(): String =
  when (this) {
    MainStatus.READY -> "準備完了"
    MainStatus.USAGE_ACCESS_REQUIRED -> "Usage accessの設定が必要です"
    MainStatus.ENDPOINT_REQUIRED -> "PC endpointの設定が必要です"
    MainStatus.COLLECTING -> "利用状況を収集中"
    MainStatus.SYNCING -> "PCへ同期中"
    MainStatus.SUCCESS -> "同期が完了しました"
    MainStatus.NO_DATA -> "新しいデータはありません"
    MainStatus.CONNECTION_FAILED -> "PCに接続できません"
    MainStatus.FAILED -> "処理に失敗しました"
    MainStatus.BACKGROUND_BUSY -> "バックグラウンド処理が実行中です"
  }

@Composable
@Preview(showBackground = true)
private fun MainScreenPreview() {
  LifeTimelineTheme {
    MainScreen(
      state = MainUiState(usageAccessGranted = true, pcBaseUrl = "https://pc.example.ts.net/"),
      onOpenUsageAccessSettings = {},
      onSaveEndpoint = {},
      onCollectAndSync = {},
    )
  }
}
