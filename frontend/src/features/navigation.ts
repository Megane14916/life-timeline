import type { TimelineLocation } from './timeline/date'

export type AppPagePath = '/timeline' | '/map'

export function pageHref(
  path: AppPagePath,
  date: string,
  timezone: string,
  visitId?: string,
): string {
  const params = new URLSearchParams({ date, timezone })
  if (visitId !== undefined) params.set('visit', visitId)
  return `${path}?${params.toString()}`
}

export function navigateToPage(
  path: AppPagePath,
  location: TimelineLocation,
  date: string,
  visitId?: string,
): void {
  window.history.pushState(
    {},
    '',
    pageHref(path, date, location.timezone, visitId),
  )
  window.dispatchEvent(new PopStateEvent('popstate'))
}
