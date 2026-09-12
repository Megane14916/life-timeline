import type {
  AppSessionTimelineItem as AppSessionTimelineItemData,
  PhotoTimelineItem as PhotoTimelineItemData,
  TimelineItem as TimelineItemData,
} from '../../api/types'
import { formatDuration, formatPlatform, formatTime } from './format'
import { PhotoTimelineCard } from './PhotoTimelineCard'

interface TimelineItemProps {
  item: TimelineItemData
  timezone: string
}

function AppSessionItem({
  item,
  timezone,
}: {
  item: AppSessionTimelineItemData
  timezone: string
}) {
  const flags = [
    item.display.continuesFromPreviousDay ? '前日から継続' : null,
    item.display.continuesToNextDay ? '翌日に継続' : null,
    item.display.endsAtDayBoundary ? '日末で終了' : null,
  ].filter((flag): flag is string => flag !== null)

  return (
    <li className="timeline-item" data-testid="timeline-item">
      <div className="timeline-time" aria-label="表示時間">
        <time dateTime={item.display.startedAt}>
          {formatTime(item.display.startedAt, timezone)}
        </time>
        <span aria-hidden="true">–</span>
        <time dateTime={item.display.endedAt}>
          {formatTime(item.display.endedAt, timezone)}
        </time>
      </div>
      <div className="timeline-marker" aria-hidden="true" />
      <article className="timeline-card">
        <div className="timeline-card-heading">
          <div>
            <h3>{item.appName}</h3>
            <p className="app-identifier">{item.appIdentifier}</p>
          </div>
          <strong data-testid="timeline-item-duration">
            {formatDuration(item.display.durationMs)}
          </strong>
        </div>
        <div className="timeline-details">
          <span>{formatPlatform(item.platform)}</span>
          <span>{item.deviceName}</span>
          <span>{item.source}</span>
        </div>
        {flags.length > 0 && (
          <div className="timeline-flags" aria-label="Sessionの境界情報">
            {flags.map((flag) => (
              <span key={flag}>{flag}</span>
            ))}
          </div>
        )}
      </article>
    </li>
  )
}

function PhotoItem({
  item,
  timezone,
}: {
  item: PhotoTimelineItemData
  timezone: string
}) {
  return (
    <li
      className="timeline-item photo-timeline-item"
      data-testid="timeline-photo-item"
    >
      <div className="timeline-time" aria-label="撮影時刻">
        <time
          dateTime={item.takenAt}
          aria-label={`撮影時刻 ${formatTime(item.takenAt, timezone)}`}
        >
          {formatTime(item.takenAt, timezone)}
        </time>
      </div>
      <div
        className="timeline-marker photo-timeline-marker"
        aria-hidden="true"
      />
      <PhotoTimelineCard item={item} />
    </li>
  )
}

export function TimelineItem({ item, timezone }: TimelineItemProps) {
  if (item.type === 'photo') {
    return <PhotoItem item={item} timezone={timezone} />
  }
  return <AppSessionItem item={item} timezone={timezone} />
}
