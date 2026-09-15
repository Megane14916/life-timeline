from __future__ import annotations

from pathlib import Path

import pytest

from app.activitywatch import (
    ActivityWatchImportChunk,
    ActivityWatchImportResult,
    NormalizationDiagnostics,
)
from app.cli.import_activitywatch import (
    ActivityWatchCliError,
    _build_parser,
    _range_ms,
    _resolve_settings,
    render_result,
)


def test_cli_parser_expands_local_range_and_enforces_exclusive_to() -> None:
    args = _build_parser().parse_args(
        [
            "--from",
            "2026-09-01",
            "--to",
            "2026-09-08",
            "--timezone",
            "Asia/Tokyo",
            "--privacy-mode",
            "titles",
            "--dry-run",
        ]
    )
    start_ms, end_ms = _range_ms(args)
    assert start_ms is not None
    assert end_ms is not None
    assert end_ms > start_ms
    assert args.dry_run is True


@pytest.mark.parametrize(
    "argv",
    [
        ["--from", "2026-09-01"],
        ["--from", "2026-09-01", "--to", "2026-10-03"],
        ["--from", "2026-09-02", "--to", "2026-09-01"],
    ],
)
def test_cli_rejects_invalid_backfill_range(argv: list[str]) -> None:
    args = _build_parser().parse_args(argv)
    with pytest.raises((ValueError, ActivityWatchCliError)):
        _range_ms(args)


def test_render_result_is_aggregate_only() -> None:
    result = ActivityWatchImportResult(
        "aw1_private-source",
        "01J00000000000000000000001",
        (
            ActivityWatchImportChunk(
                0,
                86_400_000,
                True,
                2,
                30_000,
                1,
                NormalizationDiagnostics(1, 2, 3),
                True,
            ),
        ),
        False,
        False,
    )
    rendered = render_result(result)
    assert rendered == (
        "ActivityWatch import: chunks=1, sessions=2, duration_ms=30000, "
        "invalid=1, redacted=2, truncated=3, dry_run=false, partial=false"
    )
    assert "private-source" not in rendered
    assert "01J000" not in rendered


def test_data_dir_override_requires_absolute_path() -> None:
    with pytest.raises(ActivityWatchCliError):
        _resolve_settings("relative-data")
    assert _resolve_settings(str(Path.cwd())).data_dir == Path.cwd().resolve()
