import { useCallback, useEffect, useState } from 'react'

import { ApiClientError, getMap } from '../../api/client'
import type { MapResponse } from '../../api/types'
import { PageNavigation } from '../PageNavigation'
import { navigateToPage, pageHref } from '../navigation'
import { DateNavigator } from '../timeline/DateNavigator'
import { formatDuration, formatTime } from '../timeline/format'
import {
  readTimelineLocation,
  shiftCalendarDate,
  todayInTimezone,
  type TimelineLocation,
} from '../timeline/date'
import { LeafletMap, type TileStatus } from './LeafletMap'

interface MapRequestState {
  key: string
  data: MapResponse | null
  error: string | null
}

const initialRequestState: MapRequestState = {
  key: '',
  data: null,
  error: null,
}

function locationKey(location: TimelineLocation): string {
  return `${location.date}|${location.timezone}`
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiClientError) return error.message
  return '予期しないエラーが発生しました。'
}

function hasMapContent(data: MapResponse): boolean {
  return (
    data.routes.length > 0 ||
    data.placeVisits.length > 0 ||
    data.photos.length > 0
  )
}

export function MapPage() {
  const [location, setLocation] = useState<TimelineLocation>(() =>
    readTimelineLocation(),
  )
  const [selectedVisitId, setSelectedVisitId] = useState<string | null>(() =>
    new URLSearchParams(window.location.search).get('visit'),
  )
  const [request, setRequest] = useState<MapRequestState>(initialRequestState)
  const [retry, setRetry] = useState(0)
  const [showRoutes, setShowRoutes] = useState(true)
  const [showPlaces, setShowPlaces] = useState(true)
  const [showPhotos, setShowPhotos] = useState(true)
  const [onlineBasemapEnabled, setOnlineBasemapEnabled] = useState(false)
  const [tileStatus, setTileStatus] = useState<TileStatus>('idle')

  const currentKey = locationKey(location)
  const requestKey = `${currentKey}|${retry}`
  const locationIsValid = location.isValid
  const requestMatches = locationIsValid && request.key === requestKey
  const data = requestMatches ? request.data : null
  const error = requestMatches ? request.error : null
  const loading = locationIsValid && !requestMatches
  const activeVisitId =
    data !== null &&
    selectedVisitId !== null &&
    data.placeVisits.some((visit) => visit.id === selectedVisitId)
      ? selectedVisitId
      : null

  useEffect(() => {
    const handlePopState = () => {
      setLocation(readTimelineLocation())
      setSelectedVisitId(
        new URLSearchParams(window.location.search).get('visit'),
      )
    }
    window.addEventListener('popstate', handlePopState)
    return () => window.removeEventListener('popstate', handlePopState)
  }, [])

  useEffect(() => {
    if (!location.isValid) return
    const normalizedUrl = pageHref(
      '/map',
      location.date,
      location.timezone,
      selectedVisitId ?? undefined,
    )
    if (
      `${window.location.pathname}${window.location.search}` !== normalizedUrl
    ) {
      window.history.replaceState({}, '', normalizedUrl)
    }
  }, [location.date, location.isValid, location.timezone, selectedVisitId])

  useEffect(() => {
    if (!locationIsValid) return

    const controller = new AbortController()
    void getMap(location.date, location.timezone, controller.signal)
      .then((response) => {
        if (controller.signal.aborted) return
        setRequest({ key: requestKey, data: response, error: null })
      })
      .catch((requestError: unknown) => {
        if (controller.signal.aborted) return
        setRequest({
          key: requestKey,
          data: null,
          error: errorMessage(requestError),
        })
      })

    return () => controller.abort()
  }, [location.date, location.timezone, locationIsValid, requestKey])

  useEffect(() => {
    if (
      data === null ||
      selectedVisitId === null ||
      data.placeVisits.some((visit) => visit.id === selectedVisitId)
    ) {
      return
    }
    window.history.replaceState(
      {},
      '',
      pageHref('/map', location.date, location.timezone),
    )
  }, [data, location.date, location.timezone, selectedVisitId])

  const changeDate = (value: string) => {
    const date =
      value === 'previous'
        ? shiftCalendarDate(location.date, -1)
        : value === 'next'
          ? shiftCalendarDate(location.date, 1)
          : value
    if (date !== null && date !== location.date) {
      navigateToPage('/map', location, date)
      setSelectedVisitId(null)
    }
  }

  const goToToday = () => {
    navigateToPage('/map', location, todayInTimezone(location.timezone))
    setSelectedVisitId(null)
  }

  const selectVisit = (visitId: string) => {
    window.history.pushState(
      {},
      '',
      pageHref('/map', location.date, location.timezone, visitId),
    )
    setSelectedVisitId(visitId)
    setShowPlaces(true)
  }

  const onTileStatusChange = useCallback((status: TileStatus) => {
    setTileStatus(status)
  }, [])

  return (
    <main className="app-shell">
      <div className="page-container">
        <header className="page-header">
          <div>
            <p className="eyebrow">Daily location archive</p>
            <h1>life-timeline</h1>
            <p className="page-description">
              位置情報、滞在地点、写真を一日の地図で確認します
            </p>
          </div>
          <div className="header-actions">
            <PageNavigation location={location} currentPage="map" />
            <div className="date-context">
              <span className="date-label">選択中のタイムゾーン</span>
              <span className="timezone-label">{location.timezone}</span>
            </div>
          </div>
        </header>

        <DateNavigator
          location={location}
          onDateChange={changeDate}
          onToday={goToToday}
        />

        <section className="map-section panel" aria-labelledby="map-title">
          <div className="section-heading">
            <div>
              <p className="eyebrow">Selected day</p>
              <h2 id="map-title">Map</h2>
            </div>
            {data !== null && <span className="data-source">Map API</span>}
          </div>

          {!locationIsValid && (
            <p className="panel-status">
              URLを修正するか「今日へ戻す」と地図を読み込めます。
            </p>
          )}
          {loading && (
            <p className="panel-status" role="status">
              地図情報を読み込んでいます…
            </p>
          )}
          {error !== null && (
            <div className="error-state" role="alert" data-testid="map-error">
              <strong>Map APIの取得に失敗しました</strong>
              <p>{error}</p>
              <button
                type="button"
                onClick={() => setRetry((value) => value + 1)}
              >
                再試行
              </button>
            </div>
          )}

          {!loading &&
            error === null &&
            data !== null &&
            (hasMapContent(data) ? (
              <>
                <div className="map-controls">
                  <fieldset className="map-layer-controls">
                    <legend>表示する情報</legend>
                    <label>
                      <input
                        type="checkbox"
                        checked={showRoutes}
                        onChange={(event) =>
                          setShowRoutes(event.target.checked)
                        }
                      />
                      移動経路（{data.routes.length}）
                    </label>
                    <label>
                      <input
                        type="checkbox"
                        checked={showPlaces}
                        onChange={(event) =>
                          setShowPlaces(event.target.checked)
                        }
                      />
                      滞在地点（{data.placeVisits.length}）
                    </label>
                    <label>
                      <input
                        type="checkbox"
                        checked={showPhotos}
                        onChange={(event) =>
                          setShowPhotos(event.target.checked)
                        }
                      />
                      写真地点（{data.photos.length}）
                    </label>
                  </fieldset>
                  <div className="basemap-consent">
                    <p>
                      背景地図を有効にすると、表示範囲がOpenStreetMapへ送られ、閲覧地域を推測される場合があります。
                    </p>
                    <button
                      type="button"
                      className="secondary-button"
                      aria-pressed={onlineBasemapEnabled}
                      onClick={() =>
                        setOnlineBasemapEnabled((enabled) => !enabled)
                      }
                    >
                      {onlineBasemapEnabled
                        ? 'オンライン背景地図を非表示'
                        : 'オンライン背景地図を表示'}
                    </button>
                  </div>
                </div>
                <p className="map-attribution-note">
                  背景地図: © OpenStreetMap
                  contributors（利用を選んだ場合のみ読み込み）
                </p>
                {onlineBasemapEnabled && tileStatus === 'loading' && (
                  <p className="map-tile-status" role="status">
                    背景地図タイルを読み込んでいます…
                  </p>
                )}
                {onlineBasemapEnabled && tileStatus === 'error' && (
                  <p className="map-tile-error" role="alert">
                    背景地図を読み込めませんでした。位置情報と一覧は引き続き利用できます。
                  </p>
                )}
                <LeafletMap
                  data={data}
                  onlineBasemapEnabled={onlineBasemapEnabled}
                  showRoutes={showRoutes}
                  showPlaces={showPlaces}
                  showPhotos={showPhotos}
                  selectedVisitId={activeVisitId}
                  onTileStatusChange={onTileStatusChange}
                />
                <p className="map-gap-note">
                  記録間隔が30分を超える箇所では経路を分割し、欠測区間を推測で補いません。
                </p>
              </>
            ) : (
              <div className="empty-state map-empty" data-testid="map-empty">
                <span className="empty-icon" aria-hidden="true">
                  ◌
                </span>
                <strong>この日の地図記録はありません</strong>
                <p>
                  位置記録、滞在地点、位置情報付き写真がある別の日を確認できます。
                </p>
              </div>
            ))}
        </section>

        {!loading && error === null && data !== null && hasMapContent(data) && (
          <MapSummary
            data={data}
            selectedVisitId={activeVisitId}
            onSelectVisit={selectVisit}
          />
        )}
      </div>
    </main>
  )
}

