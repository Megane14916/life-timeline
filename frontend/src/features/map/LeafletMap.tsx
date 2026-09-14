import { useEffect, useRef, useState } from 'react'
import * as L from 'leaflet'
import 'leaflet/dist/leaflet.css'

import type { MapPhotoItem, MapResponse, MapRoute } from '../../api/types'
import { formatTime } from '../timeline/format'
import {
  OPEN_STREET_MAP_ATTRIBUTION,
  OPEN_STREET_MAP_TILE_URL,
} from './tileProvider'

export type TileStatus = 'idle' | 'loading' | 'loaded' | 'error'

interface LeafletMapProps {
  data: MapResponse
  onlineBasemapEnabled: boolean
  showRoutes: boolean
  showPlaces: boolean
  showPhotos: boolean
  selectedVisitId: string | null
  onTileStatusChange: (status: TileStatus) => void
}

interface OverlayGroups {
  routes: L.LayerGroup
  places: L.LayerGroup
  photos: L.LayerGroup
  visitMarkers: Map<string, L.CircleMarker>
}

const DEVICE_COLORS = ['#246b5a', '#4f5fa8', '#8b4f91', '#316d91', '#765f2d']

function deviceColor(deviceId: string): string {
  let hash = 0
  for (const character of deviceId) {
    hash = (hash * 31 + character.charCodeAt(0)) >>> 0
  }
  return DEVICE_COLORS[hash % DEVICE_COLORS.length]
}

function popupText(title: string, details: string[]): HTMLElement {
  const root = document.createElement('div')
  root.className = 'map-popup'
  const heading = document.createElement('strong')
  heading.textContent = title
  root.append(heading)
  for (const detail of details) {
    const line = document.createElement('p')
    line.textContent = detail
    root.append(line)
  }
  return root
}

function localThumbnailUrl(photo: MapPhotoItem): string | null {
  if (photo.thumbnailUrl === null) return null
  try {
    const url = new URL(photo.thumbnailUrl, window.location.origin)
    return url.origin === window.location.origin &&
      !url.username &&
      !url.password
      ? `${url.pathname}${url.search}${url.hash}`
      : null
  } catch {
    return null
  }
}

function photoPopup(photo: MapPhotoItem, timezone: string): HTMLElement {
  const root = popupText(photo.filename, [
    `${formatTime(photo.takenAt, timezone)} · ${photo.deviceName}`,
    `${photo.latitude.toFixed(5)}, ${photo.longitude.toFixed(5)}`,
  ])
  const thumbnailUrl = localThumbnailUrl(photo)
  if (thumbnailUrl === null) {
    const note = document.createElement('p')
    note.textContent = 'サムネイルはありません。'
    root.append(note)
  } else {
    const image = document.createElement('img')
    image.className = 'map-popup-thumbnail'
    image.src = thumbnailUrl
    image.alt = `${photo.filename}の写真サムネイル`
    image.loading = 'lazy'
    image.decoding = 'async'
    root.append(image)
  }
  return root
}

function addRoute(
  group: L.LayerGroup,
  route: MapRoute,
  segmentIndex: number,
  timezone: string,
  positions: L.LatLngExpression[],
): void {
  const routePositions = route.points.map((point) => {
    const position: L.LatLngExpression = [point.latitude, point.longitude]
    positions.push(position)
    return position
  })
  const color = deviceColor(route.deviceId)
  const label = `${route.deviceName} · ${route.pointCount}点 · ${formatTime(route.startedAt, timezone)}–${formatTime(route.endedAt, timezone)}`
  const popup = popupText(`${route.deviceName}の移動経路`, [label])

  if (routePositions.length === 1) {
    const point = routePositions[0]
    if (point === undefined) return
    L.circleMarker(point, {
      color,
      fillColor: color,
      fillOpacity: 1,
      radius: 7,
      weight: 2,
    })
      .bindPopup(popup)
      .addTo(group)
    return
  }

  L.polyline(routePositions, {
    color,
    dashArray: segmentIndex % 2 === 0 ? undefined : '9 7',
    lineCap: 'round',
    lineJoin: 'round',
    opacity: 0.88,
    weight: 5,
  })
    .bindPopup(popup)
    .addTo(group)
}

