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
from app.activitywatch.importer import (
    ActivityWatchImportChunk,
    ActivityWatchImportClient,
    ActivityWatchImporter,
    ActivityWatchImportError,
    ActivityWatchImportResult,
    ActivityWatchImportSettings,
    ImportRangeError,
    TooManyEventsError,
    fetch_bucket_events,
    iter_utc_days,
    local_date_range_to_utc,
    utc_day_end_ms,
    utc_day_start_ms,
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
    "ActivityWatchImportChunk",
    "ActivityWatchImportClient",
    "ActivityWatchImportError",
    "ActivityWatchImportResult",
    "ActivityWatchImportSettings",
    "ActivityWatchImporter",
    "ActivityWatchInfo",
    "ActivityWatchProtocolError",
    "ActivityWatchUnavailableError",
    "BucketEvent",
    "ImportRangeError",
    "NormalizationDiagnostics",
    "NormalizedActivityWatch",
    "Period",
    "TooManyEventsError",
    "clip_period",
    "discover_buckets",
    "fetch_bucket_events",
    "intersect_periods",
    "is_browser_identifier",
    "iter_utc_days",
    "local_date_range_to_utc",
    "normalize_activitywatch",
    "normalize_app_identifier",
    "sanitize_title",
    "sanitize_url",
    "split_period",
    "union_periods",
    "utc_day_end_ms",
    "utc_day_start_ms",
]