function MapSummary({
  data,
  selectedVisitId,
  onSelectVisit,
}: {
  data: MapResponse
  selectedVisitId: string | null
  onSelectVisit: (visitId: string) => void
}) {
  return (
    <section
      className="map-summary panel"
      aria-labelledby="map-summary-title"
      data-testid="map-summary"
    >
      <div className="section-heading">
        <div>
          <p className="eyebrow">Text alternative</p>
          <h2 id="map-summary-title">地図上の記録</h2>
        </div>
        <span className="map-summary-date">
          {data.date} · {data.timezone}
        </span>
      </div>
      <p className="map-summary-intro">
        地図のマーカーと経路は、以下の一覧からも確認できます。
      </p>

      <section
        className="map-summary-group"
        aria-labelledby="route-summary-title"
      >
        <h3 id="route-summary-title">移動経路</h3>
        {data.routes.length === 0 ? (
          <p className="map-summary-empty">
            この日に表示できる経路はありません。
          </p>
        ) : (
          <ul className="map-summary-list">
            {data.routes.map((route, index) => (
              <li key={`${route.deviceId}-${route.startedAt}-${index}`}>
                <strong>{route.deviceName}</strong>
                <span>
                  {formatTime(route.startedAt, data.timezone)}–
                  {formatTime(route.endedAt, data.timezone)} ·{' '}
                  {route.pointCount}点
                  {route.pointCount === 1 ? '（単独地点）' : ''}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section
        className="map-summary-group"
        aria-labelledby="visit-summary-title"
      >
        <h3 id="visit-summary-title">滞在地点</h3>
        {data.placeVisits.length === 0 ? (
          <p className="map-summary-empty">この日に滞在地点はありません。</p>
        ) : (
          <ul className="map-summary-list">
            {data.placeVisits.map((visit) => (
              <li key={visit.id}>
                <div>
                  <strong>
                    {visit.label} · {visit.deviceName}
                  </strong>
                  <span>
                    {formatTime(visit.display.startedAt, data.timezone)}–
                    {formatTime(visit.display.endedAt, data.timezone)} ·{' '}
                    {formatDuration(visit.display.durationMs)} ·{' '}
                    {visit.centerLatitude.toFixed(5)},{' '}
                    {visit.centerLongitude.toFixed(5)}
                  </span>
                </div>
                <button
                  type="button"
                  className="secondary-button"
                  aria-pressed={selectedVisitId === visit.id}
                  onClick={() => onSelectVisit(visit.id)}
                >
                  地図で表示
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section
        className="map-summary-group"
        aria-labelledby="photo-summary-title"
      >
        <h3 id="photo-summary-title">写真地点</h3>
        {data.photos.length === 0 ? (
          <p className="map-summary-empty">
            この日に位置情報付き写真はありません。
          </p>
        ) : (
          <ul className="map-summary-list">
            {data.photos.map((photo) => (
              <li key={photo.id}>
                <strong>{photo.filename}</strong>
                <span>
                  {formatTime(photo.takenAt, data.timezone)} ·{' '}
                  {photo.deviceName} · {photo.latitude.toFixed(5)},{' '}
                  {photo.longitude.toFixed(5)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </section>
  )
}
