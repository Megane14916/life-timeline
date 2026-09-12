import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { App } from './App'

const requestedPaths: string[] = []

function requestPath(input: RequestInfo | URL): string {
  if (typeof input === 'string') return input
  if (input instanceof URL) return input.toString()
  return input.url
}

const timelineResponse = {
  date: '2026-09-03',
  timezone: 'Asia/Tokyo',
  rangeStart: '2026-09-02T15:00:00.000Z',
  rangeEnd: '2026-09-03T15:00:00.000Z',
  items: [
    {
      type: 'app_session' as const,
      id: '01J00000000000000000001302',
      deviceId: '01J00000000000000000001001',
      deviceName: 'Demo Android A',
      platform: 'android' as const,
      appId: '01J00000000000000000001101',
      appIdentifier: 'com.google.android.chrome',
      appName: 'Chrome',
      source: 'android_usage_stats',
      startedAt: '2026-09-02T14:50:00.000Z',
      endedAt: '2026-09-02T15:10:00.000Z',
      durationMs: 1200000,
      display: {
        startedAt: '2026-09-02T15:00:00.000Z',
        endedAt: '2026-09-02T15:10:00.000Z',
        durationMs: 600000,
        continuesFromPreviousDay: true,
        continuesToNextDay: false,
        endsAtDayBoundary: false,
      },
    },
  ],
}

const photoItem = {
  type: 'photo' as const,
  id: '01J00000000000000000001401',
  deviceId: '01J00000000000000000001001',
  deviceName: 'Demo Android A',
  source: 'android_media_store' as const,
  takenAt: '2026-09-03T00:30:00.000Z',
  filename: 'IMG_20260903_093000.jpg',
  mimeType: 'image/jpeg',
  width: 4032,
  height: 3024,
  latitude: null,
  longitude: null,
  thumbnailUrl: '/api/v1/media/01J00000000000000000001401/thumbnail',
}

const photosResponse = {
  date: '2026-09-03',
  timezone: 'Asia/Tokyo',
  rangeStart: '2026-09-02T15:00:00.000Z',
  rangeEnd: '2026-09-03T15:00:00.000Z',
  items: [],
}

const statisticsResponse = {
  from: '2026-09-03',
  to: '2026-09-04',
  timezone: 'Asia/Tokyo',
  rangeStart: '2026-09-02T15:00:00.000Z',
  rangeEnd: '2026-09-03T15:00:00.000Z',
  totals: { usageMs: 3900000, sessionCount: 4, appCount: 2 },
  items: [
    {
      appId: '01J00000000000000000001101',
      platform: 'android' as const,
      appIdentifier: 'com.google.android.chrome',
      appName: 'Chrome',
      usageMs: 2100000,
      sessionCount: 3,
    },
  ],
}