export function LeafletMap({
  data,
  onlineBasemapEnabled,
  showRoutes,
  showPlaces,
  showPhotos,
  selectedVisitId,
  onTileStatusChange,
}: LeafletMapProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<L.Map | null>(null)
  const groupsRef = useRef<OverlayGroups | null>(null)
  const [mapReady, setMapReady] = useState(false)

  useEffect(() => {
    const container = containerRef.current
    if (container === null) return

    const map = L.map(container, {
      attributionControl: true,
      keyboard: true,
      scrollWheelZoom: false,
    })
    const groups: OverlayGroups = {
      routes: L.layerGroup(),
      places: L.layerGroup(),
      photos: L.layerGroup(),
      visitMarkers: new Map(),
    }
    mapRef.current = map
    groupsRef.current = groups
    setMapReady(true)

    return () => {
      map.remove()
      mapRef.current = null
      groupsRef.current = null
      setMapReady(false)
    }
  }, [])

  useEffect(() => {
    const map = mapRef.current
    const groups = groupsRef.current
    if (!mapReady || map === null || groups === null) return

    groups.routes.clearLayers()
    groups.places.clearLayers()
    groups.photos.clearLayers()
    groups.visitMarkers.clear()
    const positions: L.LatLngExpression[] = []
    const segmentsByDevice = new Map<string, number>()

    for (const route of data.routes) {
      const segmentIndex = segmentsByDevice.get(route.deviceId) ?? 0
      segmentsByDevice.set(route.deviceId, segmentIndex + 1)
      addRoute(groups.routes, route, segmentIndex, data.timezone, positions)
    }

    for (const visit of data.placeVisits) {
      const center: L.LatLngExpression = [
        visit.centerLatitude,
        visit.centerLongitude,
      ]
      positions.push(center)
      if (visit.radiusM > 0) {
        const radius = L.circle(center, {
          color: '#9a6a24',
          fillColor: '#f0c777',
          fillOpacity: 0.13,
          radius: visit.radiusM,
          weight: 2,
        })
        groups.places.addLayer(radius)
        const bounds = L.latLng(center).toBounds(visit.radiusM * 2)
        positions.push(bounds.getNorthWest(), bounds.getSouthEast())
      }
      const marker = L.circleMarker(center, {
        color: '#805518',
        fillColor: '#f0c777',
        fillOpacity: 1,
        radius: 8,
        weight: 2,
      }).bindPopup(
        popupText('滞在地点', [
          visit.deviceName,
          `${formatTime(visit.display.startedAt, data.timezone)}–${formatTime(visit.display.endedAt, data.timezone)} · ${visit.display.durationMs ? Math.round(visit.display.durationMs / 60_000) : 0}分`,
          `${visit.centerLatitude.toFixed(5)}, ${visit.centerLongitude.toFixed(5)} · 半径 約${Math.round(visit.radiusM)}m`,
        ]),
      )
      groups.places.addLayer(marker)
      groups.visitMarkers.set(visit.id, marker)
    }

    for (const photo of data.photos) {
      const position: L.LatLngExpression = [photo.latitude, photo.longitude]
      positions.push(position)
      L.circleMarker(position, {
        color: '#9c493d',
        fillColor: '#ef9a79',
        fillOpacity: 1,
        radius: 7,
        weight: 2,
      })
        .bindPopup(photoPopup(photo, data.timezone))
        .addTo(groups.photos)
    }

    const syncGroup = (group: L.LayerGroup, visible: boolean) => {
      if (visible && !map.hasLayer(group)) group.addTo(map)
      if (!visible && map.hasLayer(group)) map.removeLayer(group)
    }
    syncGroup(groups.routes, showRoutes)
    syncGroup(groups.places, showPlaces)
    syncGroup(groups.photos, showPhotos)

    if (positions.length === 1) {
      map.setView(positions[0], 15)
    } else if (positions.length > 1) {
      map.fitBounds(L.latLngBounds(positions), {
        maxZoom: 16,
        padding: [32, 32],
      })
    }
    if (selectedVisitId !== null) {
      const selectedMarker = groups.visitMarkers.get(selectedVisitId)
      const selectedVisit = data.placeVisits.find(
        (visit) => visit.id === selectedVisitId,
      )
      if (selectedMarker !== undefined && selectedVisit !== undefined) {
        map.flyTo(
          [selectedVisit.centerLatitude, selectedVisit.centerLongitude],
          Math.max(map.getZoom(), 15),
          { animate: false },
        )
        selectedMarker.openPopup()
      }
    }
  }, [data, mapReady, selectedVisitId, showPhotos, showPlaces, showRoutes])

  useEffect(() => {
    const map = mapRef.current
    if (!mapReady || map === null) return
    if (!onlineBasemapEnabled) {
      onTileStatusChange('idle')
      return
    }

    onTileStatusChange('loading')
    let hasTileError = false
    const layer = L.tileLayer(OPEN_STREET_MAP_TILE_URL, {
      attribution: OPEN_STREET_MAP_ATTRIBUTION,
      maxZoom: 19,
    })
    const onLoading = () => {
      hasTileError = false
      onTileStatusChange('loading')
    }
    const onLoad = () => onTileStatusChange(hasTileError ? 'error' : 'loaded')
    const onError = () => {
      hasTileError = true
      onTileStatusChange('error')
    }
    layer.on('loading', onLoading)
    layer.on('load', onLoad)
    layer.on('tileerror', onError)
    layer.addTo(map)

    return () => {
      layer.off('loading', onLoading)
      layer.off('load', onLoad)
      layer.off('tileerror', onError)
      if (map.hasLayer(layer)) map.removeLayer(layer)
      onTileStatusChange('idle')
    }
  }, [mapReady, onlineBasemapEnabled, onTileStatusChange])

  return (
    <div
      ref={containerRef}
      className="leaflet-map-canvas"
      aria-label="選択日の位置情報地図。経路、滞在地点、写真地点を表示します。"
      data-testid="leaflet-map"
    />
  )
}
