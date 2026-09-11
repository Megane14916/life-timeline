package com.megane14916.lifetimeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.megane14916.lifetimeline.collector.UsageAccessChecker
import com.megane14916.lifetimeline.data.preferences.AppPreferences
import com.megane14916.lifetimeline.repository.BackgroundExecutionCoordinator
import com.megane14916.lifetimeline.repository.BackgroundLease
import com.megane14916.lifetimeline.repository.CollectionCoordinator
import com.megane14916.lifetimeline.repository.CollectionRunResult
import com.megane14916.lifetimeline.repository.CollectionRunStatus
import com.megane14916.lifetimeline.repository.SyncFailureKind
import com.megane14916.lifetimeline.repository.SyncRepository
import com.megane14916.lifetimeline.repository.SyncResult
import com.megane14916.lifetimeline.repository.SyncRunStatus
import com.megane14916.lifetimeline.worker.AutomaticSyncPolicy
import com.megane14916.lifetimeline.worker.BackgroundWorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MainStatus {
  READY,
  USAGE_ACCESS_REQUIRED,
  ENDPOINT_REQUIRED,
  COLLECTING,
  SYNCING,
  SUCCESS,
  NO_DATA,
  CONNECTION_FAILED,
  FAILED,
  BACKGROUND_BUSY,
}

data class MainUiState(
  val usageAccessGranted: Boolean = false,
  val pcBaseUrl: String? = null,
  val lastCollectionAtMs: Long? = null,
  val lastSyncAtMs: Long? = null,
  val pendingCount: Int = 0,
  val status: MainStatus = MainStatus.READY,
  val errorMessage: String? = null,
  val collectionScheduled: Boolean = false,
  val nextCollectionAtMs: Long? = null,
  val automaticCollectionAttemptAtMs: Long? = null,
  val automaticCollectionSuccessAtMs: Long? = null,
  val automaticCollectionResult: String? = null,
  val automaticSyncAttemptAtMs: Long? = null,
  val automaticSyncSuccessAtMs: Long? = null,
  val automaticSyncResult: String? = null,
  val recentAutomaticErrorKind: String? = null,
  val backgroundBusy: Boolean = false,
)

