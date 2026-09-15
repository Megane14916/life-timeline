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
  desktopDetail: DesktopSessionDetail | null
  display: TimelineDisplay
}

export interface DesktopSessionDetail {
  windowTitle: string | null
  url: string | null
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

export interface PlaceVisitTimelineItem {
  type: 'place_visit'
  id: string
  deviceId: string
  deviceName: string
  startedAt: string
  endedAt: string
  durationMs: number
  centerLatitude: number
  centerLongitude: number
  radiusM: number
  pointCount: number
  label: '滞在地点'
  display: TimelineDisplay
}

export type TimelineItem =
  AppSessionTimelineItem | PlaceVisitTimelineItem | PhotoTimelineItem

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

export interface MapRoutePoint {
  recordedAt: string
  latitude: number
  longitude: number
  accuracyM: number
}

export interface MapRoute {
  deviceId: string
  deviceName: string
  startedAt: string
  endedAt: string
  pointCount: number
  points: MapRoutePoint[]
}

export interface MapPhotoItem extends PhotoTimelineItem {
  latitude: number
  longitude: number
}

export interface MapResponse {
  date: string
  timezone: string
  rangeStart: string
  rangeEnd: string
  routes: MapRoute[]
  placeVisits: PlaceVisitTimelineItem[]
  photos: MapPhotoItem[]
}

export interface StatisticsTotals {
  usageMs: number
  sessionCount: number
  appCount: number
}

export interface StatisticsPlatformTotal {
  platform: Platform
  usageMs: number
  sessionCount: number
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
  platformTotals: StatisticsPlatformTotal[]
  items: StatisticsAppItem[]
}

export type ActivityWatchCollectorState =
  'disabled' | 'idle' | 'queued' | 'running' | 'needs_attention'

export interface ActivityWatchStatusResponse {
  enabled: boolean
  detailMode: 'app_only' | 'titles' | 'web'
  state: ActivityWatchCollectorState
  lastResult: string | null
  lastAttemptAt: string | null
  lastSuccessAt: string | null
  completedThrough: string | null
  nextAttemptAt: string | null
  webDetailsAvailable: boolean
}

export interface ActivityWatchImportTriggerResponse {
  accepted: boolean
  state: 'queued' | 'running' | 'idle'
}

export interface ApiErrorBody {
  error?: {
    code?: string
    message?: string
    field?: string
  }
}
