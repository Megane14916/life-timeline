package com.megane14916.lifetimeline.collector

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.provider.MediaStore

data class MediaStorePhotoCursor(
  val mediaStoreVersion: String? = null,
  val generationCursor: Long? = null,
  val dateAddedCursorSeconds: Long? = null,
  val mediaStoreIdCursor: Long? = null,
)

data class MediaStoreSelectionCursor(
  val dateAddedSeconds: Long,
  val mediaStoreId: Long,
)

enum class MediaStorePhotoQueryMode {
  BASELINE_BY_DATE_ADDED,
  DATE_ADDED_CURSOR,
  GENERATION_CURSOR,
  PARTIAL_SELECTION,
}

data class MediaStorePhotoQueryPlan(
  val mode: MediaStorePhotoQueryMode,
  val selection: String,
  val selectionArgs: List<String>,
  val sortOrder: String,
  val limit: Int,
  val filterDcim: Boolean,
  val useLegacyDataPath: Boolean,
)

object MediaStorePhotoQueryPolicy {
  const val MAX_PAGE_SIZE = 200

  fun create(
    apiLevel: Int,
    access: PhotoAccessState,
    cursor: MediaStorePhotoCursor?,
    selectionCursor: MediaStoreSelectionCursor?,
    collectionStartedAtMs: Long,
    mediaStoreVersion: String?,
    generationSupported: Boolean = apiLevel >= Build.VERSION_CODES.R,
    limit: Int = MAX_PAGE_SIZE,
  ): MediaStorePhotoQueryPlan {
    require(apiLevel >= 26) { "Photo collection requires Android API 26 or later." }
    require(collectionStartedAtMs >= 0) { "Collection start time must be non-negative." }
    require(limit in 1..MAX_PAGE_SIZE) { "Photo query limit must be between 1 and 200." }

    val mode =
      when (access) {
        PhotoAccessState.DENIED -> {
          error("Denied photo access cannot create a MediaStore query.")
        }

        PhotoAccessState.PARTIAL -> {
          MediaStorePhotoQueryMode.PARTIAL_SELECTION
        }

        PhotoAccessState.FULL -> {
          when {
            generationSupported &&
              mediaStoreVersion != null &&
              cursor != null &&
              cursor.mediaStoreVersion == mediaStoreVersion &&
              cursor.generationCursor != null -> {
              MediaStorePhotoQueryMode.GENERATION_CURSOR
            }

            generationSupported -> {
              MediaStorePhotoQueryMode.BASELINE_BY_DATE_ADDED
            }

            apiLevel >= Build.VERSION_CODES.R &&
              mediaStoreVersion != null &&
              cursor != null &&
              cursor.mediaStoreVersion == mediaStoreVersion &&
              cursor.dateAddedCursorSeconds != null &&
              cursor.mediaStoreIdCursor != null -> {
              MediaStorePhotoQueryMode.DATE_ADDED_CURSOR
            }

            apiLevel >= Build.VERSION_CODES.R -> {
              MediaStorePhotoQueryMode.BASELINE_BY_DATE_ADDED
            }

            cursor != null &&
              cursor.dateAddedCursorSeconds != null &&
              cursor.mediaStoreIdCursor != null -> {
              MediaStorePhotoQueryMode.DATE_ADDED_CURSOR
            }

            else -> {
              MediaStorePhotoQueryMode.BASELINE_BY_DATE_ADDED
            }
          }
        }
      }

    val selectionParts = mutableListOf("${MediaStore.Images.Media.MIME_TYPE} LIKE ?")
    val args = mutableListOf("image/%")
    if (apiLevel >= Build.VERSION_CODES.Q) {
      selectionParts += "(${MediaStore.MediaColumns.IS_PENDING} = 0 OR ${MediaStore.MediaColumns.IS_PENDING} IS NULL)"
    }
    if (apiLevel >= Build.VERSION_CODES.R) {
      selectionParts += "(${MediaStore.MediaColumns.IS_TRASHED} = 0 OR ${MediaStore.MediaColumns.IS_TRASHED} IS NULL)"
    }

    val filterDcim = access == PhotoAccessState.FULL
    if (filterDcim && apiLevel >= Build.VERSION_CODES.Q) {
      selectionParts +=
        "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?)"
      args += "DCIM/"
      args += "DCIM/%"
    }

    val sortOrder: String
    val resumeBaseline =
      mode == MediaStorePhotoQueryMode.BASELINE_BY_DATE_ADDED &&
        apiLevel >= Build.VERSION_CODES.R &&
        cursor != null &&
        cursor.mediaStoreVersion == null &&
        cursor.dateAddedCursorSeconds != null &&
        cursor.mediaStoreIdCursor != null

    when (mode) {
      MediaStorePhotoQueryMode.GENERATION_CURSOR -> {
        selectionParts += "${MediaStore.MediaColumns.GENERATION_ADDED} > ?"
        args += checkNotNull(checkNotNull(cursor).generationCursor).toString()
        sortOrder = "${MediaStore.MediaColumns.GENERATION_ADDED} ASC, ${MediaStore.Images.Media._ID} ASC"
      }

      MediaStorePhotoQueryMode.DATE_ADDED_CURSOR -> {
        val currentCursor = checkNotNull(cursor)
        val dateAdded = checkNotNull(currentCursor.dateAddedCursorSeconds)
        val mediaId = checkNotNull(currentCursor.mediaStoreIdCursor)
        selectionParts +=
          "(${MediaStore.Images.Media.DATE_ADDED} > ? OR " +
          "(${MediaStore.Images.Media.DATE_ADDED} = ? AND ${MediaStore.Images.Media._ID} > ?))"
        args += dateAdded.toString()
        args += dateAdded.toString()
        args += mediaId.toString()
        sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC, ${MediaStore.Images.Media._ID} ASC"
      }

      MediaStorePhotoQueryMode.BASELINE_BY_DATE_ADDED -> {
        selectionParts += "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        args += (collectionStartedAtMs / 1_000).toString()
        if (resumeBaseline) {
          val currentCursor = checkNotNull(cursor)
          val dateAdded = checkNotNull(currentCursor.dateAddedCursorSeconds)
          val mediaId = checkNotNull(currentCursor.mediaStoreIdCursor)
          selectionParts +=
            "(${MediaStore.Images.Media.DATE_ADDED} > ? OR " +
            "(${MediaStore.Images.Media.DATE_ADDED} = ? AND ${MediaStore.Images.Media._ID} > ?))"
          args += dateAdded.toString()
          args += dateAdded.toString()
          args += mediaId.toString()
        }
        sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC, ${MediaStore.Images.Media._ID} ASC"
      }

      MediaStorePhotoQueryMode.PARTIAL_SELECTION -> {
        if (selectionCursor != null) {
          selectionParts +=
            "(${MediaStore.Images.Media.DATE_ADDED} > ? OR " +
            "(${MediaStore.Images.Media.DATE_ADDED} = ? AND ${MediaStore.Images.Media._ID} > ?))"
          args += selectionCursor.dateAddedSeconds.toString()
          args += selectionCursor.dateAddedSeconds.toString()
          args += selectionCursor.mediaStoreId.toString()
        }
        sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC, ${MediaStore.Images.Media._ID} ASC"
      }
    }

    return MediaStorePhotoQueryPlan(
      mode = mode,
      selection = selectionParts.joinToString(" AND "),
      selectionArgs = args,
      sortOrder = sortOrder,
      limit = limit,
      filterDcim = filterDcim,
      useLegacyDataPath = filterDcim && apiLevel < Build.VERSION_CODES.Q,
    )
  }
}

