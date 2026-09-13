"""Version 1 LocationPoint synchronization contract."""

from __future__ import annotations

import math
from typing import Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StrictFloat,
    StrictInt,
    field_validator,
    model_validator,
)

from app.ids import InvalidUlidError, validate_ulid


class LocationSyncPolicy:
    """Limits shared by the Android client and the location sync API contract."""

    MAX_LOCATIONS_PER_BATCH = 200
    MAX_REQUEST_BYTES = 262_144
    SOURCE = "android_fused_location"


class LocationSyncModel(BaseModel):
    """Base model that rejects fields outside the versioned wire contract."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)


def _validate_ulid(value: str) -> str:
    try:
        return validate_ulid(value)
    except InvalidUlidError as error:
        raise ValueError(str(error)) from error


def _validate_non_blank(value: str) -> str:
    if not value.strip():
        raise ValueError("must not be blank")
    return value


def _validate_finite(value: float | int | None, field_name: str) -> float | int | None:
    if value is not None and not math.isfinite(value):
        raise ValueError(f"{field_name} must be finite")
    return value


class LocationSyncDevice(LocationSyncModel):
    id: str = Field(min_length=26, max_length=26)
    name: str = Field(min_length=1, max_length=200)
    platform: Literal["android"]

    _validate_id = field_validator("id")(_validate_ulid)
    _validate_name = field_validator("name")(_validate_non_blank)


class LocationSyncLocation(LocationSyncModel):
    id: str = Field(min_length=26, max_length=26)
    recorded_at_ms: StrictInt = Field(alias="recordedAtMs", ge=0)
    latitude: StrictFloat | StrictInt
    longitude: StrictFloat | StrictInt
    accuracy_m: StrictFloat | StrictInt | None = Field(alias="accuracyM", default=None)
    altitude_m: StrictFloat | StrictInt | None = Field(alias="altitudeM", default=None)
    speed_mps: StrictFloat | StrictInt | None = Field(alias="speedMps", default=None)
    source: Literal["android_fused_location"]

    _validate_id = field_validator("id")(_validate_ulid)

    @field_validator("latitude", "longitude", "accuracy_m", "altitude_m", "speed_mps")
    @classmethod
    def validate_finite(cls, value: float | int | None, info: object) -> float | int | None:
        return _validate_finite(value, str(info))

    @field_validator("latitude")
    @classmethod
    def validate_latitude(cls, value: float | int) -> float | int:
        if not -90 <= value <= 90:
            raise ValueError("latitude is out of range")
        return value

    @field_validator("longitude")
    @classmethod
    def validate_longitude(cls, value: float | int) -> float | int:
        if not -180 <= value <= 180:
            raise ValueError("longitude is out of range")
        return value

    @field_validator("accuracy_m")
    @classmethod
    def validate_accuracy(cls, value: float | int | None) -> float | int | None:
        if value is not None and value < 0:
            raise ValueError("accuracyM must be nonnegative")
        return value

    @field_validator("speed_mps")
    @classmethod
    def validate_speed(cls, value: float | int | None) -> float | int | None:
        if value is not None and value < 0:
            raise ValueError("speedMps must be nonnegative")
        return value


class LocationSyncRequest(LocationSyncModel):
    schema_version: Literal[1] = Field(alias="schemaVersion")
    device: LocationSyncDevice
    locations: list[LocationSyncLocation] = Field(
        max_length=LocationSyncPolicy.MAX_LOCATIONS_PER_BATCH
    )

    @model_validator(mode="after")
    def validate_unique_location_ids(self) -> LocationSyncRequest:
        location_ids = [location.id for location in self.locations]
        if len(location_ids) != len(set(location_ids)):
            raise ValueError("locations must not contain duplicate IDs")
        return self


class LocationSyncResponse(LocationSyncModel):
    schema_version: Literal[1] = Field(alias="schemaVersion")
    accepted: list[str] = Field(max_length=LocationSyncPolicy.MAX_LOCATIONS_PER_BATCH)

    _validate_accepted_ids = field_validator("accepted")(
        lambda values: [_validate_ulid(value) for value in values]
    )

    @field_validator("accepted")
    @classmethod
    def validate_unique_accepted_ids(cls, values: list[str]) -> list[str]:
        if len(values) != len(set(values)):
            raise ValueError("accepted must not contain duplicate IDs")
        return values


def validate_accepted_ids(request: LocationSyncRequest, response: LocationSyncResponse) -> None:
    """Require an ordered subset ACK; omitted IDs stay pending on Android."""

    request_ids = [location.id for location in request.locations]
    request_positions = {location_id: index for index, location_id in enumerate(request_ids)}
    if any(location_id not in request_positions for location_id in response.accepted):
        raise ValueError("accepted IDs must be a subset of the request")
    positions = [request_positions[location_id] for location_id in response.accepted]
    if positions != sorted(positions):
        raise ValueError("accepted IDs must preserve request order")
