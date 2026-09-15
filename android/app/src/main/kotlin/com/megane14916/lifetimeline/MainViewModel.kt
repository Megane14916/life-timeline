package com.megane14916.lifetimeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.megane14916.lifetimeline.collector.LocationAccessState
import com.megane14916.lifetimeline.collector.LocationCurrentFixProvider
import com.megane14916.lifetimeline.collector.LocationPermissionChecker
import com.megane14916.lifetimeline.collector.LocationRegistrationClient
import com.megane14916.lifetimeline.collector.PhotoAccessChecker
import com.megane14916.lifetimeline.collector.PhotoAccessState
import com.megane14916.lifetimeline.collector.UsageAccessChecker
import com.megane14916.lifetimeline.data.preferences.AppPreferences
import com.megane14916.lifetimeline.repository.BackgroundExecutionCoordinator
import com.megane14916.lifetimeline.repository.BackgroundLease
import com.megane14916.lifetimeline.repository.CollectionCoordinator
import com.megane14916.lifetimeline.repository.CollectionRunResult
import com.megane14916.lifetimeline.repository.CollectionRunStatus
import com.megane14916.lifetimeline.repository.LocationUpdateProcessor
import com.megane14916.lifetimeline.repository.SyncFailureKind
import com.megane14916.lifetimeline.repository.SyncRepository
import com.megane14916.lifetimeline.repository.SyncResult
import com.megane14916.lifetimeline.repository.SyncRunStatus
import com.megane14916.lifetimeline.worker.AutomaticSyncPolicy
import com.megane14916.lifetimeline.worker.BackgroundWorkScheduler
import com.megane14916.lifetimeline.worker.LocationWorkPolicy
import com.megane14916.lifetimeline.worker.PhotoWorkPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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
  val photoAccessState: PhotoAccessState = PhotoAccessState.DENIED,
  val locationAccessState: LocationAccessState = LocationAccessState.DISABLED,
  val locationCollectionEnabled: Boolean = false,
  val latestLocationReceivedAtMs: Long? = null,
  val pendingLocationCount: Int = 0,
  val locationRegistrationAttemptAtMs: Long? = null,
  val locationRegistrationSuccessAtMs: Long? = null,
  val locationRegistrationResult: String? = null,
  val locationRegistrationErrorKind: String? = null,
  val locationRegistrationWorkState: WorkInfo.State? = null,
  val currentLocationCaptureInProgress: Boolean = false,
  val currentLocationCaptureMessage: String? = null,
  val locationSyncAttemptAtMs: Long? = null,
  val locationSyncSuccessAtMs: Long? = null,
  val locationSyncResult: String? = null,
  val locationSyncErrorKind: String? = null,
  val locationSyncWorkState: WorkInfo.State? = null,
  val locationSyncRunAttemptCount: Int = 0,
  val photoCollectionEnabled: Boolean = false,
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
  val photoCollectionScheduled: Boolean = false,
  val nextPhotoCollectionAtMs: Long? = null,
  val photoCollectionAttemptAtMs: Long? = null,
  val photoCollectionSuccessAtMs: Long? = null,
  val photoCollectionResult: String? = null,
  val photoSyncAttemptAtMs: Long? = null,
  val photoSyncSuccessAtMs: Long? = null,
  val photoSyncResult: String? = null,
  val photoRecentErrorKind: String? = null,
  val pendingPhotoCount: Int = 0,
  val pendingPhotoThumbnailCount: Int = 0,
  val localPhotoThumbnailBytes: Long = 0,
  val photoSyncWorkState: WorkInfo.State? = null,
  val photoSyncRunAttemptCount: Int = 0,
)

