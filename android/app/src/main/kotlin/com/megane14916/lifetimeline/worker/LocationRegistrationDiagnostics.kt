package com.megane14916.lifetimeline.worker

import com.google.android.gms.common.api.ApiException

/** Stores only allowlisted registration stage and exception metadata; never exception messages or location data. */
internal object LocationRegistrationDiagnostics {
  private const val PREFIX = "location_registration:"
  private val apiStatusPattern = Regex("^api_(-?\\d+)$")
  private val exceptionTypePattern = Regex("^exception_([A-Za-z0-9_]{1,64})$")
  private val safeTypePattern = Regex("[A-Za-z0-9_]{1,64}")
  private val stages =
    setOf(
      "permission_check",
      "create_client",
      "request_updates",
      "verify_permissions",
      "remove_registration",
      "save_state",
    )

  fun errorKind(
    stage: String,
    error: Throwable,
  ): String {
    val safeStage = stage.takeIf { it in stages } ?: "unknown"
    val apiException = generateSequence(error) { it.cause }.take(8).filterIsInstance<ApiException>().firstOrNull()
    val exceptionType = error.javaClass.simpleName.takeIf { safeTypePattern.matches(it) } ?: "Unknown"
    val reason = apiException?.let { "api_${it.statusCode}" } ?: "exception_$exceptionType"
    return "$PREFIX$safeStage:$reason"
  }

  fun errorLabel(errorKind: String): String? {
    if (errorKind == "location_registration") return "位置登録"
    if (!errorKind.startsWith(PREFIX)) return null

    val detail = errorKind.removePrefix(PREFIX).split(':', limit = 2)
    if (detail.size != 2) return "位置登録（詳細不明）"

    val stage =
      when (detail[0]) {
        "permission_check" -> "権限確認"
        "create_client" -> "位置API準備"
        "request_updates" -> "位置更新登録"
        "verify_permissions" -> "登録後確認"
        "remove_registration" -> "登録解除"
        "save_state" -> "状態保存"
        else -> "不明な段階"
      }
    val reason =
      apiStatusPattern
        .matchEntire(detail[1])
        ?.groupValues
        ?.get(1)
        ?.let { "Play services status $it" }
        ?: exceptionTypePattern.matchEntire(detail[1])?.groupValues?.get(1)
        ?: "原因不明"
    return "位置登録（$stage / $reason）"
  }
}
