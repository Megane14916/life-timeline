import { useEffect, useState } from 'react'

import {
  ApiClientError,
  getAppStatistics,
  getPhotos,
  getTimeline,
} from '../../api/client'
import type {
  PhotosResponse,
  StatisticsResponse,
  TimelineResponse,
} from '../../api/types'
import { Dashboard } from './Dashboard'
import {
  readTimelineLocation,
  shiftCalendarDate,
  todayInTimezone,
  type TimelineLocation,
} from './date'
import { DateNavigator } from './DateNavigator'
import { nextCalendarDate } from './date'
import { TimelineItem } from './TimelineItem'
import { PhotosSection } from './PhotosSection'

interface PanelState<T> {
  key: string
  data: T | null
  error: string | null
}

const initialPanelState: PanelState<never> = {
  key: '',
  data: null,
  error: null,
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiClientError) {
    return error.message
  }
  return '予期しないエラーが発生しました。'
}

function locationKey(location: TimelineLocation): string {
  return `${location.date}|${location.timezone}`
}

function navigateTo(location: TimelineLocation, date: string): void {
  const params = new URLSearchParams({
    date,
    timezone: location.timezone,
  })
  window.history.pushState({}, '', `/timeline?${params.toString()}`)
  window.dispatchEvent(new PopStateEvent('popstate'))
}

