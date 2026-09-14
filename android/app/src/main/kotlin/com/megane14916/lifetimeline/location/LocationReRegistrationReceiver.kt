package com.megane14916.lifetimeline.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.megane14916.lifetimeline.LifeTimelineApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Converts only system boot/update broadcasts into deferred registration work. */
class LocationReRegistrationReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent?,
  ) {
    if (intent?.action !in ALLOWED_ACTIONS) return
    val pendingResult = goAsync()
    val appContainer = (context.applicationContext as? LifeTimelineApplication)?.appContainer
    if (appContainer == null) {
      pendingResult.finish()
      return
    }
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      try {
        if (appContainer.preferences.settings
            .first()
            .locationCollectionEnabled
        ) {
          appContainer.backgroundWorkScheduler.ensureLocationRegistrationScheduled()
          appContainer.backgroundWorkScheduler.enqueueLocationRegistration()
        }
      } catch (_: Throwable) {
        // A later boot, package update, app launch, or watchdog can retry registration.
      } finally {
        pendingResult.finish()
      }
    }
  }

  private companion object {
    val ALLOWED_ACTIONS =
      setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
      )
  }
}
