package com.megane14916.lifetimeline.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.megane14916.lifetimeline.data.local.OpenActivityEntity

@Dao
interface OpenActivityDao {
  @Query("SELECT * FROM open_activities ORDER BY activity_key ASC")
  suspend fun getAll(): List<OpenActivityEntity>

  @Query("DELETE FROM open_activities")
  suspend fun deleteAll()

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(activities: List<OpenActivityEntity>)
}
