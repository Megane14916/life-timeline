"""Safe, read-only ActivityWatch integration boundaries."""

from app.activitywatch.client import ActivityWatchClient
from app.activitywatch.discovery import (
    ActivityWatchDiscovery,
    discover_buckets,
)
from app.activitywatch.errors import (
    ActivityWatchError,
    ActivityWatchProtocolError,
    ActivityWatchUnavailableError,
)
from app.activitywatch.schemas import (
    ActivityWatchBucket,
    ActivityWatchEvent,
    ActivityWatchInfo,
)

__all__ = [
    "ActivityWatchBucket",
    "ActivityWatchClient",
    "ActivityWatchDiscovery",
    "ActivityWatchError",
    "ActivityWatchEvent",
    "ActivityWatchInfo",
    "ActivityWatchProtocolError",
    "ActivityWatchUnavailableError",
    "discover_buckets",
]
