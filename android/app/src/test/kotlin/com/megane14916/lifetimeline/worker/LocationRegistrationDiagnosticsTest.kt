package com.megane14916.lifetimeline.worker

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocationRegistrationDiagnosticsTest {
  @Test
  fun playServicesErrorLabelIncludesOnlyStageAndStatusCode() {
    val error = ApiException(Status(17, "private details must not be persisted"))
    val errorKind = LocationRegistrationDiagnostics.errorKind("request_updates", error)

    assertEquals("location_registration:request_updates:api_17", errorKind)
    assertEquals("位置登録（位置更新登録 / Play services status 17）", LocationRegistrationDiagnostics.errorLabel(errorKind))
    assertFalse(errorKind.contains("private details"))
  }

  @Test
  fun unexpectedErrorLabelIncludesTypeButNotExceptionMessage() {
    val error = IllegalStateException("coordinates must not be persisted")
    val errorKind = LocationRegistrationDiagnostics.errorKind("request_updates", error)

    assertEquals("location_registration:request_updates:exception_IllegalStateException", errorKind)
    assertFalse(errorKind.contains("coordinates"))
  }
}
