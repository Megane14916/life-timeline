import { expect, test } from '@playwright/test'

const seededDate = '2026-09-03'
const timezone = 'Asia/Tokyo'

function timelineUrl(date = seededDate) {
  return `/timeline?date=${date}&timezone=${encodeURIComponent(timezone)}`
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
})
