export function formatDuration(durationMs: number): string {
  if (durationMs === 0) {
    return '0分'
  }

  const totalSeconds = Math.floor(durationMs / 1000)
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60

  if (hours > 0) {
    return minutes > 0 ? `${hours}時間${minutes}分` : `${hours}時間`
  }
  if (minutes > 0) {
    return seconds > 0 ? `${minutes}分${seconds}秒` : `${minutes}分`
  }
  if (seconds > 0) {
    return `${seconds}秒`
  }
  return `${durationMs}ms`
}

export function formatTime(value: string, timezone: string): string {
  return new Intl.DateTimeFormat('ja-JP', {
    hour: '2-digit',
    minute: '2-digit',
    timeZone: timezone,
  }).format(new Date(value))
}

export function formatPlatform(platform: 'android' | 'windows'): string {
  return platform === 'android' ? 'Android' : 'Windows'
}
