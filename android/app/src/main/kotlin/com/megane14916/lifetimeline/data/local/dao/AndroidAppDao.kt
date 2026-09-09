package com.megane14916.lifetimeline.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.megane14916.lifetimeline.data.local.AndroidAppEntity

@Dao
interface AndroidAppDao {
  @Query("SELECT * FROM android_apps WHERE package_name = :packageName LIMIT 1")
  suspend fun findByPackageName(packageName: String): AndroidAppEntity?

  @Query("SELECT * FROM android_apps WHERE id IN (:ids)")
  suspend fun findByIds(ids: List<String>): List<AndroidAppEntity>

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertIfAbsent(app: AndroidAppEntity)

  @Query(
    """
    UPDATE android_apps
    SET display_name = :displayName, updated_at_ms = :updatedAtMs
    WHERE package_name = :packageName
    """,
  )
  suspend fun updateLabel(
    packageName: String,
    displayName: String,
    updatedAtMs: Long,
  )

  @Transaction
  suspend fun upsertByPackageName(app: AndroidAppEntity): AndroidAppEntity {
    insertIfAbsent(app)
    updateLabel(app.packageName, app.displayName, app.updatedAtMs)
    return checkNotNull(findByPackageName(app.packageName))
  }
}
