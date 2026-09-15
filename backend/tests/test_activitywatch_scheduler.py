from __future__ import annotations

import asyncio
from collections.abc import Callable

import httpx

from app.activitywatch import (
    ActivityWatchImportError,
    ActivityWatchImportResult,
    ActivityWatchImportScheduler,
    ActivityWatchRuntimeConfig,
    load_runtime_config,
)
from app.main import create_app


def _result(web_details_available: bool = True) -> ActivityWatchImportResult:
    return ActivityWatchImportResult(
        "aw1_source",
        "01J00000000000000000000001",
        (),
        False,
        False,
        web_details_available,
    )


class _FakeImporter:
    def __init__(self, action: Callable[[], ActivityWatchImportResult]) -> None:
        self.action = action

    def run(self) -> ActivityWatchImportResult:
        return self.action()


def test_runtime_config_is_opt_in_and_invalid_values_are_safe() -> None:
    assert load_runtime_config({}).enabled is False
    assert load_runtime_config({"LIFE_TIMELINE_ACTIVITYWATCH_ENABLED": "TRUE"}).enabled is True
    assert load_runtime_config(
        {
            "LIFE_TIMELINE_ACTIVITYWATCH_ENABLED": "true",
            "LIFE_TIMELINE_ACTIVITYWATCH_PRIVACY_MODE": "web",
        }
    ) == ActivityWatchRuntimeConfig(True, "web")
    invalid = load_runtime_config({"LIFE_TIMELINE_ACTIVITYWATCH_ENABLED": "yes"})
    assert invalid.enabled is False
    assert invalid.configuration_error == "invalid_config"


def test_scheduler_manual_trigger_is_unique_and_reports_success() -> None:
    calls: list[int] = []

    def factory() -> _FakeImporter:
        def action() -> ActivityWatchImportResult:
            calls.append(1)
            return _result()

        return _FakeImporter(action)

    async def scenario() -> None:
        scheduler = ActivityWatchImportScheduler(
            importer_factory=factory,
            enabled=True,
            initial_delay_seconds=0,
        )
        try:
            assert await scheduler.trigger_manual() is True
            assert await scheduler.trigger_manual() is False
            await asyncio.sleep(0)
            if scheduler._active_task is not None:
                await scheduler._active_task
            assert calls == [1]
            status = scheduler.status()
            assert status.state == "idle"
            assert status.last_result == "success"
            assert status.web_details_available is True
        finally:
            await scheduler.stop()

    asyncio.run(scenario())


def test_scheduler_uses_one_minute_transient_backoff_and_attention_for_permanent_failure() -> None:
    actions = iter(
        (
            lambda: (_ for _ in ()).throw(ActivityWatchImportError("unavailable", retryable=True)),
            lambda: (_ for _ in ()).throw(ActivityWatchImportError("incompatible_api")),
        )
    )

    def factory() -> _FakeImporter:
        return _FakeImporter(next(actions))

    async def scenario() -> None:
        scheduler = ActivityWatchImportScheduler(importer_factory=factory, enabled=True)
        try:
            await scheduler.run_once()
            assert scheduler._next_delay_seconds() == 60
            assert scheduler.status().state == "idle"
            await scheduler.run_once()
            status = scheduler.status()
            assert status.state == "needs_attention"
            assert status.last_result == "incompatible_api"
            assert scheduler._next_delay_seconds() == 900
        finally:
            await scheduler.stop()

    asyncio.run(scenario())


def test_disabled_status_and_manual_endpoint_never_start_import() -> None:
    async def request() -> tuple[httpx.Response, httpx.Response]:
        scheduler = ActivityWatchImportScheduler(enabled=False)
        application = create_app(activitywatch_scheduler=scheduler)
        transport = httpx.ASGITransport(app=application)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            status = await client.get("/api/v1/activitywatch/status")
            trigger = await client.post("/api/v1/activitywatch/import")
        return status, trigger

    status, trigger = asyncio.run(request())
    assert status.status_code == 200
    assert status.json() == {
        "enabled": False,
        "detailMode": "app_only",
        "state": "disabled",
        "lastResult": None,
        "lastAttemptAt": None,
        "lastSuccessAt": None,
        "completedThrough": None,
        "nextAttemptAt": None,
        "webDetailsAvailable": False,
    }
    assert trigger.status_code == 409
    assert trigger.json()["error"]["code"] == "disabled"
    assert "hostname" not in status.text
    assert "bucket" not in status.text


def test_manual_endpoint_returns_202_and_deduplicates_immediate_second_request() -> None:
    calls: list[int] = []

    def factory() -> _FakeImporter:
        def action() -> ActivityWatchImportResult:
            calls.append(1)
            return _result(False)

        return _FakeImporter(action)

    async def request() -> tuple[httpx.Response, httpx.Response]:
        scheduler = ActivityWatchImportScheduler(
            importer_factory=factory,
            enabled=True,
        )
        application = create_app(activitywatch_scheduler=scheduler)
        transport = httpx.ASGITransport(app=application)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            first = await client.post("/api/v1/activitywatch/import")
            second = await client.post("/api/v1/activitywatch/import")
            await asyncio.sleep(0)
            if scheduler._active_task is not None:
                await scheduler._active_task
        await scheduler.stop()
        return first, second

    first, second = asyncio.run(request())
    assert first.status_code == 202
    assert first.json()["accepted"] is True
    assert second.status_code == 409
    assert calls == [1]
