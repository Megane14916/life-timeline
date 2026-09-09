package com.megane14916.lifetimeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.megane14916.lifetimeline.collector.UsageAccessChecker
import com.megane14916.lifetimeline.data.preferences.AppPreferences
import com.megane14916.lifetimeline.repository.CollectionCoordinator
import com.megane14916.lifetimeline.repository.CollectionRunStatus
import com.megane14916.lifetimeline.repository.SyncRepository
import com.megane14916.lifetimeline.repository.SyncResult
import com.megane14916.lifetimeline.repository.SyncRunStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
}

data class MainUiState(
  val usageAccessGranted: Boolean = false,
  val pcBaseUrl: String? = null,
  val lastCollectionAtMs: Long? = null,
  val lastSyncAtMs: Long? = null,
  val pendingCount: Int = 0,
  val status: MainStatus = MainStatus.READY,
  val errorMessage: String? = null,
)

class MainViewModel(
  private val preferences: AppPreferences,
  private val usageAccessChecker: UsageAccessChecker,
  private val collectionCoordinator: CollectionCoordinator,
  private val pendingCount: suspend () -> Int,
  private val syncRepositoryFactory: suspend (String) -> SyncRepository,
  private val nowMs: () -> Long = { System.currentTimeMillis() },
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
  }

  fun refreshUsageAccess() {
    _uiState.value =
      _uiState.value.copy(
        usageAccessGranted = usageAccessChecker.isUsageAccessGranted(),
        status = if (usageAccessChecker.isUsageAccessGranted()) MainStatus.READY else MainStatus.USAGE_ACCESS_REQUIRED,
      )
  }

  fun savePcBaseUrl(value: String) {
    viewModelScope.launch {
      try {
        preferences.setPcBaseUrl(value)
        _uiState.value = _uiState.value.copy(status = MainStatus.READY, errorMessage = null)
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
      val collection = collectionCoordinator.collect()
      if (collection.status == CollectionRunStatus.PERMISSION_DENIED) {
        refreshUsageAccess()
        return@launch
      }
      if (collection.status == CollectionRunStatus.UNAVAILABLE) {
        _uiState.value =
          _uiState.value.copy(
            status = MainStatus.FAILED,
            errorMessage = "利用状況を取得できません。権限と端末の状態を確認してください。",
          )
        return@launch
      }

      _uiState.value = _uiState.value.copy(status = MainStatus.SYNCING)
      val result = syncRepositoryFactory(endpoint).syncAll()
      applySyncResult(result)
    }
  }

  private fun applySyncResult(result: SyncResult) {
    val status =
      when {
        result.failure?.kind == com.megane14916.lifetimeline.repository.SyncFailureKind.NETWORK -> MainStatus.CONNECTION_FAILED
        result.failure != null -> MainStatus.FAILED
        result.status == SyncRunStatus.NO_PENDING -> MainStatus.NO_DATA
        else -> MainStatus.SUCCESS
      }
    if (result.failure == null && result.status != SyncRunStatus.NO_PENDING) {
      viewModelScope.launch { preferences.recordSync(nowMs()) }
    }
    viewModelScope.launch {
      _uiState.value =
        _uiState.value.copy(
          pendingCount = pendingCount(),
          status = status,
          errorMessage = result.failure?.message,
        )
    }
  }

  class Factory(
    private val create: () -> MainViewModel,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
  }
}
