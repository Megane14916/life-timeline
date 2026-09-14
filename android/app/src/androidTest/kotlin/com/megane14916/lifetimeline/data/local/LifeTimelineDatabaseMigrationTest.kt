package com.megane14916.lifetimeline.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class LifeTimelineDatabaseMigrationTest {
  @get:Rule
  val helper =
    MigrationTestHelper(
      InstrumentationRegistry.getInstrumentation(),
      LifeTimelineDatabase::class.java,
    )

  @Test
  @Throws(IOException::class)
  fun createsVersionOneSchema() {
    helper.createDatabase("migration-test", 1).close()
  }

  @Test
  @Throws(IOException::class)
  fun migratesVersionOneDataThroughVersionThreeWithoutChangingExistingRows() {
    val database = helper.createDatabase("migration-v2-test", 1)
    database.execSQL(
      "INSERT INTO android_apps (id, package_name, display_name, updated_at_ms) VALUES ('app-1', 'com.example.app', 'Example', 1000)",
    )
    database.execSQL(
      "INSERT INTO android_app_sessions (id, app_id, started_at_ms, ended_at_ms, duration_ms, source, source_key, sync_status, collected_at_ms, synced_at_ms) VALUES ('session-1', 'app-1', 1000, 2000, 1000, 'test', 'source-1', 'pending', 2000, NULL)",
    )
    database.execSQL(
      "INSERT INTO collector_state (collector, cursor_at_ms, cursor_key, last_collected_at_ms) VALUES ('collector-1', 2000, 'cursor-1', 2000)",
    )
    database.execSQL(
      "INSERT INTO open_activities (activity_key, package_name, class_name, started_at_ms, start_event_key) VALUES ('activity-1', 'com.example.app', 'MainActivity', 1000, 'event-1')",
    )
    database.close()

    helper.runMigrationsAndValidate("migration-v2-test", 2, true, LifeTimelineDatabase.MIGRATION_1_2).close()
    val migrated =
      helper.runMigrationsAndValidate(
        "migration-v2-test",
        3,
        true,
        LifeTimelineDatabase.MIGRATION_1_2,
        LifeTimelineDatabase.MIGRATION_2_3,
      )
    migrated.use {
      assertRowCount(it, "android_apps", 1)
      assertRowCount(it, "android_app_sessions", 1)
      assertRowCount(it, "collector_state", 1)
      assertRowCount(it, "open_activities", 1)
      assertRowCount(it, "background_work_state", 0)
      assertRowCount(it, "android_media_items", 0)
      assertRowCount(it, "media_collection_state", 0)
      it.query("SELECT cursor_at_ms, cursor_key FROM collector_state WHERE collector = 'collector-1'").use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals(2000L, cursor.getLong(0))
        assertEquals("cursor-1", cursor.getString(1))
      }
    }
  }

  @Test
  @Throws(IOException::class)
  fun migratesVersionTwoDataToVersionThreeWithoutChangingBackgroundLease() {
    val database = helper.createDatabase("migration-v2-direct-test", 2)
    database.execSQL(
      "INSERT INTO background_work_state (work_key, lease_owner, lease_acquired_at_ms, lease_expires_at_ms, last_attempt_at_ms, last_success_at_ms, last_result, last_error_kind, consecutive_failures, updated_at_ms) VALUES ('photo_collection_v1', 'owner-1', 1000, 5000, 1000, NULL, NULL, NULL, 0, 1000)",
    )
    database.close()

    helper.runMigrationsAndValidate("migration-v2-direct-test", 3, true, LifeTimelineDatabase.MIGRATION_2_3).use {
      assertRowCount(it, "background_work_state", 1)
      it.query("SELECT lease_owner, lease_expires_at_ms FROM background_work_state WHERE work_key = 'photo_collection_v1'").use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals("owner-1", cursor.getString(0))
        assertEquals(5000L, cursor.getLong(1))
      }
      assertRowCount(it, "android_media_items", 0)
      assertRowCount(it, "media_collection_state", 0)
    }
  }

  @Test
  @Throws(IOException::class)
  fun resetsLegacyPhotoGenerationCursorWhenMigratingToVersionFour() {
    val database = helper.createDatabase("migration-v3-photo-cursor-test", 3)
    database.execSQL(
      "INSERT INTO media_collection_state (volume_name, media_store_version, generation_cursor, date_added_cursor_sec, media_id_cursor, collection_started_at_ms, last_scan_at_ms, updated_at_ms) VALUES ('external_primary', 'store-v1', 923, NULL, 811, 1000, 2000, 2000)",
    )
    database.close()

    helper
      .runMigrationsAndValidate(
        "migration-v3-photo-cursor-test",
        4,
        true,
        LifeTimelineDatabase.MIGRATION_3_4,
      ).use {
        it
          .query(
            "SELECT media_store_version, generation_cursor, media_id_cursor, collection_started_at_ms FROM media_collection_state WHERE volume_name = 'external_primary'",
          ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("store-v1", cursor.getString(0))
            assertEquals(0L, cursor.getLong(1))
            assertEquals(0L, cursor.getLong(2))
            assertEquals(1000L, cursor.getLong(3))
          }
      }
  }

  @Test
  @Throws(IOException::class)
  fun migratesVersionOneThroughFiveAndPreservesLegacyRows() {
    val database = helper.createDatabase("migration-v1-to-v5-test", 1)
    database.execSQL(
      "INSERT INTO android_apps (id, package_name, display_name, updated_at_ms) VALUES ('app-1', 'com.example.app', 'Example', 1000)",
    )
    database.execSQL(
      "INSERT INTO android_app_sessions (id, app_id, started_at_ms, ended_at_ms, duration_ms, source, source_key, sync_status, collected_at_ms, synced_at_ms) VALUES ('session-1', 'app-1', 1000, 2000, 1000, 'test', 'source-1', 'pending', 2000, NULL)",
    )
    database.execSQL(
      "INSERT INTO collector_state (collector, cursor_at_ms, cursor_key, last_collected_at_ms) VALUES ('collector-1', 2000, 'cursor-1', 2000)",
    )
    database.execSQL(
      "INSERT INTO open_activities (activity_key, package_name, class_name, started_at_ms, start_event_key) VALUES ('activity-1', 'com.example.app', 'MainActivity', 1000, 'event-1')",
    )
    database.close()

    helper
      .runMigrationsAndValidate(
        "migration-v1-to-v5-test",
        5,
        true,
        LifeTimelineDatabase.MIGRATION_1_2,
        LifeTimelineDatabase.MIGRATION_2_3,
        LifeTimelineDatabase.MIGRATION_3_4,
        LifeTimelineDatabase.MIGRATION_4_5,
      ).use {
        assertRowCount(it, "android_apps", 1)
        assertRowCount(it, "android_app_sessions", 1)
        assertRowCount(it, "collector_state", 1)
        assertRowCount(it, "open_activities", 1)
        assertRowCount(it, "background_work_state", 0)
        assertRowCount(it, "android_media_items", 0)
        assertRowCount(it, "media_collection_state", 0)
        assertRowCount(it, "android_location_points", 0)
        it.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'validate_android_media_item_%'").use { cursor ->
          assertTrue(cursor.moveToFirst())
          assertEquals(2, cursor.getInt(0))
        }
      }
  }

  @Test
  @Throws(IOException::class)
  fun migratesVersionFourFixtureAndPreservesAllExistingTablesAndPhotoTriggers() {
    val database = helper.createDatabase("migration-v4-to-v5-test", 4)
    PhotoIntegrityTriggers.install(database)
    database.execSQL(
      "INSERT INTO android_apps (id, package_name, display_name, updated_at_ms) VALUES ('app-1', 'com.example.app', 'Example', 1000)",
    )
    database.execSQL(
      "INSERT INTO android_app_sessions (id, app_id, started_at_ms, ended_at_ms, duration_ms, source, source_key, sync_status, collected_at_ms, synced_at_ms) VALUES ('session-1', 'app-1', 1000, 2000, 1000, 'test', 'source-1', 'pending', 2000, NULL)",
    )
    database.execSQL(
      "INSERT INTO collector_state (collector, cursor_at_ms, cursor_key, last_collected_at_ms) VALUES ('collector-1', 2000, 'cursor-1', 2000)",
    )
    database.execSQL(
      "INSERT INTO open_activities (activity_key, package_name, class_name, started_at_ms, start_event_key) VALUES ('activity-1', 'com.example.app', 'MainActivity', 1000, 'event-1')",
    )
    database.execSQL(
      "INSERT INTO background_work_state (work_key, consecutive_failures, updated_at_ms) VALUES ('photo_collection_v1', 2, 2000)",
    )
    database.execSQL(
      "INSERT INTO android_media_items (id, source, source_id, volume_name, media_store_id, filename, captured_at_ms, captured_at_source, mime_type, thumbnail_state, sync_status, discovered_at_ms) VALUES ('photo-1', 'android_media_store', 'external:1', 'external', 1, 'photo.jpg', 1500, 'date_taken', 'image/jpeg', 'pending', 'pending', 2000)",
    )
    database.execSQL(
      "INSERT INTO media_collection_state (volume_name, collection_started_at_ms, updated_at_ms) VALUES ('external', 1000, 2000)",
    )
    database.close()

    helper
      .runMigrationsAndValidate("migration-v4-to-v5-test", 5, true, LifeTimelineDatabase.MIGRATION_4_5)
      .use {
        assertRowCount(it, "android_apps", 1)
        assertRowCount(it, "android_app_sessions", 1)
        assertRowCount(it, "collector_state", 1)
        assertRowCount(it, "open_activities", 1)
        assertRowCount(it, "background_work_state", 1)
        assertRowCount(it, "android_media_items", 1)
        assertRowCount(it, "media_collection_state", 1)
        assertRowCount(it, "android_location_points", 0)
        it.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'validate_android_media_item_%'").use { cursor ->
          assertTrue(cursor.moveToFirst())
          assertEquals(2, cursor.getInt(0))
        }
      }
  }

  private fun assertRowCount(
    database: androidx.sqlite.db.SupportSQLiteDatabase,
    table: String,
    expected: Int,
  ) {
    database.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(expected, cursor.getInt(0))
    }
  }
}
