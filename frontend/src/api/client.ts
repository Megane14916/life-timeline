import type {
  ApiErrorBody,
  PhotosResponse,
  StatisticsResponse,
  TimelineResponse,
} from './types'

export class ApiClientError extends Error {
  readonly status: number
  readonly code: string
  readonly field?: string

  constructor(status: number, code: string, message: string, field?: string) {
    super(message)
    this.name = 'ApiClientError'
    this.status = status
    this.code = code
    this.field = field
  }
}

function isApiErrorBody(value: unknown): value is ApiErrorBody {
  return typeof value === 'object' && value !== null && 'error' in value
}

async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, { signal })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error
    }
    throw new ApiClientError(0, 'network_error', 'APIに接続できませんでした。')
  }

  let body: unknown
  try {
    body = await response.json()
  } catch {
    body = undefined
  }

  if (!response.ok) {
    const apiError = isApiErrorBody(body) ? body.error : undefined
    throw new ApiClientError(
      response.status,
      apiError?.code ?? 'http_error',
      apiError?.message ?? 'APIの取得に失敗しました。',
      apiError?.field,
    )
  }

  return body as T
}

export function getTimeline(
  date: string,
  timezone: string,
  signal?: AbortSignal,
): Promise<TimelineResponse> {
  const params = new URLSearchParams({ date, timezone })
  return getJson<TimelineResponse>(
    `/api/v1/timeline?${params.toString()}`,
    signal,
  )
}

export function getPhotos(
  date: string,
  timezone: string,
  signal?: AbortSignal,
): Promise<PhotosResponse> {
  const params = new URLSearchParams({ date, timezone })
  return getJson<PhotosResponse>(`/api/v1/photos?${params.toString()}`, signal)
}

export function getAppStatistics(
  from: string,
  to: string,
  timezone: string,
  signal?: AbortSignal,
): Promise<StatisticsResponse> {
  const params = new URLSearchParams({ from, to, timezone })
  return getJson<StatisticsResponse>(
    `/api/v1/stats/apps?${params.toString()}`,
    signal,
  )
}
