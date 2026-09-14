import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { MapResponse } from '../../api/types'
import { MapPage } from './MapPage'

vi.mock('./LeafletMap', () => ({
  LeafletMap: ({
    data,
    onlineBasemapEnabled,
    selectedVisitId,
  }: {
    data: MapResponse
    onlineBasemapEnabled: boolean
    selectedVisitId: string | null
  }) => (
    <div
      data-testid="leaflet-map-mock"
      data-date={data.date}
      data-online-basemap={String(onlineBasemapEnabled)}
      data-selected-visit={selectedVisitId ?? ''}
    />
  ),
}))

const visitId = '01J00000000000000000001501'
const mapResponse: MapResponse = {
  date: '2026-09-03',
  timezone: 'Asia/Tokyo',
  rangeStart: '2026-09-02T15:00:00.000Z',
  rangeEnd: '2026-09-03T15:00:00.000Z',
  routes: [
    {
      deviceId: '01J00000000000000000001001',
      deviceName: 'Synthetic Android',
      startedAt: '2026-09-03T00:00:00.000Z',
      endedAt: '2026-09-03T00:05:00.000Z',
      pointCount: 2,
      points: [
        {
          recordedAt: '2026-09-03T00:00:00.000Z',
          latitude: 35.68124,
          longitude: 139.76712,
          accuracyM: 25,
        },
        {
          recordedAt: '2026-09-03T00:05:00.000Z',
          latitude: 35.682,
          longitude: 139.768,
          accuracyM: 30,
        },
      ],
    },
  ],
  placeVisits: [
    {
      type: 'place_visit',
      id: visitId,
      deviceId: '01J00000000000000000001001',
      deviceName: 'Synthetic Android',
      startedAt: '2026-09-03T00:00:00.000Z',
      endedAt: '2026-09-03T00:20:00.000Z',
      durationMs: 1_200_000,
      centerLatitude: 35.68124,
      centerLongitude: 139.76712,
      radiusM: 28,
      pointCount: 4,
      label: '滞在地点',
      display: {
        startedAt: '2026-09-03T00:00:00.000Z',
        endedAt: '2026-09-03T00:20:00.000Z',
        durationMs: 1_200_000,
        continuesFromPreviousDay: false,
        continuesToNextDay: false,
        endsAtDayBoundary: false,
      },
    },
  ],
  photos: [
    {
      type: 'photo',
      id: '01J00000000000000000001401',
      deviceId: '01J00000000000000000001001',
      deviceName: 'Synthetic Android',
      source: 'android_media_store',
      takenAt: '2026-09-03T00:30:00.000Z',
      filename: 'synthetic-map-photo.jpg',
      mimeType: 'image/jpeg',
      width: 640,
      height: 480,
      latitude: 35.683,
      longitude: 139.769,
      thumbnailUrl: null,
    },
  ],
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('MapPage', () => {
  beforeEach(() => {
    window.history.replaceState(
      {},
      '',
      '/map?date=2026-09-03&timezone=Asia%2FTokyo',
    )
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonResponse(mapResponse))),
    )
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('loads the daily map, text alternatives, layers, and date-preserving navigation', async () => {
    render(<MapPage />)

    expect(await screen.findByTestId('leaflet-map-mock')).toHaveAttribute(
      'data-date',
      '2026-09-03',
    )
    expect(screen.getByRole('navigation', { name: '主要ページ' })).toBeVisible()
    expect(screen.getByRole('link', { name: 'Timeline' })).toHaveAttribute(
      'href',
      '/timeline?date=2026-09-03&timezone=Asia%2FTokyo',
    )
    expect(screen.getByRole('link', { name: 'Map' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(screen.getByText('Synthetic Android')).toBeVisible()
    expect(screen.getByText('synthetic-map-photo.jpg')).toBeVisible()
    expect(screen.getByRole('button', { name: '地図で表示' })).toBeVisible()
    const fetchCall = vi.mocked(fetch).mock.calls[0]
    expect(fetchCall?.[0]).toBe(
      '/api/v1/map?date=2026-09-03&timezone=Asia%2FTokyo',
    )
    expect(fetchCall?.[1]?.signal).toBeInstanceOf(AbortSignal)

    fireEvent.click(screen.getByRole('button', { name: '地図で表示' }))
    expect(window.location.search).toContain(`visit=${visitId}`)
    expect(screen.getByTestId('leaflet-map-mock')).toHaveAttribute(
      'data-selected-visit',
      visitId,
    )

    fireEvent.click(
      screen.getByRole('button', { name: 'オンライン背景地図を表示' }),
    )
    expect(
      screen.getByRole('button', { name: 'オンライン背景地図を非表示' }),
    ).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByTestId('leaflet-map-mock')).toHaveAttribute(
      'data-online-basemap',
      'true',
    )
  })

  it('shows an explicit empty state without inventing a map center', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          jsonResponse({
            ...mapResponse,
            routes: [],
            placeVisits: [],
            photos: [],
          }),
        ),
      ),
    )

    render(<MapPage />)

    expect(await screen.findByTestId('map-empty')).toBeVisible()
    expect(screen.queryByTestId('leaflet-map-mock')).not.toBeInTheDocument()
    expect(screen.getByText('この日の地図記録はありません')).toBeVisible()
  })

  it('does not request invalid URL values and recovers through the shared date navigator', async () => {
    window.history.replaceState(
      {},
      '',
      '/map?date=not-a-date&timezone=Not%2FAZone',
    )
    const fetchMock = vi.fn(() => Promise.resolve(jsonResponse(mapResponse)))
    vi.stubGlobal('fetch', fetchMock)

    render(<MapPage />)

    expect(screen.getByRole('alert')).toHaveTextContent(
      '有効な日付ではありません',
    )
    expect(fetchMock).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '今日へ戻す' }))
    expect(await screen.findByTestId('leaflet-map-mock')).toBeVisible()
    await waitFor(() =>
      expect(window.location.search).not.toContain('Not%2FAZone'),
    )
  })

  it('reports API errors and retries the current date', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse(
          {
            error: {
              code: 'internal_error',
              message: 'Internal server error.',
            },
          },
          500,
        ),
      )
      .mockResolvedValueOnce(jsonResponse(mapResponse))
    vi.stubGlobal('fetch', fetchMock)

    render(<MapPage />)

    const error = await screen.findByTestId('map-error')
    expect(error).toHaveTextContent('Map APIの取得に失敗しました')
    fireEvent.click(screen.getByRole('button', { name: '再試行' }))
    expect(await screen.findByTestId('leaflet-map-mock')).toBeVisible()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('removes an unknown visit deep link and falls back to the regular map', async () => {
    window.history.replaceState(
      {},
      '',
      '/map?date=2026-09-03&timezone=Asia%2FTokyo&visit=missing-visit',
    )

    render(<MapPage />)

    expect(await screen.findByTestId('leaflet-map-mock')).toBeVisible()
    await waitFor(() => expect(window.location.search).not.toContain('visit='))
  })
})
