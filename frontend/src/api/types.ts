export type Platform = 'android' | 'windows'

export interface TimelineDisplay {
  startedAt: string
  endedAt: string
  durationMs: number
  continuesFromPreviousDay: boolean
  continuesToNextDay: boolean
  endsAtDayBoundary: boolean
}

export interface AppSessionTimelineItem {
  type: 'app_session'
  id: string
  deviceId: string
  deviceName: string
  platform: Platform
  appId: string
  appIdentifier: string
  appName: string
  source: string
  startedAt: string
  endedAt: string
  durationMs: number
  display: TimelineDisplay
}

export interface PhotoTimelineItem {
  type: 'photo'
  id: string
  deviceId: string
  deviceName: string
  source: 'android_media_store'
  takenAt: string
  filename: string
  mimeType: string
  width: number | null
  height: number | null
  latitude: number | null
  longitude: number | null
  thumbnailUrl: string | null
}

export type TimelineItem = AppSessionTimelineItem | PhotoTimelineItem

export interface TimelineResponse {
  date: string
  timezone: string
  rangeStart: string
  rangeEnd: string
  items: TimelineItem[]
}

export interface PhotosResponse {
  date: string
  timezone: string
  rangeStart: string
  rangeEnd: string
  items: PhotoTimelineItem[]
}

export interface StatisticsTotals {
  usageMs: number
  sessionCount: number
  appCount: number
}

export interface StatisticsAppItem {
  appId: string
  platform: Platform
  appIdentifier: string
  appName: string
  usageMs: number
  sessionCount: number
}

export interface StatisticsResponse {
  from: string
  to: string
  timezone: string
  rangeStart: string
  rangeEnd: string
  totals: StatisticsTotals
  items: StatisticsAppItem[]
}

export interface ApiErrorBody {
  error?: {
    code?: string
    message?: string
    field?: string
  }
}
