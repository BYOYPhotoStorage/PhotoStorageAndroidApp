#!/usr/bin/env bash
# test-iterate.sh
# Core iteration logic for build/test verification loops
#
# Usage:
#   source scripts/test-iterate.sh
#   run_build_loop "./gradlew build" "5" "20" "/path/to/reports"
#   run_test_loop "./gradlew test" "5" "20" "/path/to/reports"

set -o pipefail

# -- Configuration --
readonly MAX_OUTPUT_CONTEXT=8000     # Characters of output to send to Claude
readonly MAX_FAILED_TESTS=20         # Max failing tests to list in prompt
readonly CLAUDE_PERMISSIONS="--dangerously-skip-permissions"
readonly CLAUDE_TOOLS="--allowedTools Read,Edit,Write,Bash"
readonly CLAUDE_FLAGS="--bare --output-format json"

# -- Colors --
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# ==============================================================
# run_build_loop: Iterate until build passes or max attempts
# ==============================================================
run_build_loop() {
    local cmd="$1"
    local max_iterations="$2"
    local claude_max_turns="$3"
    local report_dir="$4"

    mkdir -p "$report_dir"

    echo ""
    echo "==============================================================="
    echo "  BUILD VERIFICATION LOOP"
    echo "  Command: $cmd"
    echo "  Max iterations: $max_iterations"
    echo "==============================================================="

    local iteration=0
    local build_output=""

    while [ $iteration -lt "$max_iterations" ]; do
        iteration=$((iteration + 1))

        echo ""
        echo "--- Build attempt $iteration/$max_iterations ---"

        local output_file="$report_dir/build-attempt-$iteration.txt"

        # Run build command
        if eval "$cmd" 2>&1 | tee "$output_file"; then
            echo -e "${GREEN}Build passed on attempt $iteration${NC}"
            echo "iterations=$iteration" >> "$GITHUB_OUTPUT"
            echo "status=passed" >> "$GITHUB_OUTPUT"
            return 0
        fi

        echo -e "${RED}Build failed on attempt $iteration${NC}"

        # Extract last N characters of output for context
        build_output=$(tail -c "$MAX_OUTPUT_CONTEXT" "$output_file" 2>/dev/null || cat "$output_file")

        # Escape special characters for the prompt
        local escaped_output
        escaped_output=$(echo "$build_output" | sed 's/\\/\\\\/g; s/"/\\"/g; s/`/\\`/g' | tail -c 6000)

        # Build fix prompt
        local fix_prompt="""The build failed. Here are the errors:

\`\`\`
$escaped_output
\`\`\`

Fix the compilation errors. Rules:
1. Only fix the actual errors -- don't refactor unrelated code
2. After fixing, the build should pass
3. Make minimal changes
4. Do NOT modify test files for compilation errors
"""

        echo "Asking Claude to fix build errors..."

        local fix_result_file="$report_dir/fix-attempt-$iteration.json"

        # Export ANTHROPIC_BASE_URL for Claude CLI
        export ANTHROPIC_BASE_URL="${ANTHROPIC_BASE_URL:-https://api.kimi.com/coding/}"

        if ! echo "$fix_prompt" | claude -p --stdio \
            $CLAUDE_PERMISSIONS \
            $CLAUDE_TOOLS \
            --max-turns "$claude_max_turns" \
            $CLAUDE_FLAGS \
            2>&1 | tee "$fix_result_file"; then
            echo -e "${YELLOW}Claude fix attempt failed or hit turn limit${NC}"
        fi

        # Check for common indicators that Claude gave up
        if grep -q "I need your help" "$fix_result_file" 2>/dev/null || \
           grep -q "I cannot fix" "$fix_result_file" 2>/dev/null; then
            echo -e "${YELLOW}Claude reported it cannot fix this automatically${NC}"
        fi

        echo "--- Re-trying build ---"
    done

    echo -e "${RED}Build failed after $max_iterations attempts${NC}"
    echo "iterations=$max_iterations" >> "$GITHUB_OUTPUT"
    echo "status=failed" >> "$GITHUB_OUTPUT"

    # Save final build output as artifact
    if [ -f "$report_dir/build-attempt-$max_iterations.txt" ]; then
        cp "$report_dir/build-attempt-$max_iterations.txt" "$report_dir/final-build-output.txt"
    fi

    exit 1
}

