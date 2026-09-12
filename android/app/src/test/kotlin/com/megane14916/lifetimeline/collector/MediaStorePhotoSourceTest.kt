package com.megane14916.lifetimeline.collector

import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStorePhotoSourceTest {
  @Test
  fun api26UsesLegacyDateAddedCursorAndFiltersDcimInsideTheAdapter() {
    val backend =
      FakeBackend(
        rows =
          listOf(
            photoRow(
              7,
              legacyPath = "/storage/emulated/0/DCIM/Camera/7.jpg",
              dateTakenMs = 0,
            ),
          ),
      )
    val source = MediaStorePhotoSource(26, backend)

    assertEquals(listOf("external"), source.externalVolumeNames())
    val page = source.queryPage("external", PhotoAccessState.FULL, null, null, 10_000, limit = 5)

    assertEquals(MediaStorePhotoScanStatus.SUCCESS, page.status)
    assertEquals("external:7", page.photos.single().sourceId)
    assertEquals(20_000L, page.photos.single().capturedAtMs)
    assertTrue(backend.lastPlan!!.useLegacyDataPath)
    assertTrue(backend.lastPlan!!.selection.contains(MediaStore.Images.Media.DATE_ADDED))
    assertFalse(backend.lastPlan!!.selection.contains(MediaStore.MediaColumns.RELATIVE_PATH))
  }

  @Test
  fun api29UsesVolumeEnumerationPendingFilterAndDateIdCursor() {
    val backend =
      FakeBackend(
        volumes = listOf("z-volume", "a-volume"),
        versions = mapOf("z-volume" to "v1", "a-volume" to "v2"),
        rows = listOf(photoRow(17, relativePath = "DCIM/Camera/17.jpg")),
      )
    val source = MediaStorePhotoSource(29, backend)
    val currentCursor = MediaStorePhotoCursor(dateAddedCursorSeconds = 20, mediaStoreIdCursor = 16)

    assertEquals(listOf("a-volume", "z-volume"), source.externalVolumeNames())
    val page = source.queryPage("z-volume", PhotoAccessState.FULL, currentCursor, null, 10_000, limit = 5)

    assertEquals(MediaStorePhotoQueryMode.DATE_ADDED_CURSOR, backend.lastPlan!!.mode)
    assertTrue(backend.lastPlan!!.selection.contains(MediaStore.MediaColumns.IS_PENDING))
    assertTrue(backend.lastPlan!!.selection.contains(MediaStore.MediaColumns.RELATIVE_PATH))
    assertEquals("z-volume:17", page.photos.single().sourceId)
    assertEquals(20L, checkNotNull(page.nextCursor).dateAddedCursorSeconds)
    assertEquals(17L, checkNotNull(page.nextCursor).mediaStoreIdCursor)
  }

  @Test
  fun optInTimestampExcludesOlderPhotosAddedInTheSameSecond() {
    val backend = FakeBackend(rows = listOf(photoRow(17, relativePath = "DCIM/Camera/17.jpg")))
    val page =
      MediaStorePhotoSource(29, backend).queryPage(
        "external_primary",
        PhotoAccessState.FULL,
        null,
        null,
        collectionStartedAtMs = 20_500,
        limit = 5,
      )

    assertTrue(page.photos.isEmpty())
    assertEquals(20L, checkNotNull(page.nextCursor).dateAddedCursorSeconds)
    assertEquals(17L, checkNotNull(page.nextCursor).mediaStoreIdCursor)
  }

  @Test
  fun api30UsesGenerationWhenVersionMatchesAndResetsOnVersionChange() {
    val backend =
      FakeBackend(
        version = "v2",
        generationValue = 44,
        rows = listOf(photoRow(8, relativePath = "DCIM/8.jpg", generation = 42)),
      )
    val source = MediaStorePhotoSource(30, backend)
    val established = MediaStorePhotoCursor(mediaStoreVersion = "v2", generationCursor = 40)

    source.queryPage("external_primary", PhotoAccessState.FULL, established, null, 10_000, limit = 3)
    assertEquals(MediaStorePhotoQueryMode.GENERATION_CURSOR, backend.lastPlan!!.mode)
    assertTrue(backend.lastPlan!!.selection.contains(MediaStore.MediaColumns.GENERATION_ADDED))

    val changed = established.copy(mediaStoreVersion = "v1", generationCursor = 99)
    val reset = source.queryPage("external_primary", PhotoAccessState.FULL, changed, null, 10_000, limit = 1)
    assertEquals(MediaStorePhotoQueryMode.BASELINE_BY_DATE_ADDED, backend.lastPlan!!.mode)
    assertFalse(backend.lastPlan!!.selection.contains(MediaStore.MediaColumns.GENERATION_ADDED))
    assertTrue(backend.lastPlan!!.selection.contains(MediaStore.Images.Media.DATE_ADDED))
    assertEquals(null, checkNotNull(reset.nextCursor).mediaStoreVersion)
    assertEquals(20L, checkNotNull(reset.nextCursor).dateAddedCursorSeconds)

    source.queryPage("external_primary", PhotoAccessState.FULL, checkNotNull(reset.nextCursor), null, 10_000, limit = 1)
    assertEquals(MediaStorePhotoQueryMode.BASELINE_BY_DATE_ADDED, backend.lastPlan!!.mode)
    assertTrue(backend.lastPlan!!.selection.contains(MediaStore.Images.Media._ID))
  }

  @Test
  fun api30WithoutGenerationExtensionUsesVersionBoundDateIdCursor() {
    val backend = FakeBackend(version = "r-extension-0", generationSupported = false)
    val source = MediaStorePhotoSource(30, backend)

    val baseline = source.queryPage("external_primary", PhotoAccessState.FULL, null, null, 10_500)
    assertEquals(MediaStorePhotoQueryMode.BASELINE_BY_DATE_ADDED, backend.lastPlan!!.mode)
    assertEquals("r-extension-0", checkNotNull(baseline.nextCursor).mediaStoreVersion)
    assertEquals(9L, checkNotNull(baseline.nextCursor).dateAddedCursorSeconds)
    assertEquals(0L, checkNotNull(baseline.nextCursor).mediaStoreIdCursor)

    source.queryPage("external_primary", PhotoAccessState.FULL, checkNotNull(baseline.nextCursor), null, 10_500)
    assertEquals(MediaStorePhotoQueryMode.DATE_ADDED_CURSOR, backend.lastPlan!!.mode)
  }

  @Test
  fun api33StillUsesFullPermissionAndGenerationCursor() {
    val backend =
      FakeBackend(
        version = "v1",
        generationValue = 20,
        rows = listOf(photoRow(9, relativePath = "DCIM/9.jpg", generation = 18)),
      )
    val source = MediaStorePhotoSource(33, backend)
    val cursor = MediaStorePhotoCursor(mediaStoreVersion = "v1", generationCursor = 17)

    val page = source.queryPage("external_primary", PhotoAccessState.FULL, cursor, null, 10_000)

    assertEquals(MediaStorePhotoQueryMode.GENERATION_CURSOR, backend.lastPlan!!.mode)
    assertEquals(20L, checkNotNull(page.nextCursor).generationCursor)
    assertTrue(backend.lastPlan!!.selection.contains(MediaStore.MediaColumns.RELATIVE_PATH))
  }

  @Test
  fun api34And36PartialQueriesDoNotApplyDcimAndKeepTheFullCursorUntouched() {
    for (apiLevel in listOf(34, 36)) {
      val backend = FakeBackend(rows = listOf(photoRow(21, relativePath = "Pictures/selected.jpg")))
      val source = MediaStorePhotoSource(apiLevel, backend)
      val fullCursor = MediaStorePhotoCursor(mediaStoreVersion = "v1", generationCursor = 50)
      val pagePosition = MediaStoreSelectionCursor(dateAddedSeconds = 19, mediaStoreId = 20)

      val page =
        source.queryPage(
          "external_primary",
          PhotoAccessState.PARTIAL,
          fullCursor,
          pagePosition,
          0,
          limit = 1,
        )

      assertEquals("API $apiLevel", MediaStorePhotoQueryMode.PARTIAL_SELECTION, backend.lastPlan!!.mode)
      assertFalse("API $apiLevel", backend.lastPlan!!.filterDcim)
      assertFalse("API $apiLevel", backend.lastPlan!!.selection.contains(MediaStore.MediaColumns.RELATIVE_PATH))
      assertEquals(fullCursor, page.nextCursor)
      assertEquals(MediaStoreSelectionCursor(20, 21), page.nextSelectionCursor)
      assertTrue(page.hasMore)
    }
  }

  @Test
  fun fullAccessKeepsOnlyDcimAndPartialAccessCanSeeOtherFolders() {
    val outsideDcim = photoRow(22, relativePath = "Pictures/selected.jpg")
    val fullBackend = FakeBackend(rows = listOf(outsideDcim))
    val fullPage =
      MediaStorePhotoSource(29, fullBackend).queryPage(
        "external_primary",
        PhotoAccessState.FULL,
        null,
        null,
        0,
      )
    val partialPage =
      MediaStorePhotoSource(34, FakeBackend(rows = listOf(outsideDcim))).queryPage(
        "external_primary",
        PhotoAccessState.PARTIAL,
        null,
        null,
        0,
      )

    assertTrue(fullPage.photos.isEmpty())
    assertEquals("external_primary:22", partialPage.photos.single().sourceId)
  }

  @Test
  fun pendingTrashedAndNonImageRowsAreExcludedAndDateTakenFallsBack() {
    val backend =
      FakeBackend(
        rows =
          listOf(
            photoRow(1, relativePath = "DCIM/1.jpg", pending = true),
            photoRow(2, relativePath = "DCIM/2.jpg", trashed = true),
            photoRow(3, relativePath = "DCIM/3.jpg", mime = "video/mp4"),
            photoRow(4, relativePath = "DCIM/4.jpg", dateTakenMs = 0, dateAddedSeconds = 123),
          ),
      )
    val page =
      MediaStorePhotoSource(30, backend).queryPage(
        "external_primary",
        PhotoAccessState.FULL,
        null,
        null,
        0,
        limit = 10,
      )

    assertEquals(listOf("external_primary:4"), page.photos.map(MediaStorePhotoCandidate::sourceId))
    assertEquals(123_000L, page.photos.single().capturedAtMs)
  }

  @Test
  fun deniedOrRevokedAccessNeverAdvancesTheSuppliedCursors() {
    val cursor = MediaStorePhotoCursor(mediaStoreVersion = "v1", generationCursor = 8)
    val selectionCursor = MediaStoreSelectionCursor(100, 20)
    val deniedBackend = FakeBackend()
    val source = MediaStorePhotoSource(34, deniedBackend)

    val denied = source.queryPage("external_primary", PhotoAccessState.DENIED, cursor, selectionCursor, 0)
    assertEquals(MediaStorePhotoScanStatus.PHOTO_ACCESS_REQUIRED, denied.status)
    assertEquals(cursor, denied.nextCursor)
    assertEquals(selectionCursor, denied.nextSelectionCursor)
    assertNull(deniedBackend.lastPlan)

    val revokedBackend = FakeBackend(throwSecurityException = true)
    val revoked =
      MediaStorePhotoSource(34, revokedBackend).queryPage(
        "external_primary",
        PhotoAccessState.FULL,
        cursor,
        null,
        0,
      )
    assertEquals(MediaStorePhotoScanStatus.ACCESS_REVOKED, revoked.status)
    assertEquals(cursor, revoked.nextCursor)
    assertTrue(revoked.photos.isEmpty())
  }

  @Test
  fun sourceKeyIsStableForTheSameVolumeAndMediaStoreId() {
    val row = photoRow(42, relativePath = "DCIM/42.jpg")
    val backend = FakeBackend(rows = listOf(row))
    val source = MediaStorePhotoSource(29, backend)

    val first = source.queryPage("volume-a", PhotoAccessState.FULL, null, null, 0)
    val second = source.queryPage("volume-a", PhotoAccessState.FULL, null, null, 0)

    assertEquals(first.photos.single().sourceId, second.photos.single().sourceId)
    assertNotEquals("volume-b:42", first.photos.single().sourceId)
  }

  private class FakeBackend(
    private val volumes: List<String> = listOf("external_primary"),
    private val version: String? = null,
    private val versions: Map<String, String> = emptyMap(),
    private val generationValue: Long? = null,
    private val generationSupported: Boolean = true,
    private val rows: List<MediaStorePhotoRow> = emptyList(),
    private val throwSecurityException: Boolean = false,
  ) : MediaStorePhotoBackend {
    var lastPlan: MediaStorePhotoQueryPlan? = null
      private set

    override fun supportsGeneration(): Boolean = generationSupported

    override fun externalVolumeNames(): List<String> = volumes

    override fun version(volumeName: String): String? = versions[volumeName] ?: version

    override fun generation(volumeName: String): Long? = generationValue

    override fun query(
      volumeName: String,
      plan: MediaStorePhotoQueryPlan,
    ): List<MediaStorePhotoRow> {
      lastPlan = plan
      if (throwSecurityException) throw SecurityException("permission was revoked")
      return rows.take(plan.limit)
    }
  }

  private companion object {
    fun photoRow(
      id: Long,
      relativePath: String? = null,
      legacyPath: String? = null,
      dateTakenMs: Long? = 1_000,
      dateAddedSeconds: Long? = 20,
      mime: String? = "image/jpeg",
      generation: Long? = 4,
      pending: Boolean = false,
      trashed: Boolean = false,
    ) = MediaStorePhotoRow(
      mediaStoreId = id,
      displayName = "$id.jpg",
      dateTakenMs = dateTakenMs,
      dateAddedSeconds = dateAddedSeconds,
      mimeType = mime,
      width = 100,
      height = 80,
      relativePath = relativePath,
      legacyDataPath = legacyPath,
      generationAdded = generation,
      isPending = pending,
      isTrashed = trashed,
    )
  }
}
