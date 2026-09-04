from __future__ import annotations

import json
import subprocess
import sys
import time
from collections.abc import Iterator
from contextlib import contextmanager
from http.client import HTTPConnection

HOST = "127.0.0.1"
PORT = 8000
HEALTH_PATH = "/api/v1/health"
STARTUP_TIMEOUT_SECONDS = 20.0


@contextmanager
def running_server() -> Iterator[subprocess.Popen[str]]:
    process = subprocess.Popen(
        [
            sys.executable,
            "-m",
            "uvicorn",
            "app.main:app",
            "--host",
            HOST,
            "--port",
            str(PORT),
        ],
        text=True,
    )
    try:
        yield process
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)


def request_health() -> tuple[int, dict[str, str]]:
    connection = HTTPConnection(HOST, PORT, timeout=2)
    try:
        connection.request("GET", HEALTH_PATH)
        response = connection.getresponse()
        payload = json.loads(response.read())
    finally:
        connection.close()

    if not isinstance(payload, dict) or not all(
        isinstance(key, str) and isinstance(value, str) for key, value in payload.items()
    ):
        raise TypeError("Health response must be a string map")

    return response.status, payload


def main() -> None:
    deadline = time.monotonic() + STARTUP_TIMEOUT_SECONDS

    with running_server() as process:
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(f"Uvicorn exited with code {process.returncode}")

            try:
                status, payload = request_health()
            except OSError:
                time.sleep(0.2)
                continue

            if status != 200 or payload != {"status": "ok"}:
                raise RuntimeError(
                    f"Unexpected health response: status={status}, payload={payload}"
                )

            print("Backend health smoke passed")
            return

    raise TimeoutError("Backend did not become healthy before the startup timeout")


if __name__ == "__main__":
    main()
