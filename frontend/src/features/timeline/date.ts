export const DEFAULT_TIMEZONE = 'Asia/Tokyo'

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/

export interface TimelineLocation {
  date: string
  timezone: string
  dateError: string | null
  timezoneError: string | null
  browserTimezone: string
  isValid: boolean
}

function browserTimezone(): string {
  try {
    const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone
    return timezone || DEFAULT_TIMEZONE
  } catch {
    return DEFAULT_TIMEZONE
  }
}

export function isValidTimezone(value: string): boolean {
  if (!value) return false
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: value }).format()
    return true
  } catch {
    return false
  }
}

export function isCalendarDate(value: string | null): boolean {
  if (value === null || !DATE_PATTERN.test(value)) {
    return false
  }

  const year = Number(value.slice(0, 4))
  if (year < 1 || year > 9999) {
    return false
  }

  const parsed = new Date(`${value}T00:00:00Z`)
  return (
    !Number.isNaN(parsed.valueOf()) &&
    parsed.toISOString().slice(0, 10) === value
  )
}

export function todayInTimezone(timezone: string, now = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    timeZone: timezone,
  }).formatToParts(now)
  const valueFor = (type: string) =>
    parts.find((part) => part.type === type)?.value ?? ''
  return `${valueFor('year')}-${valueFor('month')}-${valueFor('day')}`
}

export function shiftCalendarDate(value: string, days: number): string | null {
  const parsed = new Date(`${value}T00:00:00Z`)
  parsed.setUTCDate(parsed.getUTCDate() + days)
  const shifted = parsed.toISOString().slice(0, 10)
  return isCalendarDate(shifted) ? shifted : null
}

export function nextCalendarDate(value: string): string {
  return shiftCalendarDate(value, 1) ?? value
}

export function readTimelineLocation(
  search = window.location.search,
): TimelineLocation {
  const params = new URLSearchParams(search)
  const browserZone = browserTimezone()
  const timezoneParam = params.get('timezone')
  const timezone = timezoneParam ?? browserZone
  const validTimezone = isValidTimezone(timezone)
  const safeTimezone = validTimezone ? timezone : browserZone
  const dateParam = params.get('date')
  const date =
    dateParam && isCalendarDate(dateParam)
      ? dateParam
      : todayInTimezone(safeTimezone)

  return {
    date,
    timezone: safeTimezone,
    dateError:
      dateParam !== null && !isCalendarDate(dateParam)
        ? `URLの日付「${dateParam}」は有効な日付ではありません。`
        : null,
    timezoneError:
      timezoneParam !== null && !validTimezone
        ? `URLのタイムゾーン「${timezoneParam}」を解決できません。`
        : null,
    browserTimezone: browserZone,
    isValid:
      (dateParam === null || isCalendarDate(dateParam)) &&
      (timezoneParam === null || validTimezone),
  }
}
