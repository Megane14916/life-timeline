package com.megane14916.lifetimeline

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.megane14916.lifetimeline.collector.FusedLocationCurrentFixProvider
import com.megane14916.lifetimeline.collector.LocationAccessState
import com.megane14916.lifetimeline.collector.LocationPermissionChecker
import com.megane14916.lifetimeline.collector.LocationRequestController
import com.megane14916.lifetimeline.collector.PhotoAccessChecker
import com.megane14916.lifetimeline.collector.PhotoAccessState
import com.megane14916.lifetimeline.collector.UsageAccessChecker
import com.megane14916.lifetimeline.worker.LocationRegistrationDiagnostics

class MainActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels { MainViewModel.Factory { createMainViewModel() } }
  private val photoAccessChecker by lazy { PhotoAccessChecker.from(applicationContext) }
  private val locationPermissionChecker by lazy { LocationPermissionChecker.from(applicationContext) }
  private lateinit var photoPermissionLauncher: ActivityResultLauncher<Array<String>>
  private lateinit var foregroundLocationPermissionLauncher: ActivityResultLauncher<Array<String>>
  private lateinit var backgroundLocationPermissionLauncher: ActivityResultLauncher<String>
  private var showBackgroundLocationEducation by mutableStateOf(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    photoPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refreshPhotoAccess()
      }
    foregroundLocationPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refreshLocationAccess()
        if (locationPermissionChecker.hasForegroundPermission() && !locationPermissionChecker.hasBackgroundPermission()) {
          showBackgroundLocationEducation = true
        }
      }
    backgroundLocationPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshLocationAccess()
      }
    val scheduler = (application as LifeTimelineApplication).appContainer.backgroundWorkScheduler
    scheduler.photoCollectionWorkInfosLiveData().observe(this) { viewModel.refreshBackgroundState() }
    scheduler.photoSyncWorkInfosLiveData().observe(this) { viewModel.refreshBackgroundState() }
    scheduler.locationRegistrationWorkInfosLiveData().observe(this) { viewModel.refreshBackgroundState() }
    scheduler.locationSyncWorkInfosLiveData().observe(this) { viewModel.refreshBackgroundState() }
    setContent {
      LifeTimelineTheme {
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        MainScreen(
          state = state,
          showBackgroundLocationEducation = showBackgroundLocationEducation,
          backgroundPermissionOptionLabel = backgroundPermissionOptionLabel(),
          onDismissBackgroundLocationEducation = { showBackgroundLocationEducation = false },
          onContinueBackgroundLocationEducation = ::continueBackgroundLocationPermission,
          onOpenUsageAccessSettings = {
            startActivity(UsageAccessChecker.from(this).usageAccessSettingsIntent())
          },
          onEnablePhotoCollection = {
            viewModel.enablePhotoCollection {
              photoPermissionLauncher.launch(photoAccessChecker.runtimePermissions())
            }
          },
          onDisablePhotoCollection = viewModel::disablePhotoCollection,
          onReselectPhotos = {
            photoPermissionLauncher.launch(photoAccessChecker.runtimePermissions())
          },
          onSaveEndpoint = viewModel::savePcBaseUrl,
          onCollectAndSync = viewModel::collectAndSync,
          onCollectPhotosNow = viewModel::collectPhotosNow,
          onEnableLocationCollection = {
            viewModel.enableLocationCollection(::advanceLocationPermissionFlow)
          },
          onDisableLocationCollection = viewModel::disableLocationCollection,
          onRequestLocationPermission = ::advanceLocationPermissionFlow,
          onCheckLocationRegistration = viewModel::checkLocationRegistration,
          onCaptureCurrentLocation = viewModel::captureCurrentLocation,
          onSyncLocationNow = viewModel::syncLocationNow,
          onOpenLocationSettings = {
            startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
          },
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.refreshUsageAccess()
    viewModel.refreshPhotoAccess()
    viewModel.refreshLocationAccess()
    viewModel.refreshBackgroundState()
  }

  private fun createMainViewModel(): MainViewModel {
    val container = (application as LifeTimelineApplication).appContainer
    val appContext = applicationContext
    val accessChecker = UsageAccessChecker.from(appContext)
    return MainViewModel(
      preferences = container.preferences,
      usageAccessChecker = accessChecker,
      photoAccessChecker = photoAccessChecker,
      collectionCoordinator = container.createCollectionCoordinator(appContext),
      pendingCount = container.localDataRepository::countPending,
      syncRepositoryFactory = { endpoint -> container.createSyncRepository(appContext, endpoint) },
      onPcBaseUrlSaved = container.backgroundWorkScheduler::enqueueAllSync,
      backgroundExecutionCoordinator = container.backgroundExecutionCoordinator,
      backgroundWorkScheduler = container.backgroundWorkScheduler,
      pendingPhotoCount = { container.database.androidMediaItemDao().countPendingSync() },
      pendingPhotoThumbnailCount = { container.database.androidMediaItemDao().countPendingThumbnails() },
      localPhotoThumbnailBytes = { container.database.androidMediaItemDao().totalStoredThumbnailBytes() },
      locationPermissionChecker = locationPermissionChecker,
      locationRegistrationClient = LocationRequestController(appContext, locationPermissionChecker),
      pendingLocationCount = { container.locationCollectionRepository.countPending() },
      latestLocationReceivedAt = { container.locationCollectionRepository.latestReceivedAt() },
      locationCurrentFixProvider = FusedLocationCurrentFixProvider(appContext, locationPermissionChecker),
      locationUpdateProcessor = container.locationUpdateProcessor,
    )
  }

  private fun advanceLocationPermissionFlow() {
    when {
      !locationPermissionChecker.hasForegroundPermission() -> {
        foregroundLocationPermissionLauncher.launch(locationPermissionChecker.foregroundPermissions())
      }

      !locationPermissionChecker.hasBackgroundPermission() -> {
        showBackgroundLocationEducation = true
      }

      else -> {
        viewModel.refreshLocationAccess()
      }
    }
  }

  private fun continueBackgroundLocationPermission() {
    showBackgroundLocationEducation = false
    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
      backgroundLocationPermissionLauncher.launch(locationPermissionChecker.backgroundPermission())
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      startActivity(
        Intent(
          android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
          Uri.fromParts("package", packageName, null),
        ),
      )
    }
  }

  @Suppress("NewApi")
  private fun backgroundPermissionOptionLabel(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      packageManager.getBackgroundPermissionOptionLabel().toString()
    } else {
      "常に許可"
    }
}

