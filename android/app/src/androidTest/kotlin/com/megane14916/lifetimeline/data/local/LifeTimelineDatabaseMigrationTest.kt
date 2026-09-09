package com.megane14916.lifetimeline.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
}
