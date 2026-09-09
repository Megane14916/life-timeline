export const DEFAULT_DATE = '2026-09-03'
export const DEFAULT_TIMEZONE = 'Asia/Tokyo'

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/

export function isCalendarDate(value: string | null): value is string {
  if (value === null || !DATE_PATTERN.test(value)) {
    return false
  }

  const parsed = new Date(`${value}T00:00:00Z`)
  return (
    !Number.isNaN(parsed.valueOf()) &&
    parsed.toISOString().slice(0, 10) === value
  )
}

export function nextCalendarDate(value: string): string {
  const parsed = new Date(`${value}T00:00:00Z`)
  parsed.setUTCDate(parsed.getUTCDate() + 1)
  return parsed.toISOString().slice(0, 10)
}

export function readTimelineLocation(): { date: string; timezone: string } {
  const params = new URLSearchParams(window.location.search)
  const requestedDate = params.get('date')
  return {
    date: isCalendarDate(requestedDate) ? requestedDate : DEFAULT_DATE,
    timezone: params.get('timezone') ?? DEFAULT_TIMEZONE,
  }
}
