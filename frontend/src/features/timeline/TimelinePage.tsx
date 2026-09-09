import { useEffect, useState } from 'react'

import { ApiClientError, getAppStatistics, getTimeline } from '../../api/client'
import type { StatisticsResponse, TimelineResponse } from '../../api/types'
import { Dashboard } from './Dashboard'
import { nextCalendarDate, readTimelineLocation } from './date'
import { TimelineItem } from './TimelineItem'

function errorMessage(error: unknown): string {
  if (error instanceof ApiClientError) {
    return error.message
  }
  return '予期しないエラーが発生しました。'
}

export function TimelinePage() {
  const { date, timezone } = readTimelineLocation()
  const [timeline, setTimeline] = useState<TimelineResponse | null>(null)
  const [timelineError, setTimelineError] = useState<string | null>(null)
  const [timelineLoading, setTimelineLoading] = useState(true)
  const [timelineRetry, setTimelineRetry] = useState(0)
  const [statistics, setStatistics] = useState<StatisticsResponse | null>(null)
  const [statisticsError, setStatisticsError] = useState<string | null>(null)
  const [statisticsLoading, setStatisticsLoading] = useState(true)
  const [statisticsRetry, setStatisticsRetry] = useState(0)
  const nextDate = nextCalendarDate(date)

  const retryTimeline = () => {
    setTimeline(null)
    setTimelineError(null)
    setTimelineLoading(true)
    setTimelineRetry((value) => value + 1)
  }

  const retryStatistics = () => {
    setStatistics(null)
    setStatisticsError(null)
    setStatisticsLoading(true)
    setStatisticsRetry((value) => value + 1)
  }

  useEffect(() => {
    const controller = new AbortController()
    void getTimeline(date, timezone, controller.signal)
      .then((response) => {
        setTimeline(response)
        setTimelineLoading(false)
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setTimelineError(errorMessage(error))
        setTimelineLoading(false)
      })

    return () => controller.abort()
  }, [date, timezone, timelineRetry])

  useEffect(() => {
    const controller = new AbortController()
    void getAppStatistics(date, nextDate, timezone, controller.signal)
      .then((response) => {
        setStatistics(response)
        setStatisticsLoading(false)
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setStatisticsError(errorMessage(error))
        setStatisticsLoading(false)
      })

    return () => controller.abort()
  }, [date, nextDate, timezone, statisticsRetry])

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
            <span className="date-label">表示日</span>
            <time dateTime={date}>{date}</time>
            <span className="timezone-label">{timezone}</span>
          </div>
        </header>

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

          {timelineLoading && (
            <p className="panel-status">Timelineを読み込んでいます…</p>
          )}
          {timelineError !== null && (
            <div className="error-state" role="alert">
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
                <p>別の日付を指定すると、記録されたSessionを確認できます。</p>
              </div>
            ) : (
              <ol className="timeline-list">
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
      </div>
    </main>
  )
}
