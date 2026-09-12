package com.megane14916.lifetimeline.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.megane14916.lifetimeline.data.local.MediaCollectionStateEntity

@Dao
interface MediaCollectionStateDao {
  @Query("SELECT * FROM media_collection_state WHERE volume_name = :volumeName LIMIT 1")
  suspend fun find(volumeName: String): MediaCollectionStateEntity?

  @Query("SELECT * FROM media_collection_state ORDER BY volume_name")
  suspend fun getAll(): List<MediaCollectionStateEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(state: MediaCollectionStateEntity)
}