class MainViewModel(
  private val preferences: AppPreferences,
  private val usageAccessChecker: UsageAccessChecker,
  private val photoAccessChecker: PhotoAccessChecker,
  private val collectionCoordinator: CollectionCoordinator,
  private val pendingCount: suspend () -> Int,
  private val syncRepositoryFactory: suspend (String) -> SyncRepository,
  private val onPcBaseUrlSaved: () -> Unit = {},
  private val nowMs: () -> Long = { System.currentTimeMillis() },
  private val backgroundExecutionCoordinator: BackgroundExecutionCoordinator? = null,
  private val backgroundWorkScheduler: BackgroundWorkScheduler? = null,
  private val pendingPhotoCount: suspend () -> Int = { 0 },
  private val pendingPhotoThumbnailCount: suspend () -> Int = { 0 },
  private val localPhotoThumbnailBytes: suspend () -> Long = { 0L },
  private val locationPermissionChecker: LocationPermissionChecker? = null,
  private val locationRegistrationClient: LocationRegistrationClient? = null,
  private val pendingLocationCount: suspend () -> Int = { 0 },
  private val latestLocationReceivedAt: suspend () -> Long? = { null },
  private val locationCurrentFixProvider: LocationCurrentFixProvider? = null,
  private val locationUpdateProcessor: LocationUpdateProcessor? = null,
) : ViewModel() {
  private val _uiState = MutableStateFlow(MainUiState())
  val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
  private var currentLocationCaptureJob: Job? = null

  init {
    viewModelScope.launch {
      preferences.settings.collectLatest { settings ->
        _uiState.value =
          _uiState.value.copy(
            pcBaseUrl = settings.pcBaseUrl,
            lastCollectionAtMs = settings.lastCollectionAtMs,
            lastSyncAtMs = settings.lastSyncAtMs,
            usageAccessGranted = usageAccessChecker.isUsageAccessGranted(),
            photoCollectionEnabled = settings.photoCollectionEnabled,
            locationCollectionEnabled = settings.locationCollectionEnabled,
            pendingCount = pendingCount(),
          )
      }
    }
    refreshPhotoAccess()
    refreshLocationAccess()
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

  /** Re-reads the OS state whenever the app resumes or a permission result returns. */
  fun refreshPhotoAccess() {
    val access = photoAccessChecker.currentAccess()
    _uiState.value = _uiState.value.copy(photoAccessState = access)
    if (access != PhotoAccessState.DENIED) {
      viewModelScope.launch {
        if (preferences.settings.first().photoCollectionEnabled) {
          backgroundWorkScheduler?.ensurePhotoCollectionScheduled()
          backgroundWorkScheduler?.enqueuePhotoCollectionNow()
        }
      }
    }
  }

  fun enablePhotoCollection(onRequestPermissions: () -> Unit) {
    viewModelScope.launch {
      preferences.enablePhotoCollection(nowMs())
      _uiState.value = _uiState.value.copy(photoCollectionEnabled = true)
      onRequestPermissions()
    }
  }

  fun disablePhotoCollection() {
    viewModelScope.launch {
      preferences.disablePhotoCollection()
      backgroundWorkScheduler?.cancelPhotoCollection()
      _uiState.value = _uiState.value.copy(photoCollectionEnabled = false)
    }
  }

  fun enableLocationCollection(onRequestPermissions: () -> Unit) {
    viewModelScope.launch {
      preferences.enableLocationCollection(nowMs())
      _uiState.value = _uiState.value.copy(locationCollectionEnabled = true, currentLocationCaptureMessage = null)
      onRequestPermissions()
    }
  }

  fun disableLocationCollection() {
    currentLocationCaptureJob?.cancel()
    viewModelScope.launch {
      preferences.disableLocationCollection()
      backgroundWorkScheduler?.cancelLocationRegistration()
      runCatching { locationRegistrationClient?.unregister() }
      _uiState.value =
        _uiState.value.copy(
          locationCollectionEnabled = false,
          currentLocationCaptureInProgress = false,
          currentLocationCaptureMessage = null,
        )
      refreshLocationAccessInternal()
    }
  }

  /** Rechecks OS state on every app resume and lets registration work reconcile external changes. */
  fun refreshLocationAccess() {
    viewModelScope.launch {
      refreshLocationAccessInternal()
      if (_uiState.value.locationCollectionEnabled) {
        backgroundWorkScheduler?.ensureLocationRegistrationScheduled()
        backgroundWorkScheduler?.enqueueLocationRegistration()
      } else {
        backgroundWorkScheduler?.cancelLocationRegistration()
      }
      val settings = preferences.settings.first()
      if (pendingLocationCount() > 0 && !settings.pcBaseUrl.isNullOrBlank()) {
        backgroundWorkScheduler?.enqueueLocationSync()
      }
      refreshBackgroundStateInternal()
    }
  }

  private suspend fun refreshLocationAccessInternal() {
    val settings = preferences.settings.first()
    val access =
      locationPermissionChecker?.currentAccess(settings.locationCollectionEnabled)
        ?: if (settings.locationCollectionEnabled) {
          LocationAccessState.FOREGROUND_PERMISSION_REQUIRED
        } else {
          LocationAccessState.DISABLED
        }
    _uiState.value =
      _uiState.value.copy(
        locationCollectionEnabled = settings.locationCollectionEnabled,
        locationAccessState = access,
      )
  }

  /** Requests one bounded photo scan without replacing the periodic work request. */
  fun collectPhotosNow() {
    if (!_uiState.value.photoCollectionEnabled || _uiState.value.photoAccessState == PhotoAccessState.DENIED) return
    backgroundWorkScheduler?.enqueuePhotoCollectionNow()
    refreshBackgroundState()
  }

  /** Explicitly reruns Location registration without changing opt-in or permissions. */
  fun checkLocationRegistration() {
    viewModelScope.launch {
      if (!_uiState.value.locationCollectionEnabled) return@launch
      backgroundWorkScheduler?.ensureLocationRegistrationScheduled()
      backgroundWorkScheduler?.enqueueLocationRegistration()
      refreshBackgroundStateInternal()
    }
  }

  /** Gets one fresh point only after an explicit user action, then persists and syncs it through the normal path. */
  fun captureCurrentLocation() {
    if (currentLocationCaptureJob?.isActive == true) return
    currentLocationCaptureJob =
      viewModelScope.launch {
        if (!_uiState.value.locationCollectionEnabled) return@launch
        val currentSettings = preferences.settings.first()
        if (!currentSettings.locationCollectionEnabled) {
          _uiState.value = _uiState.value.copy(currentLocationCaptureMessage = "位置情報収集が無効のため、現在地を取得しませんでした。")
          return@launch
        }
        val access =
          locationPermissionChecker?.currentAccess(collectionEnabled = currentSettings.locationCollectionEnabled)
            ?: _uiState.value.locationAccessState
        if (access != LocationAccessState.APPROXIMATE && access != LocationAccessState.PRECISE) {
          _uiState.value = _uiState.value.copy(currentLocationCaptureMessage = "位置情報の許可と端末の位置情報サービスを確認してください。")
          return@launch
        }
        val currentFixProvider = locationCurrentFixProvider
        val updateProcessor = locationUpdateProcessor
        if (currentFixProvider == null || updateProcessor == null) {
          _uiState.value = _uiState.value.copy(currentLocationCaptureMessage = "現在地取得を利用できません。アプリを更新してください。")
          return@launch
        }

        _uiState.value =
          _uiState.value.copy(
            currentLocationCaptureInProgress = true,
            currentLocationCaptureMessage = null,
          )
        try {
          val fix = currentFixProvider.getCurrentLocation()
          if (fix == null) {
            _uiState.value =
              _uiState.value.copy(currentLocationCaptureMessage = "新しい位置情報を取得できませんでした。位置情報サービスを確認して再試行してください。")
            return@launch
          }
          val result = updateProcessor.persistBatch(listOf(fix))
          if (result == null) {
            _uiState.value =
              _uiState.value.copy(currentLocationCaptureMessage = "収集設定または権限が変わったため、位置情報を保存しませんでした。")
            return@launch
          }

          val pending = pendingLocationCount()
          val endpointConfigured =
            !preferences.settings
              .first()
              .pcBaseUrl
              .isNullOrBlank()
          if (pending > 0 && endpointConfigured) backgroundWorkScheduler?.enqueueLocationSync()
          val message =
            when {
              result.insertedCount > 0 && pending > 0 && endpointConfigured -> "現在地を端末に保存し、PCへの同期を依頼しました。"
              result.insertedCount > 0 -> "現在地を端末に保存しました。PC URLを設定すると同期されます。"
              result.duplicateCount > 0 -> "この位置情報はすでに記録されています。"
              result.invalidCount > 0 -> "取得した位置情報は記録条件を満たさず保存されませんでした。もう一度お試しください。"
              else -> "位置情報を保存できませんでした。状態を確認してください。"
            }
          _uiState.value = _uiState.value.copy(currentLocationCaptureMessage = message)
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (_: SecurityException) {
          _uiState.value =
            _uiState.value.copy(currentLocationCaptureMessage = "位置情報の許可を確認してください。")
        } catch (_: Throwable) {
          _uiState.value =
            _uiState.value.copy(currentLocationCaptureMessage = "現在地を取得できませんでした。位置情報の状態を確認して再試行してください。")
        } finally {
          _uiState.value = _uiState.value.copy(currentLocationCaptureInProgress = false)
          if (currentCoroutineContext().isActive) refreshBackgroundStateInternal()
        }
      }
  }

  /** Requests the independent Location uploader for locally pending points. */
  fun syncLocationNow() {
    val current = _uiState.value
    if (current.pcBaseUrl.isNullOrBlank() || current.pendingLocationCount <= 0) return
    backgroundWorkScheduler?.enqueueLocationSync()
    refreshBackgroundState()
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
            executionCoordinator.recordFailure(it, "retry", "unexpected")
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
            executionCoordinator.recordFailure(it, "retry", "unexpected")
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
    val photoCollectionState = coordinator?.findState(PhotoWorkPolicy.COLLECTION_LEASE_KEY)
    val photoSyncState = coordinator?.findState(PhotoWorkPolicy.SYNC_LEASE_KEY)
    val locationRegistrationState = coordinator?.findState(LocationWorkPolicy.REGISTRATION_LEASE_KEY)
    val locationSyncState = coordinator?.findState(LocationWorkPolicy.SYNC_LEASE_KEY)
    val collectionWorkInfo = backgroundWorkScheduler?.currentCollectionWorkInfo()
    val syncWorkInfo = backgroundWorkScheduler?.currentSyncWorkInfo()
    val photoCollectionWorkInfo = backgroundWorkScheduler?.currentPhotoCollectionWorkInfo()
    val photoSyncWorkInfo = backgroundWorkScheduler?.currentPhotoSyncWorkInfo()
    val locationRegistrationWorkInfo = backgroundWorkScheduler?.currentLocationRegistrationWorkInfo()
    val locationSyncWorkInfo = backgroundWorkScheduler?.currentLocationSyncWorkInfo()
    val pendingPhotos = pendingPhotoCount()
    val pendingThumbnails = pendingPhotoThumbnailCount()
    val thumbnailBytes = localPhotoThumbnailBytes()
    val pendingLocations = pendingLocationCount()
    val latestLocationReceived = latestLocationReceivedAt()
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
        photoCollectionScheduled =
          _uiState.value.photoCollectionEnabled &&
            photoCollectionWorkInfo?.state != WorkInfo.State.CANCELLED && photoCollectionWorkInfo != null,
        nextPhotoCollectionAtMs = photoCollectionWorkInfo?.nextScheduleTimeMillis?.takeIf { it > 0 },
        photoCollectionAttemptAtMs = photoCollectionState?.lastAttemptAtMs,
        photoCollectionSuccessAtMs = photoCollectionState?.lastSuccessAtMs,
        photoCollectionResult = photoCollectionState?.lastResult,
        photoSyncAttemptAtMs = photoSyncState?.lastAttemptAtMs,
        photoSyncSuccessAtMs = photoSyncState?.lastSuccessAtMs,
        photoSyncResult = photoSyncState?.lastResult,
        photoRecentErrorKind = photoSyncState?.lastErrorKind ?: photoCollectionState?.lastErrorKind,
        pendingPhotoCount = pendingPhotos,
        pendingPhotoThumbnailCount = pendingThumbnails,
        localPhotoThumbnailBytes = thumbnailBytes,
        photoSyncWorkState = photoSyncWorkInfo?.state,
        photoSyncRunAttemptCount = photoSyncWorkInfo?.runAttemptCount ?: 0,
        latestLocationReceivedAtMs = latestLocationReceived,
        pendingLocationCount = pendingLocations,
        locationRegistrationAttemptAtMs = locationRegistrationState?.lastAttemptAtMs,
        locationRegistrationSuccessAtMs = locationRegistrationState?.lastSuccessAtMs,
        locationRegistrationResult = locationRegistrationState?.lastResult,
        locationRegistrationErrorKind = locationRegistrationState?.lastErrorKind,
        locationRegistrationWorkState = locationRegistrationWorkInfo?.state,
        locationSyncAttemptAtMs = locationSyncState?.lastAttemptAtMs,
        locationSyncSuccessAtMs = locationSyncState?.lastSuccessAtMs,
        locationSyncResult = locationSyncState?.lastResult,
        locationSyncErrorKind = locationSyncState?.lastErrorKind,
        locationSyncWorkState = locationSyncWorkInfo?.state,
        locationSyncRunAttemptCount = locationSyncWorkInfo?.runAttemptCount ?: 0,
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
