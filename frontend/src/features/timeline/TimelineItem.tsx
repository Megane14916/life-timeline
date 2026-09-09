import type { TimelineItem as TimelineItemData } from '../../api/types'
import { formatDuration, formatPlatform, formatTime } from './format'

interface TimelineItemProps {
  item: TimelineItemData
  timezone: string
}

export function TimelineItem({ item, timezone }: TimelineItemProps) {
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
