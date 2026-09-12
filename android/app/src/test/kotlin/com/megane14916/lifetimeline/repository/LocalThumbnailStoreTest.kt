package com.megane14916.lifetimeline.repository

import com.megane14916.lifetimeline.domain.generateUlid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalThumbnailStoreTest {
  private lateinit var root: File
  private lateinit var store: LocalThumbnailStore
  private lateinit var id: String
  private lateinit var webp: ByteArray

  @Before
  fun setUp() {
    root = Files.createTempDirectory("local-thumbnail-store-test").toFile()
    store = LocalThumbnailStore(root)
    id = generateUlid(1_780_000_000_000)
    webp = checkNotNull(javaClass.getResourceAsStream("/sync/fixtures/synthetic-thumbnail.webp")).use { it.readBytes() }
  }

  @After
  fun tearDown() {
    root.deleteRecursively()
  }

  @Test
  fun writesVerifiedBytesToUlidRelativePathAndRejectsTraversal() {
    val stored = store.save(id, webp)

    assertEquals("${id.take(2)}/$id.webp", stored.relativePath)
    assertEquals(webp.size.toLong(), stored.sizeBytes)
    assertTrue(File(root, stored.relativePath).isFile)
    assertThrows(IllegalArgumentException::class.java) { store.delete("../outside.webp") }
  }

  @Test
  fun cleanupDeletesOnlyOldUnreferencedThumbnailFiles() {
    val stored = store.save(id, webp)
    val file = File(root, stored.relativePath)
    val nowMs = 2_000_000_000_000L
    assertTrue(file.setLastModified(nowMs - LocalThumbnailStore.ORPHAN_RETENTION_MS - 1))

    assertEquals(0, store.cleanupOrphans(setOf(stored.relativePath), nowMs))
    assertTrue(file.exists())
    assertEquals(1, store.cleanupOrphans(emptySet(), nowMs))
    assertFalse(file.exists())
  }
}