data class MediaStorePhotoCandidate(
  val volumeName: String,
  val mediaStoreId: Long,
  val sourceId: String,
  val filename: String,
  val capturedAtMs: Long,
  val capturedAtSource: String,
  val mimeType: String,
  val width: Int?,
  val height: Int?,
)

data class MediaStorePhotoRow(
  val mediaStoreId: Long,
  val displayName: String?,
  val dateTakenMs: Long?,
  val dateAddedSeconds: Long?,
  val mimeType: String?,
  val width: Int?,
  val height: Int?,
  val relativePath: String?,
  val legacyDataPath: String?,
  val generationAdded: Long?,
  val isPending: Boolean,
  val isTrashed: Boolean,
)

interface MediaStorePhotoBackend {
  fun supportsGeneration(): Boolean = true

  fun externalVolumeNames(): List<String>

  fun version(volumeName: String): String?

  fun generation(volumeName: String): Long?

  fun query(
    volumeName: String,
    plan: MediaStorePhotoQueryPlan,
  ): List<MediaStorePhotoRow>
}

enum class MediaStorePhotoScanStatus {
  SUCCESS,
  PHOTO_ACCESS_REQUIRED,
  ACCESS_REVOKED,
}

data class MediaStorePhotoPage(
  val status: MediaStorePhotoScanStatus,
  val photos: List<MediaStorePhotoCandidate>,
  val nextCursor: MediaStorePhotoCursor?,
  val nextSelectionCursor: MediaStoreSelectionCursor?,
  val hasMore: Boolean,
)

