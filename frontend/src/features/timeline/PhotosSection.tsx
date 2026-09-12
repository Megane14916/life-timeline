import type { PhotosResponse } from '../../api/types'
import { formatTime } from './format'
import { PhotoThumbnail } from './PhotoThumbnail'

interface PhotosSectionProps {
  data: PhotosResponse | null
  error: string | null
  loading: boolean
  onRetry: () => void
}

function PhotoGridCard({
  photo,
  timezone,
}: {
  photo: PhotosResponse['items'][number]
  timezone: string
}) {
  return (
    <li>
      <article className="photo-grid-card" data-testid="photo-grid-card">
        <div className="photo-thumbnail-frame">
          <PhotoThumbnail photo={photo} />
        </div>
        <div className="photo-grid-details">
          <h3>{photo.filename}</h3>
          <time
            dateTime={photo.takenAt}
            aria-label={`撮影時刻 ${formatTime(photo.takenAt, timezone)}`}
          >
            {formatTime(photo.takenAt, timezone)}
          </time>
          <p>{photo.deviceName}</p>
          <p>
            {photo.width === null || photo.height === null
              ? 'サイズ不明'
              : `${photo.width} × ${photo.height}px`}
          </p>
        </div>
      </article>
    </li>
  )
}

export function PhotosSection({
  data,
  error,
  loading,
  onRetry,
}: PhotosSectionProps) {
  return (
    <section className="photos-section panel" aria-labelledby="photos-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Selected day</p>
          <h2 id="photos-title">写真一覧</h2>
        </div>
        {data !== null && <span className="data-source">Photos API</span>}
      </div>

      {loading && (
        <p className="panel-status" role="status">
          写真を読み込んでいます…
        </p>
      )}
      {error !== null && (
        <div className="error-state" role="alert" data-testid="photos-error">
          <strong>写真一覧の取得に失敗しました</strong>
          <p>{error}</p>
          <button type="button" onClick={onRetry}>
            再試行
          </button>
        </div>
      )}
      {!loading &&
        error === null &&
        data !== null &&
        (data.items.length === 0 ? (
          <p className="empty-state photos-empty">この日の写真はありません。</p>
        ) : (
          <ul
            className="photo-grid"
            aria-label={`${data.date}の写真一覧`}
            data-testid="photo-grid"
          >
            {data.items.map((photo) => (
              <PhotoGridCard
                key={photo.id}
                photo={photo}
                timezone={data.timezone}
              />
            ))}
          </ul>
        ))}
    </section>
  )
}
