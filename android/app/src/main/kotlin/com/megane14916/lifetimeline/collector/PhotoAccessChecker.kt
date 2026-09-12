package com.megane14916.lifetimeline.collector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

enum class PhotoAccessState {
  FULL,
  PARTIAL,
  DENIED,
}

fun interface PhotoPermissionStateProvider {
  fun isGranted(permission: String): Boolean
}

/** Reads the current OS photo-library permission state without caching it. */
class PhotoAccessChecker(
  private val apiLevel: Int,
  private val permissionStateProvider: PhotoPermissionStateProvider,
) {
  fun currentAccess(): PhotoAccessState = resolveAccess(apiLevel, permissionStateProvider::isGranted)

  fun runtimePermissions(): Array<String> = permissionsFor(apiLevel)

  companion object {
    fun from(context: Context): PhotoAccessChecker =
      PhotoAccessChecker(
        apiLevel = Build.VERSION.SDK_INT,
        permissionStateProvider =
          PhotoPermissionStateProvider { permission ->
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
          },
      )

    fun permissionsFor(apiLevel: Int): Array<String> =
      when {
        apiLevel >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
          arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
          )
        }

        apiLevel >= Build.VERSION_CODES.TIRAMISU -> {
          arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        }

        else -> {
          arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
      }

    fun resolveAccess(
      apiLevel: Int,
      isGranted: (String) -> Boolean,
    ): PhotoAccessState =
      when {
        apiLevel >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
          isGranted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccessState.FULL

        apiLevel >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
          isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccessState.PARTIAL

        apiLevel >= Build.VERSION_CODES.TIRAMISU &&
          isGranted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccessState.FULL

        apiLevel < Build.VERSION_CODES.TIRAMISU &&
          isGranted(Manifest.permission.READ_EXTERNAL_STORAGE) -> PhotoAccessState.FULL

        else -> PhotoAccessState.DENIED
      }
  }
}