export function TimelinePage() {
  const [location, setLocation] = useState<TimelineLocation>(() =>
    readTimelineLocation(),
  )
  const [timelineState, setTimelineState] =
    useState<PanelState<TimelineResponse>>(initialPanelState)
  const [statisticsState, setStatisticsState] =
    useState<PanelState<StatisticsResponse>>(initialPanelState)
  const [photosState, setPhotosState] =
    useState<PanelState<PhotosResponse>>(initialPanelState)
  const [timelineRetry, setTimelineRetry] = useState(0)
  const [statisticsRetry, setStatisticsRetry] = useState(0)
  const [photosRetry, setPhotosRetry] = useState(0)
  const currentKey = locationKey(location)
  const timelineRequestKey = `${currentKey}|${timelineRetry}`
  const statisticsRequestKey = `${currentKey}|${statisticsRetry}`
  const photosRequestKey = `${currentKey}|${photosRetry}`
  const nextDate = nextCalendarDate(location.date)
  const locationIsValid = location.isValid

  useEffect(() => {
    const handlePopState = () => setLocation(readTimelineLocation())
    window.addEventListener('popstate', handlePopState)
    return () => window.removeEventListener('popstate', handlePopState)
  }, [])

  useEffect(() => {
    if (!location.isValid) return
    const params = new URLSearchParams(window.location.search)
    if (
      params.get('date') === location.date &&
      params.get('timezone') === location.timezone &&
      window.location.pathname === '/timeline'
    ) {
      return
    }
    const normalizedParams = new URLSearchParams({
      date: location.date,
      timezone: location.timezone,
    })
    window.history.replaceState(
      {},
      '',
      `/timeline?${normalizedParams.toString()}`,
    )
  }, [location.date, location.isValid, location.timezone])

  useEffect(() => {
    if (!locationIsValid) return

    const controller = new AbortController()
    void getTimeline(location.date, location.timezone, controller.signal)
      .then((response) => {
        if (controller.signal.aborted) return
        setTimelineState({
          key: timelineRequestKey,
          data: response,
          error: null,
        })
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setTimelineState({
          key: timelineRequestKey,
          data: null,
          error: errorMessage(error),
        })
      })

    return () => controller.abort()
  }, [location.date, location.timezone, locationIsValid, timelineRequestKey])

  useEffect(() => {
    if (!locationIsValid) return

    const controller = new AbortController()
    void getAppStatistics(
      location.date,
      nextDate,
      location.timezone,
      controller.signal,
    )
      .then((response) => {
        if (controller.signal.aborted) return
        setStatisticsState({
          key: statisticsRequestKey,
          data: response,
          error: null,
        })
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setStatisticsState({
          key: statisticsRequestKey,
          data: null,
          error: errorMessage(error),
        })
      })

    return () => controller.abort()
  }, [
    location.date,
    location.timezone,
    locationIsValid,
    nextDate,
    statisticsRequestKey,
  ])

  useEffect(() => {
    if (!locationIsValid) return

    const controller = new AbortController()
    void getPhotos(location.date, location.timezone, controller.signal)
      .then((response) => {
        if (controller.signal.aborted) return
        setPhotosState({
          key: photosRequestKey,
          data: response,
          error: null,
        })
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setPhotosState({
          key: photosRequestKey,
          data: null,
          error: errorMessage(error),
        })
      })

    return () => controller.abort()
  }, [location.date, location.timezone, locationIsValid, photosRequestKey])

  const timelineMatches =
    locationIsValid && timelineState.key === timelineRequestKey
  const statisticsMatches =
    locationIsValid && statisticsState.key === statisticsRequestKey
  const photosMatches = locationIsValid && photosState.key === photosRequestKey
  const timeline = timelineMatches ? timelineState.data : null
  const statistics = statisticsMatches ? statisticsState.data : null
  const photos = photosMatches ? photosState.data : null
  const timelineError = timelineMatches ? timelineState.error : null
  const statisticsError = statisticsMatches ? statisticsState.error : null
  const photosError = photosMatches ? photosState.error : null
  const timelineLoading = locationIsValid && !timelineMatches
  const statisticsLoading = locationIsValid && !statisticsMatches
  const photosLoading = locationIsValid && !photosMatches

  const changeDate = (value: string) => {
    const date =
      value === 'previous'
        ? shiftCalendarDate(location.date, -1)
        : value === 'next'
          ? shiftCalendarDate(location.date, 1)
          : value
    if (date !== null && date !== location.date) {
      navigateTo(location, date)
    }
  }

  const goToToday = () => {
    navigateTo(location, todayInTimezone(location.timezone))
  }

  const retryTimeline = () => {
    setTimelineRetry((value) => value + 1)
  }

  const retryStatistics = () => {
    setStatisticsRetry((value) => value + 1)
  }

  const retryPhotos = () => {
    setPhotosRetry((value) => value + 1)
  }

  return (
    <main className="app-shell">
      <div className="page-container">
        <header className="page-header">
          <div>
            <p className="eyebrow">Personal activity archive</p>
            <h1>life-timeline</h1>
            <p className="page-description">記録された一日の流れと利用状況</p>
          </div>
          <div className="date-context">
            <span className="date-label">選択中のタイムゾーン</span>
            <span className="timezone-label">{location.timezone}</span>
          </div>
        </header>

        <DateNavigator
          location={location}
          onDateChange={changeDate}
          onToday={goToToday}
        />

        <Dashboard
          data={statistics}
          error={statisticsError}
          loading={statisticsLoading}
          onRetry={retryStatistics}
        />

        <section
          className="timeline-section panel"
          aria-labelledby="timeline-title"
        >
          <div className="section-heading">
            <div>
              <p className="eyebrow">Chronological view</p>
              <h2 id="timeline-title">Timeline</h2>
            </div>
            {timeline !== null && (
              <span className="data-source">Timeline API</span>
            )}
          </div>

          {!locationIsValid && (
            <p className="panel-status">URLを修正すると記録を読み込めます。</p>
          )}
          {timelineLoading && (
            <p className="panel-status">Timelineを読み込んでいます…</p>
          )}
          {timelineError !== null && (
            <div
              className="error-state"
              role="alert"
              data-testid="timeline-error"
            >
              <strong>Timelineの取得に失敗しました</strong>
              <p>{timelineError}</p>
              <button type="button" onClick={retryTimeline}>
                再試行
              </button>
            </div>
          )}
          {!timelineLoading &&
            timelineError === null &&
            timeline !== null &&
            (timeline.items.length === 0 ? (
              <div className="empty-state timeline-empty">
                <span className="empty-icon" aria-hidden="true">
                  ○
                </span>
                <strong>この日の記録はありません</strong>
                <p>アプリ利用履歴や写真がある別の日付を確認できます。</p>
              </div>
            ) : (
              <ol className="timeline-list" data-testid="timeline-list">
                {timeline.items.map((item) => (
                  <TimelineItem
                    key={`${item.type}-${item.deviceId}-${item.id}`}
                    item={item}
                    timezone={timeline.timezone}
                  />
                ))}
              </ol>
            ))}
        </section>

        <PhotosSection
          data={photos}
          error={photosError}
          loading={photosLoading}
          onRetry={retryPhotos}
        />
      </div>
    </main>
  )
}
