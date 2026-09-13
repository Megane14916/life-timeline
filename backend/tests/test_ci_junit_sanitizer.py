"""Privacy and execution gates for CI test reports."""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

SCRIPT_PATH = Path(__file__).parents[2] / "scripts" / "ci" / "sanitize_junit.py"


def _run_sanitizer(
    tmp_path: Path, source: str
) -> tuple[subprocess.CompletedProcess[str], str, dict[str, int]]:
    source_path = tmp_path / "source.xml"
    output_path = tmp_path / "safe.xml"
    summary_path = tmp_path / "safe.json"
    source_path.write_text(source, encoding="utf-8")
    result = subprocess.run(
        [
            sys.executable,
            str(SCRIPT_PATH),
            "--input",
            str(source_path),
            "--output",
            str(output_path),
            "--summary",
            str(summary_path),
        ],
        check=False,
        capture_output=True,
        text=True,
    )
    output = output_path.read_text(encoding="utf-8") if output_path.exists() else ""
    summary = json.loads(summary_path.read_text(encoding="utf-8")) if summary_path.exists() else {}
    return result, output, summary


def test_sanitizer_strips_failure_details_and_private_photo_metadata(tmp_path: Path) -> None:
    private_values = [
        "private-camera-original.jpg",
        "01K4N70E3Q6N9D6E6G0C8M2H1P",
        "5c6b7be15722d51b915b1fab25d3f8d42e37fe294a57028516a3eb6ae1bd0be8",
        "35.1234,139.9876",
        "content://media/external/images/media/101",
        "C:\\Users\\private\\Pictures\\camera.jpg",
        "private-device.example.invalid",
    ]
    xml = (
        '<testsuites tests="1" failures="1"><testsuite name="private-suite" tests="1" failures="1">'
        '<testcase classname="private-class" name="private-case"><failure message="'
        + " ".join(private_values)
        + '">'
        + " ".join(private_values)
        + "</failure></testcase><system-out>"
        + " ".join(private_values)
        + "</system-out></testsuite></testsuites>"
    )

    result, output, summary = _run_sanitizer(tmp_path, xml)

    assert result.returncode == 1
    assert summary == {
        "files": 1,
        "tests": 1,
        "failures": 1,
        "errors": 0,
        "skipped": 0,
        "result_code": 1,
    }
    assert 'message="test failed"' in output
    assert all(value not in output for value in private_values)
    assert "private-suite" not in output
    assert "private-class" not in output


def test_sanitizer_rejects_skipped_tests(tmp_path: Path) -> None:
    result, _output, summary = _run_sanitizer(
        tmp_path,
        '<testsuite tests="1" skipped="1">'
        '<testcase name="not-safe"><skipped/></testcase></testsuite>',
    )

    assert result.returncode == 1
    assert summary["tests"] == 1
    assert summary["skipped"] == 1
    assert summary["result_code"] == 1


def test_sanitizer_rejects_empty_result_files(tmp_path: Path) -> None:
    result, _output, summary = _run_sanitizer(
        tmp_path,
        '<testsuites tests="0" failures="0"/>',
    )

    assert result.returncode == 1
    assert summary["tests"] == 0
    assert summary["result_code"] == 1
