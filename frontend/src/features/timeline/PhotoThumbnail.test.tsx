import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'

import type { PhotoTimelineItem } from '../../api/types'
import { PhotoThumbnail } from './PhotoThumbnail'

const photo: PhotoTimelineItem = {
  type: 'photo',
  id: '01J00000000000000000001401',
  deviceId: '01J00000000000000000001001',
  deviceName: 'Demo Android A',
  source: 'android_media_store',
  takenAt: '2026-09-03T00:30:00.000Z',
  filename: 'IMG_20260903_093000.jpg',
  mimeType: 'image/jpeg',
  width: 4032,
  height: 3024,
  latitude: null,
  longitude: null,
  thumbnailUrl: '/api/v1/media/01J00000000000000000001401/thumbnail',
}

afterEach(cleanup)

describe('PhotoThumbnail', () => {
  it('uses the API URL as a lazy image with explicit dimensions', () => {
    render(<PhotoThumbnail photo={photo} />)

    const image = screen.getByRole('img', {
      name: 'IMG_20260903_093000.jpgの写真サムネイル',
    })
    expect(image).toHaveAttribute('src', photo.thumbnailUrl)
    expect(image).toHaveAttribute('loading', 'lazy')
    expect(image).toHaveAttribute('width', '4032')
    expect(image).toHaveAttribute('height', '3024')
  })

  it('shows a local placeholder when the thumbnail is metadata-only', () => {
    render(<PhotoThumbnail photo={{ ...photo, thumbnailUrl: null }} />)

    expect(
      screen.getByRole('img', {
        name: 'IMG_20260903_093000.jpgのサムネイルはありません',
      }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('img', { name: /写真サムネイル/ })).toBeNull()
  })

  it('shows a local placeholder for an external thumbnail URL', () => {
    render(
      <PhotoThumbnail
        photo={{ ...photo, thumbnailUrl: 'https://images.example/photo.webp' }}
      />,
    )

    expect(
      screen.getByRole('img', {
        name: 'IMG_20260903_093000.jpgのサムネイルを読み込めません',
      }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('img', { name: /写真サムネイル/ })).toBeNull()
  })

  it('replaces only the failed image with an accessible placeholder', () => {
    render(<PhotoThumbnail photo={photo} />)
    fireEvent.error(
      screen.getByRole('img', {
        name: 'IMG_20260903_093000.jpgの写真サムネイル',
      }),
    )

    expect(
      screen.getByRole('img', {
        name: 'IMG_20260903_093000.jpgのサムネイルを読み込めません',
      }),
    ).toBeInTheDocument()
  })
})
