"""Daily Map response assembly from normalized local facts."""

from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy.orm import Session

from app.repositories.map import LocationPointWithDevice, find_map_location_points, find_map_photos
from app.repositories.queries import PhotoWithDevice, PlaceVisitWithDevice, find_place_visits
from app.schemas import (
    MapPhotoItem,
    MapResponse,
    MapRoute,
    MapRoutePoint,
    PlaceVisitTimelineItem,
    TimelineDisplay,
)
from app.services.photos import to_photo_timeline_item
from app.services.time_range import (
    QueryRange,
    build_day_range,
    clip_interval,
    format_epoch_ms,
)

MAX_ROUTE_GAP_MS = 30 * 60 * 1000


@dataclass(frozen=True, slots=True)
class _RouteSegment:
    rows: tuple[LocationPointWithDevice, ...]


def _segments(rows: list[LocationPointWithDevice]) -> list[_RouteSegment]:
    segments: list[_RouteSegment] = []
    current: list[LocationPointWithDevice] = []
    current_device_id: str | None = None

    def finish() -> None:
        nonlocal current
        if current:
            segments.append(_RouteSegment(tuple(current)))
        current = []

    for row in rows:
        point = row.location_point
        if current and (
            row.device.id != current_device_id
            or point.recorded_at_ms - current[-1].location_point.recorded_at_ms > MAX_ROUTE_GAP_MS
        ):
            finish()
        current_device_id = row.device.id
        current.append(row)
    finish()
    return segments


def _to_route(segment: _RouteSegment) -> MapRoute:
    first = segment.rows[0]
    points = [row.location_point for row in segment.rows]
    route_points: list[MapRoutePoint] = []
    for point in points:
        accuracy = point.accuracy_m
        assert accuracy is not None
        route_points.append(
            MapRoutePoint(
                recordedAt=format_epoch_ms(point.recorded_at_ms),
                latitude=point.latitude,
                longitude=point.longitude,
                accuracyM=accuracy,
            )
        )
    return MapRoute(
        deviceId=first.device.id,
        deviceName=first.device.name,
        startedAt=format_epoch_ms(points[0].recorded_at_ms),
        endedAt=format_epoch_ms(points[-1].recorded_at_ms),
        pointCount=len(points),
        points=route_points,
    )


def _to_place_visit(row: PlaceVisitWithDevice, query_range: QueryRange) -> PlaceVisitTimelineItem:
    visit = row.place_visit
    clipped = clip_interval(visit.started_at_ms, visit.ended_at_ms, query_range)
    return PlaceVisitTimelineItem(
        type="place_visit",
        id=visit.id,
        deviceId=visit.device_id,
        deviceName=row.device.name,
        startedAt=format_epoch_ms(visit.started_at_ms),
        endedAt=format_epoch_ms(visit.ended_at_ms),
        durationMs=visit.duration_ms,
        centerLatitude=visit.center_latitude,
        centerLongitude=visit.center_longitude,
        radiusM=visit.radius_m,
        pointCount=visit.point_count,
        label="滞在地点",
        display=TimelineDisplay(
            startedAt=format_epoch_ms(clipped.start_ms),
            endedAt=format_epoch_ms(clipped.end_ms),
            durationMs=clipped.duration_ms,
            continuesFromPreviousDay=clipped.continues_from_previous_day,
            continuesToNextDay=clipped.continues_to_next_day,
            endsAtDayBoundary=clipped.ends_at_day_boundary,
        ),
    )


def _to_map_photo(row: PhotoWithDevice) -> MapPhotoItem:
    photo = to_photo_timeline_item(row)
    return MapPhotoItem(**photo.model_dump())


def get_daily_map(
    session: Session, date_value: str | None, timezone_name: str | None
) -> MapResponse:
    date_string, query_range = build_day_range(date_value, timezone_name)
    return MapResponse(
        date=date_string,
        timezone=query_range.timezone_name,
        rangeStart=query_range.start_iso,
        rangeEnd=query_range.end_iso,
        routes=[
            _to_route(segment)
            for segment in _segments(find_map_location_points(session, query_range))
        ],
        placeVisits=[
            _to_place_visit(row, query_range) for row in find_place_visits(session, query_range)
        ],
        photos=[_to_map_photo(row) for row in find_map_photos(session, query_range)],
    )