class MainViewModel(
  private val preferences: AppPreferences,
  private val usageAccessChecker: UsageAccessChecker,
  private val collectionCoordinator: CollectionCoordinator,
  private val pendingCount: suspend () -> Int,
  private val syncRepositoryFactory: suspend (String) -> SyncRepository,
  private val onPcBaseUrlSaved: () -> Unit = {},
  private val nowMs: () -> Long = { System.currentTimeMillis() },
  private val backgroundExecutionCoordinator: BackgroundExecutionCoordinator? = null,
  private val backgroundWorkScheduler: BackgroundWorkScheduler? = null,
) : ViewModel() {
  private val _uiState = MutableStateFlow(MainUiState())
  val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      preferences.settings.collectLatest { settings ->
        _uiState.value =
          _uiState.value.copy(
            pcBaseUrl = settings.pcBaseUrl,
            lastCollectionAtMs = settings.lastCollectionAtMs,
            lastSyncAtMs = settings.lastSyncAtMs,
            usageAccessGranted = usageAccessChecker.isUsageAccessGranted(),
            pendingCount = pendingCount(),
          )
      }
    }
    refreshBackgroundState()
  }

  fun refreshUsageAccess() {
    val granted = usageAccessChecker.isUsageAccessGranted()
    _uiState.value =
      _uiState.value.copy(
        usageAccessGranted = granted,
        status =
          if (!granted) {
            MainStatus.USAGE_ACCESS_REQUIRED
          } else if (_uiState.value.status == MainStatus.USAGE_ACCESS_REQUIRED) {
            MainStatus.READY
          } else {
            _uiState.value.status
          },
      )
  }

  fun refreshBackgroundState() {
    viewModelScope.launch { refreshBackgroundStateInternal() }
  }

  fun savePcBaseUrl(value: String) {
    viewModelScope.launch {
      try {
        preferences.setPcBaseUrl(value)
        onPcBaseUrlSaved()
        _uiState.value = _uiState.value.copy(status = MainStatus.READY, errorMessage = null)
        refreshBackgroundStateInternal()
      } catch (_: IllegalArgumentException) {
        _uiState.value =
          _uiState.value.copy(
            status = MainStatus.FAILED,
            errorMessage = "HTTPS形式のPC URLを入力してください。",
          )
      }
    }
  }

  fun collectAndSync() {
    val current = _uiState.value
    if (current.status == MainStatus.COLLECTING || current.status == MainStatus.SYNCING) return
    if (!current.usageAccessGranted) {
      _uiState.value = current.copy(status = MainStatus.USAGE_ACCESS_REQUIRED)
      return
    }
    val endpoint = current.pcBaseUrl
    if (endpoint.isNullOrBlank()) {
      _uiState.value = current.copy(status = MainStatus.ENDPOINT_REQUIRED)
      return
    }

    viewModelScope.launch {
      _uiState.value = _uiState.value.copy(status = MainStatus.COLLECTING, errorMessage = null)
      val executionCoordinator = backgroundExecutionCoordinator
      val collectionLease = executionCoordinator?.acquire(AutomaticSyncPolicy.COLLECTION_LEASE_KEY)
      if (executionCoordinator != null && collectionLease == null) {
        markBackgroundBusy()
        return@launch
      }

      val collection =
        try {
          collectionCoordinator.collect()
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Throwable) {
          collectionLease?.let {
            executionCoordinator?.recordFailure(it, "retry", "unexpected")
          }
          _uiState.value =
            _uiState.value.copy(
              status = MainStatus.FAILED,
              errorMessage = "利用状況の収集中に予期しないエラーが発生しました。",
            )
          releaseLease(collectionLease)
          refreshBackgroundStateInternal()
          return@launch
        }
      recordCollectionResult(collectionLease, collection)
      releaseLease(collectionLease)
      if (collection.status == CollectionRunStatus.PERMISSION_DENIED) {
        refreshUsageAccess()
        refreshBackgroundStateInternal()
        return@launch
      }
      if (collection.status == CollectionRunStatus.UNAVAILABLE) {
        _uiState.value =
          _uiState.value.copy(
            status = MainStatus.FAILED,
            errorMessage = "利用状況を取得できません。権限と端末の状態を確認してください。",
          )
        refreshBackgroundStateInternal()
        return@launch
      }

      _uiState.value = _uiState.value.copy(status = MainStatus.SYNCING)
      val syncLease = executionCoordinator?.acquire(AutomaticSyncPolicy.SYNC_LEASE_KEY)
      if (executionCoordinator != null && syncLease == null) {
        markBackgroundBusy()
        return@launch
      }
      val result =
        try {
          syncRepositoryFactory(endpoint).syncAll(
            onBatchCompleted = {
              executionCoordinator?.heartbeat(checkNotNull(syncLease)) ?: true
            },
          )
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Throwable) {
          syncLease?.let {
            executionCoordinator?.recordFailure(it, "retry", "unexpected")
          }
          _uiState.value =
            _uiState.value.copy(
              status = MainStatus.FAILED,
              errorMessage = "同期処理中に予期しないエラーが発生しました。",
            )
          releaseLease(syncLease)
          refreshBackgroundStateInternal()
          return@launch
        }
      applySyncResult(result, syncLease)
      releaseLease(syncLease)
      refreshBackgroundStateInternal()
    }
  }

  private suspend fun recordCollectionResult(
    lease: BackgroundLease?,
    result: CollectionRunResult,
  ) {
    val coordinator = backgroundExecutionCoordinator ?: return
    if (lease == null) return
    when (result.status) {
      CollectionRunStatus.SUCCESS,
      CollectionRunStatus.NO_DATA,
      CollectionRunStatus.PERMISSION_DENIED,
      -> coordinator.recordSuccess(lease, result.status.name.lowercase())

      CollectionRunStatus.UNAVAILABLE -> coordinator.recordFailure(lease, "retry", "unavailable")
    }
  }

  private suspend fun applySyncResult(
    result: SyncResult,
    lease: BackgroundLease?,
  ) {
    val coordinator = backgroundExecutionCoordinator
    if (coordinator != null && lease != null) {
      if (result.status == SyncRunStatus.LEASE_LOST) {
        markBackgroundBusy()
        return
      }
      if (result.failure == null && result.status != SyncRunStatus.RETRY_LIMIT_REACHED) {
        coordinator.recordSuccess(lease, result.status.name.lowercase())
      } else {
        coordinator.recordFailure(
          lease,
          result.status.name.lowercase(),
          result.failure
            ?.kind
            ?.name
            ?.lowercase() ?: "retry",
        )
      }
    }
    val status =
      when {
        result.status == SyncRunStatus.LEASE_LOST -> MainStatus.BACKGROUND_BUSY
        result.failure?.kind == SyncFailureKind.NETWORK -> MainStatus.CONNECTION_FAILED
        result.failure != null -> MainStatus.FAILED
        result.status == SyncRunStatus.NO_PENDING -> MainStatus.NO_DATA
        else -> MainStatus.SUCCESS
      }
    if (result.failure == null && result.status != SyncRunStatus.RETRY_LIMIT_REACHED) {
      preferences.recordSync(nowMs())
    }
    _uiState.value =
      _uiState.value.copy(
        pendingCount = pendingCount(),
        status = status,
        errorMessage =
          if (result.status == SyncRunStatus.LEASE_LOST) {
            "バックグラウンド処理が実行中です。完了後にもう一度お試しください。"
          } else {
            result.failure?.message
          },
      )
  }

  private suspend fun markBackgroundBusy() {
    _uiState.value =
      _uiState.value.copy(
        status = MainStatus.BACKGROUND_BUSY,
        backgroundBusy = true,
        errorMessage = "バックグラウンド処理が実行中です。完了後に状態を再読み込みしました。",
      )
    refreshBackgroundStateInternal()
  }

  private suspend fun releaseLease(lease: BackgroundLease?) {
    if (lease == null) return
    withContext(NonCancellable) {
      backgroundExecutionCoordinator?.release(lease)
    }
  }

  private suspend fun refreshBackgroundStateInternal() {
    val coordinator = backgroundExecutionCoordinator
    val collectionState = coordinator?.findState(AutomaticSyncPolicy.COLLECTION_LEASE_KEY)
    val syncState = coordinator?.findState(AutomaticSyncPolicy.SYNC_LEASE_KEY)
    val collectionWorkInfo = backgroundWorkScheduler?.currentCollectionWorkInfo()
    val syncWorkInfo = backgroundWorkScheduler?.currentSyncWorkInfo()
    val now = nowMs()
    val leaseBusy =
      listOf(collectionState, syncState).any { state ->
        state?.leaseOwner != null && (state.leaseExpiresAtMs == null || state.leaseExpiresAtMs > now)
      }
    val workBusy =
      listOf(collectionWorkInfo, syncWorkInfo).any { it?.state == WorkInfo.State.RUNNING }
    val backgroundBusy = leaseBusy || workBusy
    val status =
      if (_uiState.value.status == MainStatus.BACKGROUND_BUSY && !backgroundBusy) {
        MainStatus.READY
      } else {
        _uiState.value.status
      }
    _uiState.value =
      _uiState.value.copy(
        collectionScheduled = collectionWorkInfo?.state != WorkInfo.State.CANCELLED && collectionWorkInfo != null,
        nextCollectionAtMs = collectionWorkInfo?.nextScheduleTimeMillis?.takeIf { it > 0 },
        automaticCollectionAttemptAtMs = collectionState?.lastAttemptAtMs,
        automaticCollectionSuccessAtMs = collectionState?.lastSuccessAtMs,
        automaticCollectionResult = collectionState?.lastResult,
        automaticSyncAttemptAtMs = syncState?.lastAttemptAtMs,
        automaticSyncSuccessAtMs = syncState?.lastSuccessAtMs,
        automaticSyncResult = syncState?.lastResult,
        recentAutomaticErrorKind = syncState?.lastErrorKind ?: collectionState?.lastErrorKind,
        status = status,
        errorMessage = if (status == MainStatus.READY) null else _uiState.value.errorMessage,
        backgroundBusy = backgroundBusy,
      )
  }

  class Factory(
    private val create: () -> MainViewModel,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
  }
}
