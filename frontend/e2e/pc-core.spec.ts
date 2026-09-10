import { expect, test } from '@playwright/test'

const seededDate = '2026-09-03'
const timezone = 'Asia/Tokyo'
const syncedDate = '2026-09-05'
const syncedDeviceId = '01K4N6Q2N6N8YJ7W4M2D3A9B5C'
const syncedAppId = '01K4N6R7KQJ8J2W9VQW4B6M0TN'
const syncedSessionId = '01K4N70E3Q6N9D6E6G0C8M2H1P'
const syncedStartedAtMs = Date.UTC(2026, 8, 5, 1, 0)

function timelineUrl(date = seededDate) {
  return `/timeline?date=${date}&timezone=${encodeURIComponent(timezone)}`
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
})
