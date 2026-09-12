package com.megane14916.lifetimeline.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/** Keeps photo invariants enforced by SQLite on both fresh databases and migrated databases. */
object PhotoIntegrityTriggers {
  fun install(database: SupportSQLiteDatabase) {
    val invalidRow =
      """
      NEW.source != 'android_media_store' OR
      NEW.captured_at_source NOT IN ('date_taken', 'date_added_fallback') OR
      (NEW.width IS NOT NULL AND NEW.width <= 0) OR
      (NEW.height IS NOT NULL AND NEW.height <= 0) OR
      ((NEW.latitude IS NULL) != (NEW.longitude IS NULL)) OR
      (NEW.latitude IS NOT NULL AND (NEW.latitude < -90 OR NEW.latitude > 90)) OR
      (NEW.longitude IS NOT NULL AND (NEW.longitude < -180 OR NEW.longitude > 180)) OR
      NEW.thumbnail_state NOT IN ('pending', 'ready', 'unavailable', 'cleaned') OR
      NEW.sync_status NOT IN ('pending', 'synced') OR
      (NEW.thumbnail_state = 'ready' AND (NEW.thumbnail_relative_path IS NULL OR NEW.thumbnail_sha256 IS NULL OR NEW.thumbnail_size_bytes IS NULL OR NEW.thumbnail_size_bytes <= 0)) OR
      (NEW.thumbnail_state = 'cleaned' AND (NEW.sync_status != 'synced' OR NEW.thumbnail_relative_path IS NOT NULL OR NEW.thumbnail_sha256 IS NULL OR NEW.thumbnail_size_bytes IS NULL OR NEW.thumbnail_size_bytes <= 0))
      """.trimIndent()
    database.execSQL(
      """
      CREATE TRIGGER IF NOT EXISTS `validate_android_media_item_insert`
      BEFORE INSERT ON `android_media_items`
      WHEN $invalidRow
      BEGIN SELECT RAISE(ABORT, 'invalid android media item'); END
      """.trimIndent(),
    )
    database.execSQL(
      """
      CREATE TRIGGER IF NOT EXISTS `validate_android_media_item_update`
      BEFORE UPDATE ON `android_media_items`
      WHEN $invalidRow
      BEGIN SELECT RAISE(ABORT, 'invalid android media item'); END
      """.trimIndent(),
    )
  }
}
