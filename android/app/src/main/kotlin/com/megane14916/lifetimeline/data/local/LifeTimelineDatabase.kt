package com.megane14916.lifetimeline.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.megane14916.lifetimeline.data.local.dao.AndroidAppDao
import com.megane14916.lifetimeline.data.local.dao.AndroidAppSessionDao
import com.megane14916.lifetimeline.data.local.dao.AndroidMediaItemDao
import com.megane14916.lifetimeline.data.local.dao.BackgroundWorkStateDao
import com.megane14916.lifetimeline.data.local.dao.CollectorStateDao
import com.megane14916.lifetimeline.data.local.dao.MediaCollectionStateDao
import com.megane14916.lifetimeline.data.local.dao.OpenActivityDao

@Database(
  entities = [
    AndroidAppEntity::class,
    AndroidAppSessionEntity::class,
    CollectorStateEntity::class,
    OpenActivityEntity::class,
    BackgroundWorkStateEntity::class,
    AndroidMediaItemEntity::class,
    MediaCollectionStateEntity::class,
  ],
  version = 3,
  exportSchema = true,
)
abstract class LifeTimelineDatabase : RoomDatabase() {
  abstract fun androidAppDao(): AndroidAppDao

  abstract fun androidAppSessionDao(): AndroidAppSessionDao

  abstract fun collectorStateDao(): CollectorStateDao

  abstract fun openActivityDao(): OpenActivityDao

  abstract fun backgroundWorkStateDao(): BackgroundWorkStateDao

  abstract fun androidMediaItemDao(): AndroidMediaItemDao

  abstract fun mediaCollectionStateDao(): MediaCollectionStateDao

  companion object {
    val PHOTO_INTEGRITY_CALLBACK =
      object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
          PhotoIntegrityTriggers.install(db)
        }
      }

    val MIGRATION_2_3 =
      object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `android_media_items` (
              `id` TEXT NOT NULL,
              `source` TEXT NOT NULL,
              `source_id` TEXT NOT NULL,
              `volume_name` TEXT NOT NULL,
              `media_store_id` INTEGER NOT NULL,
              `filename` TEXT NOT NULL,
              `captured_at_ms` INTEGER NOT NULL,
              `captured_at_source` TEXT NOT NULL,
              `width` INTEGER,
              `height` INTEGER,
              `mime_type` TEXT NOT NULL,
              `latitude` REAL,
              `longitude` REAL,
              `thumbnail_state` TEXT NOT NULL,
              `thumbnail_relative_path` TEXT,
              `thumbnail_sha256` TEXT,
              `thumbnail_size_bytes` INTEGER,
              `sync_status` TEXT NOT NULL,
              `discovered_at_ms` INTEGER NOT NULL,
              `synced_at_ms` INTEGER,
              `last_error_kind` TEXT,
              PRIMARY KEY(`id`),
              CHECK (`source` = 'android_media_store'),
              CHECK (`captured_at_source` IN ('date_taken', 'date_added_fallback')),
              CHECK (`width` IS NULL OR `width` > 0),
              CHECK (`height` IS NULL OR `height` > 0),
              CHECK ((`latitude` IS NULL AND `longitude` IS NULL) OR (`latitude` IS NOT NULL AND `longitude` IS NOT NULL)),
              CHECK (`latitude` IS NULL OR (`latitude` BETWEEN -90 AND 90)),
              CHECK (`longitude` IS NULL OR (`longitude` BETWEEN -180 AND 180)),
              CHECK (`thumbnail_state` IN ('pending', 'ready', 'unavailable', 'cleaned')),
              CHECK (`sync_status` IN ('pending', 'synced')),
              CHECK (`thumbnail_state` != 'ready' OR (`thumbnail_relative_path` IS NOT NULL AND `thumbnail_sha256` IS NOT NULL AND `thumbnail_size_bytes` IS NOT NULL AND `thumbnail_size_bytes` > 0)),
              CHECK (`thumbnail_state` != 'cleaned' OR (`sync_status` = 'synced' AND `thumbnail_relative_path` IS NULL AND `thumbnail_sha256` IS NOT NULL AND `thumbnail_size_bytes` IS NOT NULL))
            )
            """.trimIndent(),
          )
          db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_android_media_items_source_source_id` ON `android_media_items` (`source`, `source_id`)",
          )
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_android_media_items_sync_status_captured_at_ms_id` ON `android_media_items` (`sync_status`, `captured_at_ms`, `id`)",
          )
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_android_media_items_thumbnail_state_captured_at_ms_id` ON `android_media_items` (`thumbnail_state`, `captured_at_ms`, `id`)",
          )
          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `media_collection_state` (
              `volume_name` TEXT NOT NULL,
              `media_store_version` TEXT,
              `generation_cursor` INTEGER,
              `date_added_cursor_sec` INTEGER,
              `media_id_cursor` INTEGER,
              `collection_started_at_ms` INTEGER NOT NULL,
              `last_scan_at_ms` INTEGER,
              `updated_at_ms` INTEGER NOT NULL,
              PRIMARY KEY(`volume_name`)
            )
            """.trimIndent(),
          )
          PhotoIntegrityTriggers.install(db)
        }
      }

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
