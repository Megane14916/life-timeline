"""Identifiers used by the normalized data model."""

from __future__ import annotations

import re
import secrets
import time

ULID_LENGTH = 26
_CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
_ULID_PATTERN = re.compile(r"^[0-7][0-9A-HJKMNP-TV-Z]{25}$")


class InvalidUlidError(ValueError):
    """Raised when an identifier is not a canonical ULID."""


def validate_ulid(value: str, *, field_name: str = "id") -> str:
    """Validate and return a canonical uppercase ULID."""

    if not isinstance(value, str) or _ULID_PATTERN.fullmatch(value) is None:
        raise InvalidUlidError(f"{field_name} must be a canonical 26-character ULID.")
    return value


def _encode_base32(value: int, length: int) -> str:
    encoded = ["0"] * length
    for index in range(length - 1, -1, -1):
        value, remainder = divmod(value, 32)
        encoded[index] = _CROCKFORD_ALPHABET[remainder]
    return "".join(encoded)


def new_ulid() -> str:
    """Generate a canonical ULID using the current epoch milliseconds."""

    timestamp_ms = int(time.time() * 1000)
    if timestamp_ms >= 2**48:
        raise OverflowError("the current timestamp cannot be represented by a ULID")
    return _encode_base32(timestamp_ms, 10) + _encode_base32(secrets.randbits(80), 16)
