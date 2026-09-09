package com.megane14916.lifetimeline.collector

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings

fun interface UsageAccessStateProvider {
  fun isGranted(): Boolean
}

class UsageAccessChecker(
  private val stateProvider: UsageAccessStateProvider,
) {
  fun isUsageAccessGranted(): Boolean = stateProvider.isGranted()

  fun usageAccessSettingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

  companion object {
    fun from(context: Context): UsageAccessChecker =
      UsageAccessChecker(
        UsageAccessStateProvider {
          val appOpsManager = context.getSystemService(AppOpsManager::class.java)
          appOpsManager.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
          ) == AppOpsManager.MODE_ALLOWED
        },
      )
  }
}
