package com.megane14916.lifetimeline.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.megane14916.lifetimeline.data.local.dao.AndroidAppDao
import com.megane14916.lifetimeline.data.local.dao.AndroidAppSessionDao
import com.megane14916.lifetimeline.data.local.dao.BackgroundWorkStateDao
import com.megane14916.lifetimeline.data.local.dao.CollectorStateDao
import com.megane14916.lifetimeline.data.local.dao.OpenActivityDao

@Database(
  entities = [
    AndroidAppEntity::class,
    AndroidAppSessionEntity::class,
    CollectorStateEntity::class,
    OpenActivityEntity::class,
    BackgroundWorkStateEntity::class,
  ],
  version = 2,
  exportSchema = true,
)
abstract class LifeTimelineDatabase : RoomDatabase() {
  abstract fun androidAppDao(): AndroidAppDao

  abstract fun androidAppSessionDao(): AndroidAppSessionDao

  abstract fun collectorStateDao(): CollectorStateDao

  abstract fun openActivityDao(): OpenActivityDao

  abstract fun backgroundWorkStateDao(): BackgroundWorkStateDao

  companion object {
    val MIGRATION_1_2 =
      object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `background_work_state` (
              `work_key` TEXT NOT NULL,
              `lease_owner` TEXT,
              `lease_acquired_at_ms` INTEGER,
              `lease_expires_at_ms` INTEGER,
              `last_attempt_at_ms` INTEGER,
              `last_success_at_ms` INTEGER,
              `last_result` TEXT,
              `last_error_kind` TEXT,
              `consecutive_failures` INTEGER NOT NULL DEFAULT 0,
              `updated_at_ms` INTEGER NOT NULL,
              PRIMARY KEY(`work_key`)
            )
            """.trimIndent(),
          )
        }
      }
  }
}
