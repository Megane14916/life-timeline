package com.megane14916.lifetimeline.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/** Enforces LocationPoint invariants on fresh installs as well as migrated databases. */
object LocationPointIntegrityTriggers {
  fun install(database: SupportSQLiteDatabase) {
    val invalidRow =
      """
      NEW.source != 'android_fused_location' OR
      NEW.recorded_at_ms < 0 OR
      NEW.latitude < -90 OR NEW.latitude > 90 OR NEW.latitude != NEW.latitude OR abs(NEW.latitude) > 1.7976931348623157e308 OR
      NEW.longitude < -180 OR NEW.longitude > 180 OR NEW.longitude != NEW.longitude OR abs(NEW.longitude) > 1.7976931348623157e308 OR
      (NEW.accuracy_m IS NOT NULL AND (NEW.accuracy_m < 0 OR NEW.accuracy_m != NEW.accuracy_m OR abs(NEW.accuracy_m) > 1.7976931348623157e308)) OR
      (NEW.altitude_m IS NOT NULL AND (NEW.altitude_m != NEW.altitude_m OR abs(NEW.altitude_m) > 1.7976931348623157e308)) OR
      (NEW.speed_mps IS NOT NULL AND (NEW.speed_mps < 0 OR NEW.speed_mps != NEW.speed_mps OR abs(NEW.speed_mps) > 1.7976931348623157e308)) OR
      NEW.elapsed_realtime_nanos < 0 OR NEW.received_at_ms < 0 OR
      NEW.sync_status NOT IN ('pending', 'synced') OR
      (NEW.synced_at_ms IS NOT NULL AND NEW.synced_at_ms < 0)
      """.trimIndent()
    database.execSQL(
      """
      CREATE TRIGGER IF NOT EXISTS `validate_android_location_point_insert`
      BEFORE INSERT ON `android_location_points`
      WHEN $invalidRow
      BEGIN SELECT RAISE(ABORT, 'invalid android location point'); END
      """.trimIndent(),
    )
    database.execSQL(
      """
      CREATE TRIGGER IF NOT EXISTS `validate_android_location_point_update`
      BEFORE UPDATE ON `android_location_points`
      WHEN $invalidRow
      BEGIN SELECT RAISE(ABORT, 'invalid android location point'); END
      """.trimIndent(),
    )
  }
}
