import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test } from '@playwright/test'

const seededDate = '2026-09-03'
const timezone = 'Asia/Tokyo'
const syncedDate = '2026-09-05'
const syncedDeviceId = '01K4N6Q2N6N8YJ7W4M2D3A9B5C'
const syncedAppId = '01K4N6R7KQJ8J2W9VQW4B6M0TN'
const syncedSessionId = '01K4N70E3Q6N9D6E6G0C8M2H1P'
const syncedStartedAtMs = Date.UTC(2026, 8, 5, 1, 0)
const photoId = '01K4N70E3Q6N9D6E6G0C8M2H1Q'
const photoCapturedAtMs = Date.UTC(2026, 8, 5, 23, 30)
const repositoryDirectory = resolve(
  dirname(fileURLToPath(import.meta.url)),
  '../..',
)
const syntheticThumbnail = readFileSync(
  new URL(
    '../../contracts/sync/fixtures/synthetic-thumbnail.webp',
    import.meta.url,
  ),
)
const photoPayload = {
  schemaVersion: 1,
  device: {
    id: syncedDeviceId,
    name: 'P2 E2E Android',
    platform: 'android',
  },
  photos: [
    {
      id: photoId,
      source: 'android_media_store',
      sourceId: 'fixture-volume:photo-e2e',
      filename: 'synthetic-fixture.jpg',
      capturedAtMs: photoCapturedAtMs,
      width: 3,
      height: 2,
      mimeType: 'image/jpeg',
      latitude: null,
      longitude: null,
      thumbnail: {
        mimeType: 'image/webp',
        width: 3,
        height: 2,
        byteSize: syntheticThumbnail.byteLength,
        sha256: createHash('sha256').update(syntheticThumbnail).digest('hex'),
      },
    },
  ],
}
const pythonSqliteSnapshot = [
  'from pathlib import Path',
  'import json, sqlite3, sys',
  'data_dir = Path(sys.argv[1])',
  'photo_id = sys.argv[2]',
  "connection = sqlite3.connect((data_dir / 'lifelog.db').as_uri() + '?mode=ro', uri=True)",
  'try:',
  '    row = connection.execute(',
  '        "SELECT COUNT(*), COALESCE(SUM(CASE WHEN thumbnail_path IS NOT NULL THEN 1 ELSE 0 END), 0) FROM media_items WHERE id = ? AND type = \'photo\'",',
  '        (photo_id,),',
  '    ).fetchone()',
  'finally:',
  '    connection.close()',
  "files = [path for path in (data_dir / 'thumbnails').rglob('*.webp') if path.is_file()]",
  "print(json.dumps({'rows': row[0], 'thumbnail_refs': row[1], 'files': len(files), 'bytes': sum(path.stat().st_size for path in files)}))",
].join('\n')

function timelineUrl(date = seededDate, selectedTimezone = timezone) {
  return `/timeline?date=${date}&timezone=${encodeURIComponent(selectedTimezone)}`
}

function photoStorageSnapshot(currentPhotoId: string = photoId) {
  const dataDirectory = process.env.LIFE_TIMELINE_E2E_DATA_DIR
  if (!dataDirectory) throw new Error('E2E database path is not configured.')

  const virtualEnvironmentPython = join(
    repositoryDirectory,
    'backend',
    '.venv',
    process.platform === 'win32' ? 'Scripts/python.exe' : 'bin/python',
  )
  const python =
    process.env.E2E_PYTHON ??
    (existsSync(virtualEnvironmentPython)
      ? virtualEnvironmentPython
      : process.platform === 'win32'
        ? 'python'
        : 'python3')
  const result = execFileSync(
    python,
    ['-c', pythonSqliteSnapshot, dataDirectory, currentPhotoId],
    { encoding: 'utf8' },
  )
  return JSON.parse(result) as {
    rows: number
    thumbnail_refs: number
    files: number
    bytes: number
  }
}

function syncPayload(durationMs = 120_000) {
  return {
    schemaVersion: 1,
    device: {
      id: syncedDeviceId,
      name: 'P2 E2E Android',
      platform: 'android',
    },
    apps: [
      {
        id: syncedAppId,
        identifier: 'com.example.p2-e2e',
        displayName: 'P2 E2E Browser',
      },
    ],
    sessions: [
      {
        id: syncedSessionId,
        appId: syncedAppId,
        startedAtMs: syncedStartedAtMs,
        endedAtMs: syncedStartedAtMs + durationMs,
        durationMs,
        source: 'android_usage_stats',
      },
    ],
  }
}

