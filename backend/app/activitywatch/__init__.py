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
from app.activitywatch.normalizer import (
    ActivityWatchBucketEvent,
    BucketEvent,
    NormalizationDiagnostics,
    NormalizedActivityWatch,
    normalize_activitywatch,
)
from app.activitywatch.periods import (
    Period,
    clip_period,
    intersect_periods,
    split_period,
    union_periods,
)
from app.activitywatch.privacy import (
    is_browser_identifier,
    normalize_app_identifier,
    sanitize_title,
    sanitize_url,
)
from app.activitywatch.schemas import (
    ActivityWatchBucket,
    ActivityWatchEvent,
    ActivityWatchInfo,
)

__all__ = [
    "ActivityWatchBucket",
    "ActivityWatchBucketEvent",
    "ActivityWatchClient",
    "ActivityWatchDiscovery",
    "ActivityWatchError",
    "ActivityWatchEvent",
    "ActivityWatchInfo",
    "ActivityWatchProtocolError",
    "ActivityWatchUnavailableError",
    "BucketEvent",
    "NormalizationDiagnostics",
    "NormalizedActivityWatch",
    "Period",
    "clip_period",
    "discover_buckets",
    "intersect_periods",
    "is_browser_identifier",
    "normalize_activitywatch",
    "normalize_app_identifier",
    "sanitize_title",
    "sanitize_url",
    "split_period",
    "union_periods",
]
