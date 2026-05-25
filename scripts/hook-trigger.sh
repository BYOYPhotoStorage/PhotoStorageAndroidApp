#!/bin/bash
# UserPromptSubmit hook: runs the app on device when trigger phrases are detected.

INPUT=$(cat)

# Extract prompt text from the hook input JSON.
# Tries common keys; falls back to raw input if jq is unavailable.
PROMPT=$(echo "$INPUT" | jq -r '.prompt // .user_prompt // .text // empty' 2>/dev/null || echo "$INPUT")

# Check for trigger phrases (case-insensitive).
if echo "$PROMPT" | grep -qiE 'run on device|deploy to device|launch on device|run the app|build and run'; then
    bash scripts/run-device.sh
fi
