"""Application configuration shared by the API, CLI, and database layer."""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

DATA_DIR_ENV = "LIFE_TIMELINE_DATA_DIR"
DEFAULT_DATA_DIRECTORY_NAME = "life-timeline"
DEFAULT_SQLITE_TIMEOUT_SECONDS = 30.0


class ConfigurationError(ValueError):
    """Raised when the application cannot resolve its runtime configuration."""


def _default_data_dir(environ: Mapping[str, str]) -> Path:
    if os.name == "nt":
        local_app_data = environ.get("LOCALAPPDATA")
        if not local_app_data:
            raise ConfigurationError(
                "LOCALAPPDATA is not set; set LIFE_TIMELINE_DATA_DIR to an absolute path."
            )
        return Path(local_app_data) / DEFAULT_DATA_DIRECTORY_NAME

    xdg_data_home = environ.get("XDG_DATA_HOME")
    if xdg_data_home:
        return Path(xdg_data_home) / DEFAULT_DATA_DIRECTORY_NAME
    return Path.home() / ".local" / "share" / DEFAULT_DATA_DIRECTORY_NAME


def resolve_data_dir(raw_value: str | None, *, environ: Mapping[str, str] | None = None) -> Path:
    """Resolve the configured data directory without creating it."""

    environment = os.environ if environ is None else environ
    if raw_value is None:
        candidate = _default_data_dir(environment)
    elif not raw_value.strip():
        raise ConfigurationError(f"{DATA_DIR_ENV} must not be empty.")
    else:
        candidate = Path(raw_value).expanduser()

    return candidate.resolve()


@dataclass(frozen=True, slots=True)
class Settings:
    """Resolved settings used by all PC-side processes."""

    data_dir: Path
    sqlite_timeout_seconds: float = DEFAULT_SQLITE_TIMEOUT_SECONDS

    def __post_init__(self) -> None:
        if self.sqlite_timeout_seconds <= 0:
            raise ConfigurationError("sqlite_timeout_seconds must be greater than zero.")
        object.__setattr__(self, "data_dir", self.data_dir.expanduser().resolve())

    @property
    def database_path(self) -> Path:
        return self.data_dir / "lifelog.db"

    @property
    def thumbnail_dir(self) -> Path:
        """Return the one managed directory used for server-side thumbnails."""

        return self.data_dir / "thumbnails"

    @classmethod
    def from_env(cls, environ: Mapping[str, str] | None = None) -> Settings:
        environment = os.environ if environ is None else environ
        data_dir = resolve_data_dir(environment.get(DATA_DIR_ENV), environ=environment)
        return cls(data_dir=data_dir)


def get_settings() -> Settings:
    """Load settings for the current process."""

    return Settings.from_env()
