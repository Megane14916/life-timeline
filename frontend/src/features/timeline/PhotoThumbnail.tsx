import { useState } from 'react'

import type { PhotoTimelineItem } from '../../api/types'

interface PhotoThumbnailProps {
  photo: PhotoTimelineItem
}

function sameOriginThumbnailUrl(value: string | null): string | null {
  if (value === null) return null
  try {
    const url = new URL(value, window.location.origin)
    return url.origin === window.location.origin &&
      !url.username &&
      !url.password
      ? value
      : null
  } catch {
    return null
  }
}

export function PhotoThumbnail({ photo }: PhotoThumbnailProps) {
  const source = sameOriginThumbnailUrl(photo.thumbnailUrl)
  const [failedSource, setFailedSource] = useState<string | null>(null)
  const failed = source !== null && failedSource === source

  if (source === null || failed) {
    const metadataOnly = photo.thumbnailUrl === null
    const label = metadataOnly
      ? `${photo.filename}のサムネイルはありません`
      : `${photo.filename}のサムネイルを読み込めません`
    const message = metadataOnly ? 'サムネイルなし' : '読み込み失敗'
    return (
      <div
        className="photo-thumbnail-placeholder"
        role="img"
        aria-label={label}
      >
        <span aria-hidden="true">{message}</span>
      </div>
    )
  }

  return (
    <img
      className="photo-thumbnail-image"
      src={source}
      alt={`${photo.filename}の写真サムネイル`}
      loading="lazy"
      decoding="async"
      width={photo.width ?? 512}
      height={photo.height ?? 512}
      onError={() => setFailedSource(source)}
    />
  )
}