test.describe('PC core real database flow', () => {
  test('renders consistent Timeline and Dashboard data from the seeded SQLite database', async ({
    page,
  }) => {
    await page.goto(timelineUrl())

    await expect(page.getByTestId('dashboard-usage')).toHaveText('1時間5分')
    await expect(page.getByTestId('dashboard-session-count')).toHaveText('4件')
    await expect(page.getByTestId('dashboard-app-count')).toHaveText('2件')
    await expect(page.getByTestId('timeline-list')).toBeVisible()
    await expect(page.getByTestId('timeline-item')).toHaveCount(4)
    await expect(page.getByTestId('timeline-item-duration')).toHaveText([
      '10分',
      '20分',
      '30分',
      '5分',
    ])
    await expect(page.getByText('前日から継続')).toBeVisible()
    await expect(page.getByTestId('dashboard-app-usage')).toHaveText([
      '35分',
      '30分',
    ])
  })

  test('switches dates, shows the empty day, and restores the selection after reload', async ({
    page,
  }) => {
    await page.goto(timelineUrl())
    await expect(page.getByTestId('dashboard-usage')).toHaveText('1時間5分')

    await page.getByRole('button', { name: '翌日を表示' }).click()
    await expect(page.getByTestId('date-input')).toHaveValue('2026-09-04')
    await expect(page.getByTestId('dashboard-usage')).toHaveText('1分30秒')
    await expect(page.getByTestId('timeline-item')).toHaveCount(3)

    await page.getByTestId('date-input').fill('2026-09-05')
    await expect(page.getByTestId('dashboard-usage')).toHaveText('0分')
    await expect(page.getByTestId('dashboard-session-count')).toHaveText('0件')
    await expect(page.getByTestId('dashboard-app-count')).toHaveText('0件')
    await expect(page.getByText('この日の記録はありません')).toBeVisible()
    await expect(page.getByTestId('timeline-list')).toHaveCount(0)

    await page.getByTestId('date-input').fill(seededDate)
    await expect(page.getByTestId('dashboard-usage')).toHaveText('1時間5分')
    await page.reload()
    await expect(page.getByTestId('date-input')).toHaveValue(seededDate)
    await expect(page.getByTestId('timeline-item')).toHaveCount(4)
  })

  test('recovers a failed Dashboard request through the real API retry', async ({
    page,
  }) => {
    let failedRequestCount = 0
    await page.route(/\/api\/v1\/stats\/apps(?:\?|$)/, async (route) => {
      if (failedRequestCount < 2) {
        failedRequestCount += 1
        await route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({
            error: {
              code: 'temporary_failure',
              message: 'temporary test failure',
            },
          }),
        })
        return
      }
      await route.continue()
    })

    await page.goto(timelineUrl())
    await expect(page.getByTestId('dashboard-error')).toBeVisible()
    await expect(page.getByTestId('timeline-item')).toHaveCount(4)

    await page
      .getByTestId('dashboard-error')
      .getByRole('button', { name: '再試行' })
      .click()
    await expect(page.getByTestId('dashboard-usage')).toHaveText('1時間5分')
    await expect(page.getByTestId('dashboard-error')).toHaveCount(0)
  })

  test('syncs an Android fixture through the real API and renders the replay safely', async ({
    page,
  }) => {
    const first = await page.request.post('/api/v1/sync/app-sessions', {
      data: syncPayload(),
    })
    expect(first.status()).toBe(200)
    expect(await first.json()).toEqual({
      schemaVersion: 1,
      accepted: [syncedSessionId],
    })

    const replay = await page.request.post('/api/v1/sync/app-sessions', {
      data: syncPayload(),
    })
    expect(replay.status()).toBe(200)
    expect(await replay.json()).toEqual({
      schemaVersion: 1,
      accepted: [syncedSessionId],
    })

    const conflict = await page.request.post('/api/v1/sync/app-sessions', {
      data: syncPayload(180_000),
    })
    expect(conflict.status()).toBe(409)
    await expect(conflict.json()).resolves.toMatchObject({
      error: { code: 'sync_conflict' },
    })

    await page.goto(timelineUrl(syncedDate))
    await expect(page.getByTestId('dashboard-usage')).toHaveText('2分')
    await expect(page.getByTestId('dashboard-session-count')).toHaveText('1件')
    await expect(page.getByTestId('dashboard-app-count')).toHaveText('1件')
    await expect(page.getByTestId('timeline-item')).toHaveCount(1)
    await expect(
      page.getByRole('heading', { name: 'P2 E2E Browser' }),
    ).toBeVisible()
    await expect(page.getByTestId('timeline-item-duration')).toHaveText('2分')
  })

  test('syncs a synthetic photo idempotently through SQLite, thumbnail storage, and the UI', async ({
    page,
  }, testInfo) => {
    const currentPhotoId =
      testInfo.retry === 0 ? photoId : `${photoId.slice(0, -1)}R`
    const currentCapturedAtMs =
      photoCapturedAtMs + testInfo.retry * 24 * 60 * 60 * 1_000
    const currentPhotoTokyoDate = new Date(
      currentCapturedAtMs + 9 * 60 * 60 * 1_000,
    )
      .toISOString()
      .slice(0, 10)
    const currentPhotoUtcDate = new Date(currentCapturedAtMs)
      .toISOString()
      .slice(0, 10)
    const nextPhotoUtcDate = new Date(
      currentCapturedAtMs + 24 * 60 * 60 * 1_000,
    )
      .toISOString()
      .slice(0, 10)
    const currentPhotoPayload = {
      ...photoPayload,
      photos: photoPayload.photos.map((photo) => ({
        ...photo,
        id: currentPhotoId,
        sourceId: `fixture-volume:photo-e2e-${testInfo.retry}`,
        capturedAtMs: currentCapturedAtMs,
      })),
    }
    await page.goto(timelineUrl(currentPhotoTokyoDate))
    const dashboardStats = [
      await page.getByTestId('dashboard-usage').innerText(),
      await page.getByTestId('dashboard-session-count').innerText(),
      await page.getByTestId('dashboard-app-count').innerText(),
    ]
    await expect(page.getByText('この日の写真はありません。')).toBeVisible()

    const postPhoto = () => {
      const boundary = 'life-timeline-synthetic-photo-e2e'
      const body = Buffer.concat([
        Buffer.from(
          `--${boundary}\r\nContent-Disposition: form-data; name="metadata"\r\nContent-Type: application/json\r\n\r\n${JSON.stringify(currentPhotoPayload)}\r\n`,
        ),
        Buffer.from(
          `--${boundary}\r\nContent-Disposition: form-data; name="thumbnail_${currentPhotoId}"; filename="thumbnail.webp"\r\nContent-Type: image/webp\r\n\r\n`,
        ),
        syntheticThumbnail,
        Buffer.from(`\r\n--${boundary}--\r\n`),
      ])
      return page.request.post('/api/v1/sync/photos', {
        data: body,
        headers: {
          'Content-Type': `multipart/form-data; boundary=${boundary}`,
        },
      })
    }

    const firstUpload = await postPhoto()
    expect(firstUpload.status()).toBe(200)
    await expect(firstUpload.json()).resolves.toEqual({
      schemaVersion: 1,
      accepted: [currentPhotoId],
    })
    const afterFirstUpload = photoStorageSnapshot(currentPhotoId)
    expect(afterFirstUpload).toMatchObject({
      rows: 1,
      thumbnail_refs: 1,
      files: 1,
      bytes: syntheticThumbnail.byteLength,
    })

    await page.reload()
    await expect(page.getByTestId('timeline-photo-item')).toHaveCount(1)
    await expect(page.getByTestId('photo-grid-card')).toHaveCount(1)
    await expect(page.getByTestId('dashboard-usage')).toHaveText(
      dashboardStats[0],
    )
    await expect(page.getByTestId('dashboard-session-count')).toHaveText(
      dashboardStats[1],
    )
    await expect(page.getByTestId('dashboard-app-count')).toHaveText(
      dashboardStats[2],
    )

    const thumbnailResponse = await page.request.get(
      `/api/v1/media/${currentPhotoId}/thumbnail`,
    )
    expect(thumbnailResponse.status()).toBe(200)
    expect(thumbnailResponse.headers()['content-type']).toContain('image/webp')
    await expect(
      page
        .getByTestId('photo-timeline-card')
        .getByRole('img', { name: 'synthetic-fixture.jpgの写真サムネイル' }),
    ).toHaveJSProperty('naturalWidth', 3)

    const replay = await postPhoto()
    expect(replay.status()).toBe(200)
    await expect(replay.json()).resolves.toEqual({
      schemaVersion: 1,
      accepted: [currentPhotoId],
    })
    await page.reload()
    await expect(page.getByTestId('timeline-photo-item')).toHaveCount(1)
    await expect(page.getByTestId('photo-grid-card')).toHaveCount(1)
    expect(photoStorageSnapshot(currentPhotoId)).toEqual(afterFirstUpload)

    await page.goto(timelineUrl(nextPhotoUtcDate, 'UTC'))
    await expect(page.getByText('この日の写真はありません。')).toBeVisible()
    await page.goto(timelineUrl(currentPhotoUtcDate, 'UTC'))
    await expect(page.getByTestId('timeline-photo-item')).toHaveCount(1)
    await expect(page.locator('.photo-grid-card time')).toHaveText('23:30')
    await page.goto(timelineUrl(currentPhotoTokyoDate, timezone))
    await expect(page.getByTestId('timeline-photo-item')).toHaveCount(1)
    await expect(page.locator('.photo-grid-card time')).toHaveText('08:30')

    await page.route(`**/api/v1/media/${currentPhotoId}/thumbnail`, (route) =>
      route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({
          error: { code: 'not_found', message: 'not found' },
        }),
      }),
    )
    await page.goto(timelineUrl(currentPhotoTokyoDate, timezone))
    await page.getByTestId('timeline-photo-item').scrollIntoViewIfNeeded()
    await expect(
      page.getByTestId('photo-timeline-card').getByRole('img', {
        name: 'synthetic-fixture.jpgのサムネイルを読み込めません',
      }),
    ).toBeVisible()
    await expect(page.getByTestId('timeline-photo-item')).toHaveCount(1)
    await expect(page.getByTestId('photo-grid-card')).toHaveCount(1)
  })
})
