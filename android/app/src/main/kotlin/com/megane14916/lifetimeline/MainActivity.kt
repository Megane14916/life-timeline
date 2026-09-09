package com.megane14916.lifetimeline

import android.os.Build
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
import com.megane14916.lifetimeline.collector.AndroidPackageLabelResolver
import com.megane14916.lifetimeline.collector.AndroidUsageEventsSource
import com.megane14916.lifetimeline.collector.UsageAccessChecker
import com.megane14916.lifetimeline.collector.UsageEventMapper
import com.megane14916.lifetimeline.collector.UsageEventsCollector
import com.megane14916.lifetimeline.data.remote.SyncApiFactory
import com.megane14916.lifetimeline.data.remote.SyncAppDto
import com.megane14916.lifetimeline.data.remote.SyncDeviceDto
import com.megane14916.lifetimeline.repository.CollectionCoordinator
import com.megane14916.lifetimeline.repository.CollectionRepository
import com.megane14916.lifetimeline.repository.LocalDataRepository
import com.megane14916.lifetimeline.repository.SyncRepository
import retrofit2.Retrofit

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
  }

  private fun createMainViewModel(): MainViewModel {
    val container = (application as LifeTimelineApplication).appContainer
    val accessChecker = UsageAccessChecker.from(this)
    val collector =
      UsageEventsCollector(
        accessChecker = accessChecker,
        source = AndroidUsageEventsSource.from(this),
        mapper = UsageEventMapper(Build.VERSION.SDK_INT),
        labelResolver = AndroidPackageLabelResolver(this),
        selfPackageName = packageName,
      )
    val localRepository = LocalDataRepository(container.database)
    val coordinator =
      CollectionCoordinator(
        database = container.database,
        preferences = container.preferences,
        accessChecker = accessChecker,
        collector = collector,
        collectionRepository = CollectionRepository(container.database),
      )
    return MainViewModel(
      preferences = container.preferences,
      usageAccessChecker = accessChecker,
      collectionCoordinator = coordinator,
      pendingCount = localRepository::countPending,
      syncRepositoryFactory = { endpoint ->
        SyncRepository(
          pendingStore = localRepository,
          appProvider = { ids ->
            localRepository.getAppsByIds(ids.toList()).map { app ->
              SyncAppDto(
                id = app.id,
                identifier = app.packageName,
                displayName = app.displayName,
              )
            }
          },
          syncApi =
            SyncApiFactory.create(
              baseUrl = endpoint,
              retrofitBuilder = Retrofit.Builder().client(container.httpClient),
            ),
          device =
            SyncDeviceDto(
              id = container.preferences.ensureDeviceId(),
              name = deviceName(),
              platform = "android",
            ),
        )
      },
    )
  }

  private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android device" }
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
  val busy = state.status == MainStatus.COLLECTING || state.status == MainStatus.SYNCING

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

      Text("最終収集: ${state.lastCollectionAtMs?.toString() ?: "未実行"}")
      Text("最終同期: ${state.lastSyncAtMs?.toString() ?: "未実行"}")
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
