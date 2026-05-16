#!/usr/bin/env python3
"""
gradle-parser.py
Parses Gradle test/build output to extract structured failure information.
Used by test-iterate.sh for more sophisticated parsing.

Usage:
    python scripts/gradle-parser.py test-output.txt --format json
    python scripts/gradle-parser.py test-output.txt --format summary
    python scripts/gradle-parser.py test-output.txt --extract-failing-tests
"""

import re
import sys
import json
import argparse
from pathlib import Path
from dataclasses import dataclass, asdict
from typing import List, Optional


@dataclass
class TestFailure:
    """Represents a single test failure."""
    test_class: str
    test_method: str
    error_type: str
    error_message: str
    stack_trace: str
    file_path: Optional[str] = None
    line_number: Optional[int] = None


@dataclass
class BuildError:
    """Represents a compilation/build error."""
    file_path: str
    line_number: int
    error_message: str
    severity: str  # "error" or "warning"


@dataclass
class ParseResult:
    """Complete parse result for a build/test run."""
    success: bool
    phase: str  # "build" or "test"
    total_tests: int = 0
    passed_tests: int = 0
    failed_tests: int = 0
    skipped_tests: int = 0
    duration_ms: int = 0
    failures: List[TestFailure] = None
    build_errors: List[BuildError] = None
    raw_output_tail: str = ""

    def __post_init__(self):
        if self.failures is None:
            self.failures = []
        if self.build_errors is None:
            self.build_errors = []


class GradleOutputParser:
    """Parser for Gradle build and test output."""

    # Regex patterns for different failure types
    TEST_FAILURE_PATTERN = re.compile(
        r'^(\S+)\s+>\s+(\S+)\s+FAILED\s*\n'
        r'(?:.*?\n)?'
        r'\s+(\w+(?:\.\w+)*):\s+(.*?)\s*\n'
        r'((?:\s+at\s+.*\n)+)',
        re.MULTILINE | re.DOTALL
    )

    BUILD_ERROR_PATTERN = re.compile(
        r'^(\S+\.\w+):(\d+):\s*(error|warning):\s*(.*?)$',
        re.MULTILINE
    )

    SUMMARY_PATTERN = re.compile(
        r'(\d+) tests completed, '
        r'(\d+) failed, '
        r'(\d+) skipped'
    )

    KOTLIN_ERROR_PATTERN = re.compile(
        r'^e:\s*(.*?):(\d+):\d+\s*(.*?)$',
        re.MULTILINE
    )

    def parse(self, output: str, phase: str = "test") -> ParseResult:
        """Parse Gradle output and return structured result."""
        result = ParseResult(
            success="BUILD SUCCESSFUL" in output or
                    ("tests completed" in output and
                     self.SUMMARY_PATTERN.search(output) and
                     int(self.SUMMARY_PATTERN.search(output).group(2)) == 0),
            phase=phase,
            raw_output_tail=output[-6000:]  # Last 6K chars for context
        )

        if phase == "build":
            result.build_errors = self._parse_build_errors(output)
            result.success = len(result.build_errors) == 0
        else:
            result.failures = self._parse_test_failures(output)
            summary = self.SUMMARY_PATTERN.search(output)
            if summary:
                result.total_tests = int(summary.group(1))
                result.failed_tests = int(summary.group(2))
                result.passed_tests = result.total_tests - result.failed_tests
                result.skipped_tests = int(summary.group(3))
            result.success = result.failed_tests == 0

        return result

    def _parse_test_failures(self, output: str) -> List[TestFailure]:
        """Extract test failures from output."""
        failures = []
        for match in self.TEST_FAILURE_PATTERN.finditer(output):
            failures.append(TestFailure(
                test_class=match.group(1),
                test_method=match.group(2),
                error_type=match.group(3),
                error_message=match.group(4).strip(),
                stack_trace=match.group(5).strip()
            ))
        return failures

    def _parse_build_errors(self, output: str) -> List[BuildError]:
        """Extract compilation errors from output."""
        errors = []

        # Java errors
        for match in self.BUILD_ERROR_PATTERN.finditer(output):
            errors.append(BuildError(
                file_path=match.group(1),
                line_number=int(match.group(2)),
                severity=match.group(3),
                error_message=match.group(4).strip()
            ))

        # Kotlin errors
        for match in self.KOTLIN_ERROR_PATTERN.finditer(output):
            errors.append(BuildError(
                file_path=match.group(1),
                line_number=int(match.group(2)),
                severity="error",
                error_message=match.group(3).strip()
            ))

        return errors

    def extract_failing_test_classes(self, output: str) -> List[str]:
        """Get list of failing test class names for targeted re-run."""
        result = self.parse(output, "test")
        classes = set()
        for failure in result.failures:
            classes.add(failure.test_class)
        return sorted(classes)

    def generate_fix_prompt(self, output: str, phase: str = "test") -> str:
        """Generate a Claude prompt from parsed failures."""
        result = self.parse(output, phase)

        if phase == "build":
            error_list = "\n".join(
                f"- {e.file_path}:{e.line_number}: {e.error_message}"
                for e in result.build_errors[:10]
            )
            return f"""The build failed with these compilation errors:

{error_list}

Fix these errors. After fixing, the build must pass with `./gradlew build`."""

        else:
            test_list = "\n".join(
                f"- {f.test_class} > {f.test_method}: {f.error_type}: {f.error_message[:200]}"
                for f in result.failures[:15]
            )

            failing_classes = self.extract_failing_test_classes(output)
            test_filter = " ".join(f"--tests {c}" for c in failing_classes[:5])

            return f"""Tests failed. Failing tests:

{test_list}

Fix the PRODUCTION CODE (NOT the test files) to make these tests pass.
After fixing, run only the failing tests to verify:
`./gradlew test {test_filter}`

Make minimal changes. Don't refactor unrelated code."""


def main():
    parser = argparse.ArgumentParser(description='Parse Gradle output')
    parser.add_argument('file', help='Output file to parse')
    parser.add_argument('--format', choices=['json', 'summary'], default='summary')
    parser.add_argument('--phase', choices=['build', 'test'], default='test')
    parser.add_argument('--extract-failing-tests', action='store_true')
    parser.add_argument('--generate-prompt', action='store_true')

    args = parser.parse_args()

    output = Path(args.file).read_text()
    gradle_parser = GradleOutputParser()

    if args.extract_failing_tests:
        classes = gradle_parser.extract_failing_test_classes(output)
        for c in classes:
            print(c)
    elif args.generate_prompt:
        print(gradle_parser.generate_fix_prompt(output, args.phase))
    elif args.format == 'json':
        result = gradle_parser.parse(output, args.phase)
        print(json.dumps(asdict(result), indent=2, default=str))
    else:
        result = gradle_parser.parse(output, args.phase)
        print(f"Phase: {result.phase}")
        print(f"Success: {'Yes' if result.success else 'No'}")
        if result.phase == 'test':
            print(f"Tests: {result.passed_tests}/{result.total_tests} passed, "
                  f"{result.failed_tests} failed, {result.skipped_tests} skipped")
        else:
            print(f"Build errors: {len(result.build_errors)}")
        for f in result.failures[:5]:
            print(f"  FAIL: {f.test_class}.{f.test_method}")


if __name__ == '__main__':
    main()
