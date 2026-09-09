package com.megane14916.lifetimeline.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import com.megane14916.lifetimeline.domain.generateUlid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.net.URI
import java.net.URISyntaxException

data class AppSettings(
  val deviceId: String?,
  val pcBaseUrl: String?,
)

private val Context.lifeTimelineDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "life_timeline_preferences",
)

class AppPreferences private constructor(
  private val dataStore: DataStore<Preferences>,
) {
  val settings: Flow<AppSettings> =
    dataStore.data.map { preferences ->
      AppSettings(
        deviceId = preferences[DEVICE_ID_KEY],
        pcBaseUrl = preferences[PC_BASE_URL_KEY],
      )
    }

  suspend fun ensureDeviceId(): String {
    var deviceId: String? = null
    dataStore.edit { preferences ->
      deviceId = preferences[DEVICE_ID_KEY]
      if (deviceId == null) {
        deviceId = generateUlid()
        preferences[DEVICE_ID_KEY] = deviceId!!
      }
    }
    return checkNotNull(deviceId)
  }

  suspend fun getPcBaseUrl(): String? = dataStore.data.first()[PC_BASE_URL_KEY]

  suspend fun setPcBaseUrl(value: String) {
    dataStore.edit { preferences ->
      preferences[PC_BASE_URL_KEY] = normalizePcBaseUrl(value)
    }
  }

  companion object {
    private val DEVICE_ID_KEY = stringPreferencesKey("device_id")
    private val PC_BASE_URL_KEY = stringPreferencesKey("pc_base_url")

    fun create(context: Context): AppPreferences = AppPreferences(context.lifeTimelineDataStore)

    fun forTest(
      context: Context,
      fileName: String,
    ): AppPreferences =
      AppPreferences(
        PreferenceDataStoreFactory.create {
          context.preferencesDataStoreFile(fileName)
        },
      )

    fun normalizePcBaseUrl(value: String): String {
      val uri =
        try {
          URI(value.trim())
        } catch (error: URISyntaxException) {
          throw IllegalArgumentException("PC endpoint must be a valid HTTPS URL.", error)
        }
      require(uri.scheme.equals("https", ignoreCase = true)) {
        "PC endpoint must use HTTPS."
      }
      require(!uri.rawAuthority.isNullOrBlank() && !uri.host.isNullOrBlank()) {
        "PC endpoint must include a hostname."
      }
      require(uri.userInfo == null) { "PC endpoint must not include userinfo." }
      require(uri.rawQuery == null && uri.rawFragment == null) {
        "PC endpoint must not include a query or fragment."
      }

      val path = uri.rawPath.orEmpty().trimEnd('/')
      return buildString {
        append("https://")
        append(uri.rawAuthority)
        append(path)
        append('/')
      }
    }
  }
}
