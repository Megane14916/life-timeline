"""Versioned Android AppSession synchronization contracts."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, StrictInt, field_validator, model_validator

from app.ids import InvalidUlidError, validate_ulid


class SyncModel(BaseModel):
    """Base model that rejects unknown fields in the versioned wire contract."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)


def _validate_id(value: str) -> str:
    try:
        return validate_ulid(value)
    except InvalidUlidError as error:
        raise ValueError(str(error)) from error


def _validate_non_blank(value: str) -> str:
    if not value.strip():
        raise ValueError("must not be blank")
    return value


class SyncDevice(SyncModel):
    id: str = Field(min_length=26, max_length=26)
    name: str = Field(min_length=1, max_length=200)
    platform: Literal["android"]

    _validate_id = field_validator("id")(_validate_id)
    _validate_name = field_validator("name")(_validate_non_blank)


class SyncApp(SyncModel):
    id: str = Field(min_length=26, max_length=26)
    identifier: str = Field(min_length=1, max_length=255)
    display_name: str = Field(alias="displayName", min_length=1, max_length=255)

    _validate_id = field_validator("id")(_validate_id)
    _validate_identifier = field_validator("identifier")(_validate_non_blank)
    _validate_display_name = field_validator("display_name")(_validate_non_blank)


class SyncAppSession(SyncModel):
    id: str = Field(min_length=26, max_length=26)
    app_id: str = Field(alias="appId", min_length=26, max_length=26)
    started_at_ms: StrictInt = Field(alias="startedAtMs", ge=0)
    ended_at_ms: StrictInt = Field(alias="endedAtMs", ge=0)
    duration_ms: StrictInt = Field(alias="durationMs", ge=0)
    source: Literal["android_usage_stats"]

    _validate_id = field_validator("id", "app_id")(_validate_id)

    @model_validator(mode="after")
    def validate_interval(self) -> SyncAppSession:
        if self.ended_at_ms <= self.started_at_ms:
            raise ValueError("endedAtMs must be after startedAtMs")
        if self.duration_ms != self.ended_at_ms - self.started_at_ms:
            raise ValueError("durationMs must equal endedAtMs - startedAtMs")
        return self


class SyncAppSessionsRequest(SyncModel):
    schema_version: Literal[1] = Field(alias="schemaVersion")
    device: SyncDevice
    apps: list[SyncApp] = Field(max_length=100)
    sessions: list[SyncAppSession] = Field(max_length=100)

    @model_validator(mode="after")
    def validate_batch_references(self) -> SyncAppSessionsRequest:
        app_ids = [app.id for app in self.apps]
        if len(app_ids) != len(set(app_ids)):
            raise ValueError("apps must not contain duplicate IDs")

        identifiers = [app.identifier for app in self.apps]
        if len(identifiers) != len(set(identifiers)):
            raise ValueError("apps must not contain duplicate identifiers")

        session_ids = [app_session.id for app_session in self.sessions]
        if len(session_ids) != len(set(session_ids)):
            raise ValueError("sessions must not contain duplicate IDs")

        referenced_app_ids = {app_session.app_id for app_session in self.sessions}
        if referenced_app_ids != set(app_ids):
            raise ValueError("apps must exactly match the sessions' app references")
        return self


class SyncAppSessionsResponse(SyncModel):
    schema_version: Literal[1] = Field(alias="schemaVersion")
    accepted: list[str]

    _validate_accepted_ids = field_validator("accepted")(
        lambda values: [_validate_id(value) for value in values]
    )
