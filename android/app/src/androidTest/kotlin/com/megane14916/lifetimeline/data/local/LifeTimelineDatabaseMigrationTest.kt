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
  fun migratesVersionOneDataAndAddsBackgroundWorkState() {
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

    val migrated =
      helper.runMigrationsAndValidate(
        "migration-v2-test",
        2,
        true,
        LifeTimelineDatabase.MIGRATION_1_2,
      )
    migrated.use {
      assertRowCount(it, "android_apps", 1)
      assertRowCount(it, "android_app_sessions", 1)
      assertRowCount(it, "collector_state", 1)
      assertRowCount(it, "open_activities", 1)
      assertRowCount(it, "background_work_state", 0)
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