@Composable
private fun LifeTimelineTheme(content: @Composable () -> Unit) {
  MaterialTheme(content = content)
}

@Composable
private fun MainScreen(
  state: MainUiState,
  showBackgroundLocationEducation: Boolean,
  backgroundPermissionOptionLabel: String,
  onDismissBackgroundLocationEducation: () -> Unit,
  onContinueBackgroundLocationEducation: () -> Unit,
  onOpenUsageAccessSettings: () -> Unit,
  onEnablePhotoCollection: () -> Unit,
  onDisablePhotoCollection: () -> Unit,
  onReselectPhotos: () -> Unit,
  onSaveEndpoint: (String) -> Unit,
  onCollectAndSync: () -> Unit,
  onCollectPhotosNow: () -> Unit,
  onEnableLocationCollection: () -> Unit,
  onDisableLocationCollection: () -> Unit,
  onRequestLocationPermission: () -> Unit,
  onCheckLocationRegistration: () -> Unit,
  onCaptureCurrentLocation: () -> Unit,
  onSyncLocationNow: () -> Unit,
  onOpenLocationSettings: () -> Unit,
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

      Text(text = "写真の収集", style = MaterialTheme.typography.titleMedium)
      Text(
        text =
          "写真の原本はPCへ送らず、DCIM内の新しい写真のthumbnailとmetadataを同期します。" +
            "選択した写真のみのアクセスでは、選択済みの写真が対象です。",
        style = MaterialTheme.typography.bodyMedium,
      )
      Text("写真へのアクセス: ${state.photoAccessState.toDisplayText()}")
      Text("写真収集の設定: ${if (state.photoCollectionEnabled) "有効" else "無効"}")
      if (!state.photoCollectionEnabled) {
        Button(onClick = onEnablePhotoCollection, enabled = !busy) {
          Text("写真収集を有効にする")
        }
      } else {
        when (state.photoAccessState) {
          PhotoAccessState.DENIED -> {
            Button(onClick = onReselectPhotos, enabled = !busy) {
              Text("写真へのアクセスを許可")
            }
          }

          PhotoAccessState.PARTIAL -> {
            Button(onClick = onReselectPhotos, enabled = !busy) {
              Text("選択する写真を変更")
            }
          }

          PhotoAccessState.FULL -> {
          }
        }
        Button(onClick = onDisablePhotoCollection, enabled = !busy) {
          Text("写真収集を無効にする")
        }
      }
      if (state.photoCollectionEnabled) {
        Button(
          onClick = onCollectPhotosNow,
          enabled = state.photoAccessState != PhotoAccessState.DENIED,
        ) {
          Text("写真を今すぐ確認")
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

      Text("写真の自動収集: ${if (state.photoCollectionScheduled) "スケジュール済み" else "未スケジュール"}")
      Text("次回の写真確認: ${formatDeviceTimestamp(state.nextPhotoCollectionAtMs)}")
      Text(
        "写真の最終scan: ${state.photoCollectionResult ?: "未実行"} " +
          "(試行 ${formatDeviceTimestamp(state.photoCollectionAttemptAtMs)} / 成功 ${formatDeviceTimestamp(state.photoCollectionSuccessAtMs)})",
      )
      Text(
        "写真の最終sync: ${state.photoSyncResult ?: "未実行"} " +
          "(試行 ${formatDeviceTimestamp(state.photoSyncAttemptAtMs)} / 成功 ${formatDeviceTimestamp(state.photoSyncSuccessAtMs)})",
      )
      Text("thumbnail生成待ち: ${state.pendingPhotoThumbnailCount}")
      Text("写真pending: ${state.pendingPhotoCount}")
      Text("端末内thumbnail容量: ${state.localPhotoThumbnailBytes} bytes")
      Text("写真同期状態: ${state.photoSyncStatusToDisplay()}")
      state.photoRecentErrorKind?.let { errorKind ->
        Text("写真の直近エラー: $errorKind", color = MaterialTheme.colorScheme.error)
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

      if (showBackgroundLocationEducation) {
        AlertDialog(
          onDismissRequest = onDismissBackgroundLocationEducation,
          title = { Text("バックグラウンド位置情報") },
          text = {
            Text(
              "画面を閉じている間も位置情報を記録するには、アプリの位置情報権限で「$backgroundPermissionOptionLabel」を選択してください。許可しない場合、バックグラウンド収集は行いません.",
            )
          },
          confirmButton = {
            Button(onClick = onContinueBackgroundLocationEducation) { Text("設定を続ける") }
          },
          dismissButton = {
            Button(onClick = onDismissBackgroundLocationEducation) { Text("今はしない") }
          },
        )
      }

      Button(
        onClick = onCollectAndSync,
        enabled = !busy && state.usageAccessGranted && endpoint.isNotBlank(),
      ) {
        Text(if (busy) "処理中..." else "収集して同期")
      }

      Text(text = "位置情報の収集", style = MaterialTheme.typography.titleMedium)
      Text(
        text = "位置情報は明示的に有効化した場合のみ端末内へ保存します。無効化しても過去のpending記録とPC側の履歴は削除されません。",
        style = MaterialTheme.typography.bodyMedium,
      )
      Text("位置情報: ${state.locationAccessState.toDisplayText()}")
      when {
        !state.locationCollectionEnabled -> {
          Button(onClick = onEnableLocationCollection, enabled = !busy) {
            Text("位置情報収集を有効にする")
          }
        }

        state.locationAccessState == LocationAccessState.FOREGROUND_PERMISSION_REQUIRED -> {
          Button(onClick = onRequestLocationPermission, enabled = !busy) {
            Text("位置情報へのアクセスを許可")
          }
        }

        state.locationAccessState == LocationAccessState.BACKGROUND_PERMISSION_REQUIRED -> {
          Button(onClick = onRequestLocationPermission, enabled = !busy) {
            Text("バックグラウンド位置情報を設定")
          }
        }

        state.locationAccessState == LocationAccessState.LOCATION_SERVICES_OFF -> {
          Text("端末の位置情報サービスをオンにすると収集を再開します。")
          Button(onClick = onOpenLocationSettings, enabled = !busy) {
            Text("端末の位置設定を開く")
          }
        }
      }
      if (state.locationCollectionEnabled) {
        Button(onClick = onDisableLocationCollection, enabled = !busy) {
          Text("位置情報収集を無効にする")
        }
        Button(onClick = onCheckLocationRegistration, enabled = !busy) {
          Text("登録状態を確認")
        }
        Button(
          onClick = onCaptureCurrentLocation,
          enabled =
            !state.currentLocationCaptureInProgress &&
              (
                state.locationAccessState == LocationAccessState.APPROXIMATE ||
                  state.locationAccessState == LocationAccessState.PRECISE
              ),
        ) {
          Text(if (state.currentLocationCaptureInProgress) "現在地を取得中…" else "現在地を1回取得して送信")
        }
        Text("この操作を押したときだけ、新しい位置を1点取得します。通常のバックグラウンド収集とは別です。")
        state.currentLocationCaptureMessage?.let { message -> Text(message) }
      }
      Text("位置情報の最終受信: ${formatDeviceTimestamp(state.latestLocationReceivedAtMs)}")
      Text("位置情報pending: ${state.pendingLocationCount}")
      Text(
        "位置登録: ${state.locationRegistrationResult.toLocationRegistrationLabel()} " +
          "(試行 ${formatDeviceTimestamp(state.locationRegistrationAttemptAtMs)} / " +
          "成功 ${formatDeviceTimestamp(state.locationRegistrationSuccessAtMs)})",
      )
      Text("位置登録work: ${state.locationRegistrationWorkState?.name ?: "未登録"}")
      Text(
        "位置同期: ${state.locationSyncStatusToDisplay()} " +
          "(試行 ${formatDeviceTimestamp(state.locationSyncAttemptAtMs)} / " +
          "成功 ${formatDeviceTimestamp(state.locationSyncSuccessAtMs)})",
      )
      Text(
        "位置同期work: ${state.locationSyncWorkState?.name ?: "未登録"}" +
          if (state.locationSyncRunAttemptCount > 0) " (試行回数 ${state.locationSyncRunAttemptCount})" else "",
      )
      state.locationSyncErrorKind?.let { kind ->
        Text("位置同期の直近エラー: ${kind.toLocationErrorLabel()}", color = MaterialTheme.colorScheme.error)
      }
      state.locationRegistrationErrorKind?.let { kind ->
        Text("位置登録の直近エラー: ${kind.toLocationErrorLabel()}", color = MaterialTheme.colorScheme.error)
      }
      Button(
        onClick = onSyncLocationNow,
        enabled = state.pendingLocationCount > 0 && !state.pcBaseUrl.isNullOrBlank() && !busy,
      ) {
        Text("未同期の位置情報を送信")
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
      showBackgroundLocationEducation = false,
      backgroundPermissionOptionLabel = "常に許可",
      onDismissBackgroundLocationEducation = {},
      onContinueBackgroundLocationEducation = {},
      onOpenUsageAccessSettings = {},
      onEnablePhotoCollection = {},
      onDisablePhotoCollection = {},
      onReselectPhotos = {},
      onSaveEndpoint = {},
      onCollectAndSync = {},
      onCollectPhotosNow = {},
      onEnableLocationCollection = {},
      onDisableLocationCollection = {},
      onRequestLocationPermission = {},
      onCheckLocationRegistration = {},
      onCaptureCurrentLocation = {},
      onSyncLocationNow = {},
      onOpenLocationSettings = {},
    )
  }
}

private fun PhotoAccessState.toDisplayText(): String =
  when (this) {
    PhotoAccessState.FULL -> "有効（すべての写真）"
    PhotoAccessState.PARTIAL -> "制限付き（選択した写真のみ）"
    PhotoAccessState.DENIED -> "権限が必要"
  }

private fun LocationAccessState.toDisplayText(): String =
  when (this) {
    LocationAccessState.DISABLED -> "無効"
    LocationAccessState.FOREGROUND_PERMISSION_REQUIRED -> "権限が必要"
    LocationAccessState.BACKGROUND_PERMISSION_REQUIRED -> "バックグラウンド許可が必要"
    LocationAccessState.LOCATION_SERVICES_OFF -> "端末設定でOFF"
    LocationAccessState.APPROXIMATE -> "有効（概算）"
    LocationAccessState.PRECISE -> "有効（正確）"
  }

private fun MainUiState.photoSyncStatusToDisplay(): String =
  when {
    pendingPhotoCount > 0 && pcBaseUrl.isNullOrBlank() -> "PC URL設定後に同期"
    photoSyncWorkState == androidx.work.WorkInfo.State.RUNNING -> "同期中"
    photoSyncWorkState == androidx.work.WorkInfo.State.ENQUEUED && photoSyncRunAttemptCount > 0 -> "再試行待ち"
    photoSyncWorkState == androidx.work.WorkInfo.State.ENQUEUED -> "制約条件待ち（UNMETERED / バッテリー / ストレージ）"
    photoRecentErrorKind != null -> "要確認（$photoRecentErrorKind）"
    else -> "待機中"
  }

private fun String?.toLocationRegistrationLabel(): String =
  when (this) {
    "registered" -> "登録済み"
    "disabled" -> "無効"
    "permission_required" -> "権限が必要"
    "location_services_off" -> "位置サービスOFF"
    "play_services_unavailable" -> "Play services利用不可"
    "retry" -> "再試行待ち"
    null -> "未実行"
    else -> "要確認"
  }

private fun MainUiState.locationSyncStatusToDisplay(): String =
  when {
    pendingLocationCount > 0 && pcBaseUrl.isNullOrBlank() -> "PC URL設定後に同期"
    locationSyncWorkState == androidx.work.WorkInfo.State.RUNNING -> "同期中"
    locationSyncWorkState == androidx.work.WorkInfo.State.ENQUEUED && locationSyncRunAttemptCount > 0 -> "再試行待ち"
    locationSyncWorkState == androidx.work.WorkInfo.State.ENQUEUED -> "ネットワーク / バッテリー待ち"
    locationSyncResult == "success" -> "完了"
    locationSyncResult == "no_pending" -> "未同期なし"
    locationSyncResult == "configuration_required" -> "PC URL設定が必要"
    locationSyncResult == "retry" -> "再試行待ち"
    locationSyncResult == "failure" -> "要確認"
    pendingLocationCount > 0 -> "送信待ち"
    else -> "待機中"
  }

private fun String.toLocationErrorLabel(): String =
  LocationRegistrationDiagnostics.errorLabel(this)
    ?: when (this) {
      "network" -> "ネットワーク"
      "server" -> "PCサーバー"
      "protocol" -> "同期データ形式"
      "budget" -> "実行上限"
      "unexpected" -> "予期しないエラー"
      else -> "同期処理"
    }
