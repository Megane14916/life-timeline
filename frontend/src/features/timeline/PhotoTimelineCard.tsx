import type { PhotoTimelineItem } from '../../api/types'
import { PhotoThumbnail } from './PhotoThumbnail'

interface PhotoTimelineCardProps {
  item: PhotoTimelineItem
}

function dimensions(item: PhotoTimelineItem): string {
  if (item.width === null || item.height === null) return 'サイズ不明'
  return `${item.width} × ${item.height}px`
}

export function PhotoTimelineCard({ item }: PhotoTimelineCardProps) {
  return (
    <article
      className="timeline-card photo-timeline-card"
      data-testid="photo-timeline-card"
    >
      <div className="timeline-card-heading">
        <div>
          <p className="photo-label">写真</p>
          <h3>{item.filename}</h3>
        </div>
      </div>
      <div className="photo-thumbnail-frame">
        <PhotoThumbnail photo={item} />
      </div>
      <dl className="photo-metadata">
        <div>
          <dt>撮影端末</dt>
          <dd>{item.deviceName}</dd>
        </div>
        <div>
          <dt>サイズ</dt>
          <dd>{dimensions(item)}</dd>
        </div>
      </dl>
    </article>
  )
}