describe('App', () => {
  beforeEach(() => {
    requestedPaths.length = 0
    window.history.replaceState(
      {},
      '',
      '/timeline?date=2026-09-03&timezone=Asia%2FTokyo',
    )
    vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
      const url = requestPath(input)
      requestedPaths.push(url)
      const body = url.includes('/stats/apps')
        ? statisticsResponse
        : url.includes('/api/v1/photos')
          ? photosResponse
          : timelineResponse
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('loads the Dashboard and Timeline independently from the API', async () => {
    render(<App />)

    expect(
      screen.getByRole('heading', { level: 1, name: 'life-timeline' }),
    ).toBeInTheDocument()
    expect(await screen.findByText('1時間5分')).toBeInTheDocument()
    expect(screen.getByText('前日から継続')).toBeInTheDocument()
    expect(screen.getByText('00:00')).toBeInTheDocument()
    expect(screen.getByText('Demo Android A')).toBeInTheDocument()
    expect(screen.getByText('3件')).toBeInTheDocument()
    expect(requestedPaths).toContain(
      '/api/v1/timeline?date=2026-09-03&timezone=Asia%2FTokyo',
    )
    expect(requestedPaths).toContain(
      '/api/v1/photos?date=2026-09-03&timezone=Asia%2FTokyo',
    )
    expect(requestedPaths).toContain(
      '/api/v1/stats/apps?from=2026-09-03&to=2026-09-04&timezone=Asia%2FTokyo',
    )
  })

  it('renders photo Timeline cards and a separate daily grid without changing app totals', async () => {
    vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
      const url = requestPath(input)
      requestedPaths.push(url)
      const body = url.includes('/stats/apps')
        ? statisticsResponse
        : url.includes('/api/v1/photos')
          ? { ...photosResponse, items: [photoItem] }
          : {
              ...timelineResponse,
              items: [...timelineResponse.items, photoItem],
            }
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })

    render(<App />)

    expect(await screen.findByTestId('timeline-photo-item')).toBeInTheDocument()
    expect(await screen.findByTestId('photo-grid-card')).toBeInTheDocument()
    expect(screen.getAllByText('IMG_20260903_093000.jpg')).toHaveLength(2)
    expect(
      screen.getAllByRole('img', {
        name: 'IMG_20260903_093000.jpgの写真サムネイル',
      }),
    ).toHaveLength(2)
    const timelineCard = screen.getByTestId('photo-timeline-card')
    expect(within(timelineCard).getByText('Demo Android A')).toBeInTheDocument()
    expect(within(timelineCard).getByText('4032 × 3024px')).toBeInTheDocument()
    for (const image of screen.getAllByRole('img', {
      name: 'IMG_20260903_093000.jpgの写真サムネイル',
    })) {
      expect(image).toHaveAttribute('loading', 'lazy')
      expect(image).toHaveAttribute('width', '4032')
      expect(image).toHaveAttribute('height', '3024')
      expect(image).toHaveAttribute(
        'src',
        '/api/v1/media/01J00000000000000000001401/thumbnail',
      )
    }
    fireEvent.error(
      within(timelineCard).getByRole('img', {
        name: 'IMG_20260903_093000.jpgの写真サムネイル',
      }),
    )
    expect(
      within(timelineCard).getByRole('img', {
        name: 'IMG_20260903_093000.jpgのサムネイルを読み込めません',
      }),
    ).toBeInTheDocument()
    expect(screen.getByTestId('timeline-item')).toBeInTheDocument()
    expect(screen.getByTestId('photo-grid-card')).toBeInTheDocument()
    expect(screen.getByTestId('dashboard-session-count')).toHaveTextContent(
      '4件',
    )
    expect(screen.getByTestId('dashboard-app-count')).toHaveTextContent('2件')
    expect(
      within(screen.getByRole('region', { name: 'Dashboard' })).queryByText(
        photoItem.filename,
      ),
    ).toBeNull()
  })

  it('shows a Photos API error independently and retries that panel', async () => {
    let photoAttempts = 0
    vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
      const url = requestPath(input)
      requestedPaths.push(url)
      if (url.includes('/api/v1/photos') && photoAttempts++ === 0) {
        return new Response(
          JSON.stringify({
            error: {
              code: 'internal_error',
              message: 'Internal server error.',
            },
          }),
          { status: 500, headers: { 'Content-Type': 'application/json' } },
        )
      }
      const body = url.includes('/stats/apps')
        ? statisticsResponse
        : url.includes('/api/v1/photos')
          ? { ...photosResponse, items: [photoItem] }
          : timelineResponse
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })

    render(<App />)

    const error = await screen.findByTestId('photos-error')
    expect(error).toHaveTextContent('写真一覧の取得に失敗しました')
    expect(await screen.findByText('Demo Android A')).toBeInTheDocument()
    fireEvent.click(within(error).getByRole('button', { name: '再試行' }))
    expect(await screen.findByTestId('photo-grid-card')).toBeInTheDocument()
  })

  it('does not show photos from an older date after a slower response completes', async () => {
    const stalePhoto = { ...photoItem, filename: 'STALE_OLD_DATE.jpg' }
    const datePhoto = {
      ...photoItem,
      id: '01J00000000000000000001402',
      filename: 'CURRENT_DATE.jpg',
      thumbnailUrl: null,
    }
    const timezonePhoto = {
      ...photoItem,
      id: '01J00000000000000000001403',
      filename: 'CURRENT_TIMEZONE.jpg',
      thumbnailUrl: null,
    }
    let finishOldRequest: ((response: Response) => void) | undefined
    vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
      const url = new URL(requestPath(input), window.location.origin)
      requestedPaths.push(url.pathname + url.search)
      if (url.pathname === '/api/v1/photos') {
        const date = url.searchParams.get('date')
        const timezone = url.searchParams.get('timezone')
        if (date === '2026-09-03' && timezone === 'Asia/Tokyo') {
          return new Promise<Response>((resolve) => {
            finishOldRequest = resolve
          })
        }
        const selectedPhoto = timezone === 'UTC' ? timezonePhoto : datePhoto
        return new Response(
          JSON.stringify({
            ...photosResponse,
            date,
            timezone,
            items: [selectedPhoto],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        )
      }
      const body =
        url.pathname === '/api/v1/stats/apps'
          ? statisticsResponse
          : timelineResponse
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })

    render(<App />)
    await screen.findByText('写真を読み込んでいます…')
    expect(finishOldRequest).toBeDefined()

    fireEvent.click(screen.getByRole('button', { name: '翌日を表示' }))
    expect(await screen.findByText('CURRENT_DATE.jpg')).toBeInTheDocument()

    window.history.pushState({}, '', '/timeline?date=2026-09-04&timezone=UTC')
    window.dispatchEvent(new PopStateEvent('popstate'))
    expect(await screen.findByText('CURRENT_TIMEZONE.jpg')).toBeInTheDocument()

    await act(async () => {
      finishOldRequest?.(
        new Response(
          JSON.stringify({ ...photosResponse, items: [stalePhoto] }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
      await Promise.resolve()
    })
    expect(screen.queryByText('STALE_OLD_DATE.jpg')).not.toBeInTheDocument()
    expect(screen.queryByText('CURRENT_DATE.jpg')).not.toBeInTheDocument()
    expect(screen.getByText('CURRENT_TIMEZONE.jpg')).toBeInTheDocument()
  })

  it('keeps the successful panel visible when the other API fails', async () => {
    vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
      const url = requestPath(input)
      requestedPaths.push(url)
      if (url.includes('/stats/apps')) {
        return new Response(
          JSON.stringify({
            error: {
              code: 'internal_error',
              message: 'Internal server error.',
            },
          }),
          { status: 500, headers: { 'Content-Type': 'application/json' } },
        )
      }
      const body = url.includes('/api/v1/photos')
        ? photosResponse
        : timelineResponse
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })

    render(<App />)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Dashboardの取得に失敗しました',
    )
    expect(await screen.findByText('Demo Android A')).toBeInTheDocument()
    expect(
      screen.queryByText('統計を読み込んでいます…'),
    ).not.toBeInTheDocument()
  })

  it('renders zero totals and an empty timeline without inventing data', async () => {
    vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
      const url = requestPath(input)
      requestedPaths.push(url)
      const body = url.includes('/stats/apps')
        ? {
            ...statisticsResponse,
            totals: { usageMs: 0, sessionCount: 0, appCount: 0 },
            items: [],
          }
        : { ...timelineResponse, items: [] }
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })

    render(<App />)

    expect(await screen.findByText('0分')).toBeInTheDocument()
    expect(screen.getAllByText('0件')).toHaveLength(2)
    expect(screen.getByText('この日の記録はありません')).toBeInTheDocument()
    expect(screen.getByText('この日の写真はありません。')).toBeInTheDocument()
  })

  it('keeps the selected date in the URL and follows browser history', async () => {
    render(<App />)
    await screen.findByText('1時間5分')

    fireEvent.click(screen.getByRole('button', { name: '翌日を表示' }))
    expect(window.location.search).toContain('date=2026-09-04')
    expect(await screen.findByDisplayValue('2026-09-04')).toBeInTheDocument()
    expect(requestedPaths).toContain(
      '/api/v1/timeline?date=2026-09-04&timezone=Asia%2FTokyo',
    )

    window.history.pushState(
      {},
      '',
      '/timeline?date=2026-09-02&timezone=Asia%2FTokyo',
    )
    window.dispatchEvent(new PopStateEvent('popstate'))
    expect(await screen.findByDisplayValue('2026-09-02')).toBeInTheDocument()
    expect(requestedPaths).toContain(
      '/api/v1/stats/apps?from=2026-09-02&to=2026-09-03&timezone=Asia%2FTokyo',
    )
  })

  it('does not fetch for invalid URL values and offers a recovery action', async () => {
    window.history.replaceState(
      {},
      '',
      '/timeline?date=not-a-date&timezone=Not%2FAZone',
    )

    render(<App />)

    expect(screen.getByRole('alert')).toHaveTextContent(
      '有効な日付ではありません',
    )
    expect(screen.getByRole('alert')).toHaveTextContent('タイムゾーン')
    expect(requestedPaths).toHaveLength(0)

    fireEvent.click(screen.getByRole('button', { name: '今日へ戻す' }))
    expect(
      await screen.findByDisplayValue(/\d{4}-\d{2}-\d{2}/),
    ).toBeInTheDocument()
    expect(window.location.search).not.toContain('Not%2FAZone')
    expect(requestedPaths.length).toBe(3)
  })
})
