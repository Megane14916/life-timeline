"""Create privacy-safe JUnit summaries and reject empty or skipped test runs."""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from collections.abc import Sequence
from pathlib import Path


def _test_suites(root: ET.Element) -> list[ET.Element]:
    if root.tag == "testsuite":
        return [root]
    return list(root.findall(".//testsuite"))


def sanitize(paths: Sequence[Path]) -> tuple[ET.Element, dict[str, int]]:
    """Return a JUnit document and count summary with all source text removed."""

    output = ET.Element("testsuites")
    summary = {
        "files": 0,
        "tests": 0,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "result_code": 1,
    }
    suite_number = 0

    for path in paths:
        source_root = ET.parse(path).getroot()
        summary["files"] += 1
        for source_suite in _test_suites(source_root):
            cases = source_suite.findall("testcase")
            if not cases:
                continue

            suite_number += 1
            suite_name = f"suite-{suite_number:03d}"
            safe_suite = ET.SubElement(output, "testsuite", {"name": suite_name})
            suite_tests = 0
            suite_failures = 0
            suite_errors = 0
            suite_skipped = 0

            for case_number, source_case in enumerate(cases, start=1):
                suite_tests += 1
                safe_case = ET.SubElement(
                    safe_suite,
                    "testcase",
                    {"classname": suite_name, "name": f"case-{case_number:04d}"},
                )
                if source_case.find("failure") is not None:
                    ET.SubElement(safe_case, "failure", {"message": "test failed"})
                    suite_failures += 1
                if source_case.find("error") is not None:
                    ET.SubElement(safe_case, "error", {"message": "test error"})
                    suite_errors += 1
                if source_case.find("skipped") is not None:
                    ET.SubElement(safe_case, "skipped", {"message": "test skipped"})
                    suite_skipped += 1

            safe_suite.set("tests", str(suite_tests))
            safe_suite.set("failures", str(suite_failures))
            safe_suite.set("errors", str(suite_errors))
            safe_suite.set("skipped", str(suite_skipped))
            summary["tests"] += suite_tests
            summary["failures"] += suite_failures
            summary["errors"] += suite_errors
            summary["skipped"] += suite_skipped

    output.set("tests", str(summary["tests"]))
    output.set("failures", str(summary["failures"]))
    output.set("errors", str(summary["errors"]))
    output.set("skipped", str(summary["skipped"]))
    summary["result_code"] = int(
        summary["tests"] == 0
        or summary["failures"] > 0
        or summary["errors"] > 0
        or summary["skipped"] > 0
    )
    return output, summary


def _expand_inputs(inputs: Sequence[str], directories: Sequence[str]) -> list[Path]:
    paths: set[Path] = set()
    for value in inputs:
        path = Path(value)
        if path.is_file():
            paths.add(path)
    for value in directories:
        directory = Path(value)
        if directory.is_dir():
            paths.update(path for path in directory.rglob("*.xml") if path.is_file())
    return sorted(paths)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", action="append", default=[], help="JUnit XML file")
    parser.add_argument(
        "--input-dir", action="append", default=[], help="directory of JUnit XML files"
    )
    parser.add_argument("--output", required=True, help="sanitized JUnit output path")
    parser.add_argument(
        "--summary", required=True, help="safe JSON summary output path"
    )
    arguments = parser.parse_args(argv)

    paths = _expand_inputs(arguments.input, arguments.input_dir)
    try:
        document, summary = sanitize(paths)
    except (ET.ParseError, OSError):
        print("Unable to read test result XML.", file=sys.stderr)
        return 1

    output_path = Path(arguments.output)
    summary_path = Path(arguments.summary)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(document).write(output_path, encoding="utf-8", xml_declaration=True)
    summary_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")

    if summary["tests"] == 0:
        print("No executed tests were found in the test result XML.", file=sys.stderr)
    elif summary["skipped"] > 0:
        print("Skipped tests are not accepted by the CI gate.", file=sys.stderr)
    elif summary["failures"] > 0 or summary["errors"] > 0:
        print("The test result XML reports failed tests.", file=sys.stderr)
    return summary["result_code"]


if __name__ == "__main__":
    raise SystemExit(main())
