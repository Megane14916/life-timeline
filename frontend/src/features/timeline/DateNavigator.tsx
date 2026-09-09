import { shiftCalendarDate, type TimelineLocation } from './date'

interface DateNavigatorProps {
  location: TimelineLocation
  onDateChange: (date: string) => void
  onToday: () => void
}

export function DateNavigator({
  location,
  onDateChange,
  onToday,
}: DateNavigatorProps) {
  const hasError =
    location.dateError !== null || location.timezoneError !== null
  const previousDate = shiftCalendarDate(location.date, -1)
  const nextDate = shiftCalendarDate(location.date, 1)

  return (
    <section className="date-navigator" aria-label="表示日の操作">
      <div className="date-controls">
        <button
          type="button"
          className="secondary-button"
          onClick={() => onDateChange('previous')}
          disabled={previousDate === null}
          aria-label="前日を表示"
        >
          ← 前日
        </button>
        <label className="date-input-label">
          <span>表示日</span>
          <input
            type="date"
            value={location.date}
            onChange={(event) => onDateChange(event.target.value)}
            aria-label="表示日"
            data-testid="date-input"
          />
        </label>
        <button
          type="button"
          className="secondary-button"
          onClick={() => onDateChange('next')}
          disabled={nextDate === null}
          aria-label="翌日を表示"
        >
          翌日 →
        </button>
        <button type="button" className="today-button" onClick={onToday}>
          今日
        </button>
      </div>
      <div className="date-meta">
        <span className="timezone-label">{location.timezone}</span>
        {hasError && (
          <div className="location-error" role="alert">
            {location.dateError !== null && <span>{location.dateError}</span>}
            {location.timezoneError !== null && (
              <span>{location.timezoneError}</span>
            )}
            <button type="button" onClick={onToday}>
              今日へ戻す
            </button>
          </div>
        )}
      </div>
    </section>
  )
}
