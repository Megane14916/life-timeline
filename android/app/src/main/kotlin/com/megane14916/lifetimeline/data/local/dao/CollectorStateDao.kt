package com.megane14916.lifetimeline.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.megane14916.lifetimeline.data.local.CollectorStateEntity

@Dao
interface CollectorStateDao {
  @Query("SELECT * FROM collector_state WHERE collector = :collector LIMIT 1")
  suspend fun find(collector: String): CollectorStateEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(state: CollectorStateEntity)
}
