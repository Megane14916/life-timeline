package com.megane14916.lifetimeline.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.megane14916.lifetimeline.data.local.dao.AndroidAppDao
import com.megane14916.lifetimeline.data.local.dao.AndroidAppSessionDao
import com.megane14916.lifetimeline.data.local.dao.CollectorStateDao
import com.megane14916.lifetimeline.data.local.dao.OpenActivityDao

@Database(
  entities = [
    AndroidAppEntity::class,
    AndroidAppSessionEntity::class,
    CollectorStateEntity::class,
    OpenActivityEntity::class,
  ],
  version = 1,
  exportSchema = true,
)
abstract class LifeTimelineDatabase : RoomDatabase() {
  abstract fun androidAppDao(): AndroidAppDao

  abstract fun androidAppSessionDao(): AndroidAppSessionDao

  abstract fun collectorStateDao(): CollectorStateDao

  abstract fun openActivityDao(): OpenActivityDao
}