# ==============================================================
# run_test_loop: Iterate until all tests pass or max attempts
# ==============================================================
run_test_loop() {
    local cmd="$1"
    local max_iterations="$2"
    local claude_max_turns="$3"
    local report_dir="$4"

    mkdir -p "$report_dir"

    echo ""
    echo "==============================================================="
    echo "  TEST VERIFICATION LOOP"
    echo "  Command: $cmd"
    echo "  Max iterations: $max_iterations"
    echo "==============================================================="

    local iteration=0
    local test_output=""

    while [ $iteration -lt "$max_iterations" ]; do
        iteration=$((iteration + 1))

        echo ""
        echo "--- Test attempt $iteration/$max_iterations ---"

        local output_file="$report_dir/test-attempt-$iteration.txt"

        # Run test command
        if eval "$cmd" 2>&1 | tee "$output_file"; then
            echo -e "${GREEN}All tests passed on attempt $iteration${NC}"
            echo "iterations=$iteration" >> "$GITHUB_OUTPUT"
            echo "status=passed" >> "$GITHUB_OUTPUT"
            return 0
        fi

        echo -e "${RED}Tests failed on attempt $iteration${NC}"

        # Parse failing tests
        local failed_tests
        failed_tests=$(parse_failed_tests "$output_file")

        local failed_count
        failed_count=$(echo "$failed_tests" | wc -l)

        echo "   Found $failed_count failing test(s)"
        echo "$failed_tests" | head -$MAX_FAILED_TESTS | while read -r test; do
            echo "   - $test"
        done

        # Extract test output for context
        test_output=$(tail -c "$MAX_OUTPUT_CONTEXT" "$output_file" 2>/dev/null || cat "$output_file")

        local escaped_output
        escaped_output=$(echo "$test_output" | sed 's/\\/\\\\/g; s/"/\\"/g; s/`/\\`/g' | tail -c 6000)

        # Build targeted fix prompt
        local failed_tests_section=""
        if [ -n "$failed_tests" ]; then
            local limited_tests
            limited_tests=$(echo "$failed_tests" | head -$MAX_FAILED_TESTS | tr '\n' ',' | sed 's/,$//')
            failed_tests_section="Focus on these failing tests: $limited_tests
Run only these tests after fixing: $cmd --tests $(echo "$failed_tests" | head -5 | paste -sd' --tests ' -)"
        fi

        local fix_prompt="""Tests failed. Here are the errors:

\`\`\`
$escaped_output
\`\`\`

$failed_tests_section

Fix the PRODUCTION CODE to make these tests pass. Critical rules:
1. Do NOT modify test files unless the test itself is clearly wrong
2. Fix the actual production code that causes the test to fail
3. After fixing, run the tests again to verify
4. Make minimal changes -- don't refactor unrelated code
"""

        echo "Asking Claude to fix test failures..."

        local fix_result_file="$report_dir/test-fix-$iteration.json"

        # Export ANTHROPIC_BASE_URL for Claude CLI
        export ANTHROPIC_BASE_URL="${ANTHROPIC_BASE_URL:-https://api.kimi.com/coding/}"

        if ! echo "$fix_prompt" | claude -p --stdio \
            $CLAUDE_PERMISSIONS \
            $CLAUDE_TOOLS \
            --max-turns "$claude_max_turns" \
            $CLAUDE_FLAGS \
            2>&1 | tee "$fix_result_file"; then
            echo -e "${YELLOW}Claude fix attempt failed or hit turn limit${NC}"
        fi

        echo "--- Re-trying tests ---"
    done

    echo -e "${RED}Tests failed after $max_iterations attempts${NC}"
    echo "iterations=$max_iterations" >> "$GITHUB_OUTPUT"
    echo "status=failed" >> "$GITHUB_OUTPUT"

    # Save final test output
    if [ -f "$report_dir/test-attempt-$max_iterations.txt" ]; then
        cp "$report_dir/test-attempt-$max_iterations.txt" "$report_dir/final-test-output.txt"
    fi

    exit 1
}

# ==============================================================
# parse_failed_tests: Extract failing test names from Gradle output
# ==============================================================
parse_failed_tests() {
    local output_file="$1"

    if [ ! -f "$output_file" ]; then
        echo ""
        return
    fi

    # Pattern 1: Gradle test output (e.g. "com.example.MyTest > testMethod FAILED")
    grep -oP '^\s*(\S+)\s+>\s+(\S+)\s+FAILED' "$output_file" 2>/dev/null | \
        sed 's/^\s*//' | sort -u

    # Pattern 2: JUnit output (e.g. "Test com.example.MyTest.testMethod FAILED")
    grep -oP 'Test\s+(\S+\.(\w+))\s+FAILED' "$output_file" 2>/dev/null | \
        sed 's/Test //; s/ FAILED//' | sort -u

    # Pattern 3: Android Gradle Plugin output
    grep -oP '(\S+Test)\s+FAILED' "$output_file" 2>/dev/null | \
        sed 's/ FAILED//' | sort -u

    # Pattern 4: Test summary lines
    grep -oP '\d+ tests completed, \d+ failed' "$output_file" 2>/dev/null
}

# ==============================================================
# log_iteration_metric: Send metrics to your observability stack
# (Optional -- implement with your monitoring tool)
# ==============================================================
log_iteration_metric() {
    local phase="$1"      # "build" or "test"
    local iteration="$2"
    local status="$3"     # "passed" or "failed"
    local duration_ms="$4"

    # Example: Send to DataDog, Grafana, or custom API
    # curl -s -X POST "$METRICS_ENDPOINT" \
    #   -H "Authorization: Bearer $METRICS_API_KEY" \
    #   -d "{
    #     \"metric\": \"claude_wrapper.$phase.iterations\",
    #     \"points\": [[$(date +%s), $iteration]],
    #     \"tags\": [\"status:$status\", \"repo:${GITHUB_REPOSITORY}\"]
    #   }" || true

    echo "[METRIC] $phase iteration=$iteration status=$status duration=${duration_ms}ms"
}
