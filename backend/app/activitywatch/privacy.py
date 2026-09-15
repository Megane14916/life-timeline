"""ActivityWatch title, URL, browser, and privacy-mode normalization."""

from __future__ import annotations

import re
import unicodedata
from urllib.parse import urlsplit, urlunsplit

from app.repositories import RepositoryValidationError

PRIVACY_MODES = frozenset(("app_only", "titles", "web"))
URL_POLICY_VERSION = "activitywatch_url_v1"
TITLE_POLICY_VERSION = "activitywatch_title_v1"
MAX_TITLE_LENGTH = 500
MAX_URL_LENGTH = 2048
BROWSER_IDENTIFIERS: dict[str, frozenset[str]] = {
    "chrome": frozenset(("chrome", "chrome.exe", "chromium", "chromium.exe")),
    "edge": frozenset(("edge", "msedge", "msedge.exe")),
    "firefox": frozenset(("firefox", "firefox.exe")),
    "brave": frozenset(("brave", "brave.exe")),
    "opera": frozenset(("opera", "opera.exe")),
    "vivaldi": frozenset(("vivaldi", "vivaldi.exe")),
}
_CONTROL_PATTERN = re.compile(r"[\x00-\x1f\x7f-\x9f]")


def _has_control(value: str) -> bool:
    return bool(_CONTROL_PATTERN.search(value)) or any(
        unicodedata.category(character) in {"Cc", "Cf"} for character in value
    )


def _remove_controls(value: str) -> str:
    return "".join(
        character for character in value if unicodedata.category(character) not in {"Cc", "Cf"}
    )


def validate_privacy_mode(mode: str) -> str:
    if mode not in PRIVACY_MODES:
        raise RepositoryValidationError("privacy_mode is unsupported.")
    return mode


def normalize_app_identifier(value: str) -> str:
    """Normalize an ActivityWatch app label without guessing or trimming paths."""

    if not isinstance(value, str) or _has_control(value):
        raise RepositoryValidationError("ActivityWatch app label is invalid.")
    normalized = re.sub(r"\s+", " ", unicodedata.normalize("NFKC", value).strip()).casefold()
    if not normalized or len(normalized) > 255:
        raise RepositoryValidationError("ActivityWatch app label is invalid.")
    return normalized


def display_app_name(value: str) -> str:
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", value)).strip()


def is_browser_identifier(identifier: str) -> bool:
    return any(identifier in identifiers for identifiers in BROWSER_IDENTIFIERS.values())


def sanitize_title(value: object) -> tuple[str | None, int]:
    if not isinstance(value, str):
        return None, 0
    cleaned = _remove_controls(unicodedata.normalize("NFKC", value)).strip()
    if len(cleaned) <= MAX_TITLE_LENGTH:
        return cleaned or None, 0
    return cleaned[:MAX_TITLE_LENGTH], len(cleaned) - MAX_TITLE_LENGTH


def sanitize_url(value: object, *, incognito: bool = False) -> str | None:
    """Return a plain HTTP(S) URL with credentials, query, fragment, and default port removed."""

    if incognito or not isinstance(value, str) or not value or len(value) > MAX_URL_LENGTH:
        return None
    if _has_control(value) or any(character.isspace() for character in value):
        return None
    try:
        parsed = urlsplit(value)
        scheme = parsed.scheme.casefold()
        hostname = parsed.hostname
        port = parsed.port
    except ValueError:
        return None
    if scheme not in {"http", "https"} or hostname is None:
        return None
    if parsed.username is not None or parsed.password is not None:
        # Credentials are discarded; the hostname and path remain safe to retain.
        pass
    try:
        ascii_hostname = hostname.encode("idna").decode("ascii").casefold()
    except UnicodeError:
        return None
    if not ascii_hostname:
        return None
    if port is not None and port not in {80 if scheme == "http" else 443}:
        host_part = f"[{ascii_hostname}]" if ":" in ascii_hostname else ascii_hostname
        netloc = f"{host_part}:{port}"
    else:
        netloc = f"[{ascii_hostname}]" if ":" in ascii_hostname else ascii_hostname
    sanitized = urlunsplit((scheme, netloc, parsed.path, "", ""))
    return sanitized if len(sanitized) <= MAX_URL_LENGTH else None