/** Queries one bounded page from a mounted volume and returns proposed cursor state. */
class MediaStorePhotoSource(
  private val apiLevel: Int,
  private val backend: MediaStorePhotoBackend,
) {
  fun externalVolumeNames(): List<String> =
    if (apiLevel >= Build.VERSION_CODES.Q) backend.externalVolumeNames().distinct().sorted() else listOf("external")

  fun queryPage(
    volumeName: String,
    access: PhotoAccessState,
    cursor: MediaStorePhotoCursor?,
    selectionCursor: MediaStoreSelectionCursor?,
    collectionStartedAtMs: Long,
    limit: Int = MediaStorePhotoQueryPolicy.MAX_PAGE_SIZE,
  ): MediaStorePhotoPage {
    if (access == PhotoAccessState.DENIED) {
      return MediaStorePhotoPage(
        status = MediaStorePhotoScanStatus.PHOTO_ACCESS_REQUIRED,
        photos = emptyList(),
        nextCursor = cursor,
        nextSelectionCursor = selectionCursor,
        hasMore = false,
      )
    }

    val generationSupported = apiLevel >= Build.VERSION_CODES.R && backend.supportsGeneration()
    val version: String?
    val generation: Long?
    val plan: MediaStorePhotoQueryPlan
    val rows: List<MediaStorePhotoRow>
    try {
      version = if (apiLevel >= Build.VERSION_CODES.Q) backend.version(volumeName) else null
      generation = if (generationSupported) backend.generation(volumeName) else null
      plan =
        MediaStorePhotoQueryPolicy.create(
          apiLevel = apiLevel,
          access = access,
          cursor = cursor,
          selectionCursor = selectionCursor,
          collectionStartedAtMs = collectionStartedAtMs,
          mediaStoreVersion = version,
          generationSupported = generationSupported,
          limit = limit,
        )
      rows =
        backend.query(volumeName, plan)
    } catch (_: SecurityException) {
      return MediaStorePhotoPage(
        status = MediaStorePhotoScanStatus.ACCESS_REVOKED,
        photos = emptyList(),
        nextCursor = cursor,
        nextSelectionCursor = selectionCursor,
        hasMore = false,
      )
    }

    val photos = rows.mapNotNull { row -> row.toCandidate(volumeName, plan, collectionStartedAtMs) }
    val lastRow = rows.lastOrNull()
    val hasMore = rows.size >= limit
    val nextCursor =
      when (plan.mode) {
        MediaStorePhotoQueryMode.PARTIAL_SELECTION -> {
          cursor
        }

        MediaStorePhotoQueryMode.GENERATION_CURSOR -> {
          checkNotNull(cursor).copy(
            generationCursor =
              if (hasMore) {
                lastRow?.generationAdded ?: cursor.generationCursor
              } else {
                generation ?: cursor.generationCursor
              },
          )
        }

        MediaStorePhotoQueryMode.BASELINE_BY_DATE_ADDED -> {
          if (apiLevel < Build.VERSION_CODES.R) {
            lastRow?.let { row ->
              MediaStorePhotoCursor(
                dateAddedCursorSeconds = row.dateAddedSeconds,
                mediaStoreIdCursor = row.mediaStoreId,
              )
            } ?: MediaStorePhotoCursor(
              dateAddedCursorSeconds = collectionStartedAtMs / 1_000 - 1,
              mediaStoreIdCursor = 0,
            )
          } else if (!hasMore && generationSupported) {
            MediaStorePhotoCursor(mediaStoreVersion = version, generationCursor = generation)
          } else if (!hasMore) {
            MediaStorePhotoCursor(
              mediaStoreVersion = version,
              dateAddedCursorSeconds = lastRow?.dateAddedSeconds ?: (collectionStartedAtMs / 1_000 - 1),
              mediaStoreIdCursor = lastRow?.mediaStoreId ?: 0,
            )
          } else {
            MediaStorePhotoCursor(
              mediaStoreVersion = null,
              dateAddedCursorSeconds = lastRow?.dateAddedSeconds,
              mediaStoreIdCursor = lastRow?.mediaStoreId,
            )
          }
        }

        MediaStorePhotoQueryMode.DATE_ADDED_CURSOR -> {
          val currentCursor = checkNotNull(cursor)
          currentCursor.copy(
            dateAddedCursorSeconds = lastRow?.dateAddedSeconds ?: currentCursor.dateAddedCursorSeconds,
            mediaStoreIdCursor = lastRow?.mediaStoreId ?: currentCursor.mediaStoreIdCursor,
          )
        }
      }
    val nextSelectionCursor =
      if (plan.mode != MediaStorePhotoQueryMode.PARTIAL_SELECTION || !hasMore) {
        null
      } else {
        lastRow?.let { MediaStoreSelectionCursor(it.dateAddedSeconds ?: 0, it.mediaStoreId) }
      }

    return MediaStorePhotoPage(
      status = MediaStorePhotoScanStatus.SUCCESS,
      photos = photos,
      nextCursor = nextCursor,
      nextSelectionCursor = nextSelectionCursor,
      hasMore = hasMore,
    )
  }

  private fun MediaStorePhotoRow.toCandidate(
    volumeName: String,
    plan: MediaStorePhotoQueryPlan,
    collectionStartedAtMs: Long,
  ): MediaStorePhotoCandidate? {
    if (mediaStoreId <= 0 || isPending || isTrashed) return null
    if (
      dateAddedSeconds != null &&
      dateAddedSeconds <= Long.MAX_VALUE / 1_000 &&
      dateAddedSeconds * 1_000 < collectionStartedAtMs
    ) {
      return null
    }
    val mime = mimeType?.takeIf { it.startsWith("image/", ignoreCase = true) } ?: return null
    val filename = displayName?.takeIf(String::isNotBlank) ?: return null
    if (plan.filterDcim) {
      val path = if (plan.useLegacyDataPath) legacyDataPath else relativePath
      if (!isDcimPath(path, plan.useLegacyDataPath)) return null
    }
    val capturedAtMs =
      dateTakenMs
        ?.takeIf { it > 0 }
        ?: dateAddedSeconds
          ?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1_000 }
          ?.times(1_000)
        ?: return null
    return MediaStorePhotoCandidate(
      volumeName = volumeName,
      mediaStoreId = mediaStoreId,
      sourceId = "$volumeName:$mediaStoreId",
      filename = filename,
      capturedAtMs = capturedAtMs,
      capturedAtSource = if (dateTakenMs != null && dateTakenMs > 0) "date_taken" else "date_added_fallback",
      mimeType = mime,
      width = width?.takeIf { it > 0 },
      height = height?.takeIf { it > 0 },
    )
  }

  private fun isDcimPath(
    path: String?,
    absolutePath: Boolean,
  ): Boolean {
    if (path.isNullOrBlank()) return false
    val normalized = path.replace('\\', '/').trimEnd('/')
    if (!absolutePath) return normalized.equals("DCIM", ignoreCase = true) || normalized.startsWith("DCIM/", true)
    return normalized
      .split('/')
      .windowed(size = 2)
      .any { (parent, _) -> parent.equals("DCIM", ignoreCase = true) }
  }
}

