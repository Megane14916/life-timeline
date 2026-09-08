from pathlib import Path

import pytest

from app.config import ConfigurationError, Settings, resolve_data_dir


def test_explicit_data_dir_is_resolved_to_an_absolute_path(tmp_path: Path) -> None:
    relative_path = tmp_path / ".." / tmp_path.name / "demo-data"

    resolved = resolve_data_dir(str(relative_path))

    assert resolved.is_absolute()
    assert resolved == (tmp_path / "demo-data").resolve()


def test_settings_normalize_the_database_path() -> None:
    settings = Settings(data_dir=Path("data") / "demo")

    assert settings.data_dir.is_absolute()
    assert settings.database_path == settings.data_dir / "lifelog.db"


def test_default_data_dir_uses_local_app_data_on_windows() -> None:
    resolved = resolve_data_dir(None, environ={"LOCALAPPDATA": r"C:\Users\demo\AppData\Local"})

    # The platform-specific branch is exercised only when the test process is Windows.
    if __import__("os").name == "nt":
        assert resolved == Path(r"C:\Users\demo\AppData\Local\life-timeline").resolve()
    else:
        assert resolved.is_absolute()


def test_empty_data_dir_is_rejected() -> None:
    with pytest.raises(ConfigurationError, match="must not be empty"):
        Settings.from_env({"LIFE_TIMELINE_DATA_DIR": "   "})


def test_non_positive_sqlite_timeout_is_rejected(tmp_path: Path) -> None:
    with pytest.raises(ConfigurationError, match="greater than zero"):
        Settings(data_dir=tmp_path, sqlite_timeout_seconds=0)