/** Android framework adapter kept behind [MediaStorePhotoBackend] for API-policy tests. */
class AndroidMediaStorePhotoBackend(
  private val context: Context,
  private val apiLevel: Int = Build.VERSION.SDK_INT,
  private val contentResolver: ContentResolver = context.contentResolver,
) : MediaStorePhotoBackend {
  @SuppressLint("NewApi")
  override fun supportsGeneration(): Boolean =
    when {
      apiLevel > Build.VERSION_CODES.R -> true
      apiLevel < Build.VERSION_CODES.R -> false
      else -> SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 1
    }

  @SuppressLint("NewApi")
  override fun externalVolumeNames(): List<String> =
    if (apiLevel >= Build.VERSION_CODES.Q) {
      MediaStore.getExternalVolumeNames(context).toList()
    } else {
      listOf("external")
    }

  @SuppressLint("NewApi")
  override fun version(volumeName: String): String? =
    if (apiLevel >= Build.VERSION_CODES.Q) MediaStore.getVersion(context, volumeName) else null

  @SuppressLint("NewApi")
  override fun generation(volumeName: String): Long? = if (supportsGeneration()) MediaStore.getGeneration(context, volumeName) else null

  @Suppress("DEPRECATION")
  override fun query(
    volumeName: String,
    plan: MediaStorePhotoQueryPlan,
  ): List<MediaStorePhotoRow> {
    val uri =
      if (apiLevel >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(volumeName)
      } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
      }
    val projection =
      buildList {
        add(MediaStore.Images.Media._ID)
        add(MediaStore.Images.Media.DISPLAY_NAME)
        add(MediaStore.Images.Media.DATE_TAKEN)
        add(MediaStore.Images.Media.DATE_ADDED)
        add(MediaStore.Images.Media.MIME_TYPE)
        add(MediaStore.Images.Media.WIDTH)
        add(MediaStore.Images.Media.HEIGHT)
        if (apiLevel >= Build.VERSION_CODES.Q) add(MediaStore.MediaColumns.RELATIVE_PATH)
        if (apiLevel < Build.VERSION_CODES.Q) add(MediaStore.Images.Media.DATA)
        if (apiLevel >= Build.VERSION_CODES.Q) add(MediaStore.MediaColumns.IS_PENDING)
        if (apiLevel >= Build.VERSION_CODES.R) {
          add(MediaStore.MediaColumns.IS_TRASHED)
          add(MediaStore.MediaColumns.GENERATION_ADDED)
        }
      }
    val queryArgs =
      Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, plan.selection)
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, plan.selectionArgs.toTypedArray())
        putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, plan.sortOrder)
        putInt(ContentResolver.QUERY_ARG_LIMIT, plan.limit)
      }
    val rows = mutableListOf<MediaStorePhotoRow>()
    contentResolver.query(uri, projection.toTypedArray(), queryArgs, null)?.use { cursor ->
      val indexes = MediaStorePhotoColumnIndexes(cursor, apiLevel)
      while (rows.size < plan.limit && cursor.moveToNext()) {
        try {
          rows += cursor.toPhotoRow(indexes)
        } catch (error: RuntimeException) {
          if (error is SecurityException) throw error
          // A broken provider row must not prevent later rows from being discovered.
        }
      }
    }
    return rows
  }

  private data class MediaStorePhotoColumnIndexes(
    val id: Int,
    val displayName: Int,
    val dateTaken: Int,
    val dateAdded: Int,
    val mimeType: Int,
    val width: Int,
    val height: Int,
    val relativePath: Int?,
    val legacyDataPath: Int?,
    val pending: Int?,
    val trashed: Int?,
    val generation: Int?,
  ) {
    constructor(cursor: Cursor, apiLevel: Int) :
      this(
        id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID),
        displayName = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME),
        dateTaken = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN),
        dateAdded = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED),
        mimeType = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE),
        width = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH),
        height = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT),
        relativePath = optionalColumnIndex(cursor, apiLevel >= Build.VERSION_CODES.Q, MediaStore.MediaColumns.RELATIVE_PATH),
        legacyDataPath = optionalColumnIndex(cursor, apiLevel < Build.VERSION_CODES.Q, MediaStore.Images.Media.DATA),
        pending = optionalColumnIndex(cursor, apiLevel >= Build.VERSION_CODES.Q, MediaStore.MediaColumns.IS_PENDING),
        trashed = optionalColumnIndex(cursor, apiLevel >= Build.VERSION_CODES.R, MediaStore.MediaColumns.IS_TRASHED),
        generation = optionalColumnIndex(cursor, apiLevel >= Build.VERSION_CODES.R, MediaStore.MediaColumns.GENERATION_ADDED),
      )
  }

  private fun Cursor.toPhotoRow(indexes: MediaStorePhotoColumnIndexes): MediaStorePhotoRow =
    MediaStorePhotoRow(
      mediaStoreId = getLong(indexes.id),
      displayName = getString(indexes.displayName),
      dateTakenMs = nullableLong(indexes.dateTaken),
      dateAddedSeconds = nullableLong(indexes.dateAdded),
      mimeType = getString(indexes.mimeType),
      width = nullableInt(indexes.width),
      height = nullableInt(indexes.height),
      relativePath = indexes.relativePath?.let { getString(it) },
      legacyDataPath = indexes.legacyDataPath?.let { getString(it) },
      generationAdded = indexes.generation?.let { getLong(it) },
      isPending = indexes.pending?.let { getInt(it) != 0 } ?: false,
      isTrashed = indexes.trashed?.let { getInt(it) != 0 } ?: false,
    )

  private fun Cursor.nullableLong(columnIndex: Int): Long? = if (isNull(columnIndex)) null else getLong(columnIndex)

  private fun Cursor.nullableInt(columnIndex: Int): Int? = if (isNull(columnIndex)) null else getInt(columnIndex)
}

private fun optionalColumnIndex(
  cursor: Cursor,
  available: Boolean,
  name: String,
): Int? = if (available) cursor.getColumnIndexOrThrow(name) else null
