# PhotoStorage

Android app for backing up photos to cloud storage (S3-compatible).

## Claude Code CI/CD Wrapper

This repository includes an automated **Issue -> Fix -> Compile -> Test -> Iterate -> PR** pipeline powered by Claude Code.

### How It Works

When an issue is labeled with `ai-fix` or someone mentions `@claude` in a comment, the workflow:

1. **Initial Fix** -- Claude reads the issue and implements a minimal code fix
2. **Build Loop** -- Runs `./gradlew build` iteratively, asking Claude to fix compilation errors
3. **Test Loop** -- Runs `./gradlew test` iteratively, asking Claude to fix failing tests
4. **PR Creation** -- Only creates a PR when build and tests all pass

### Triggering the Workflow

| Method | How |
|--------|-----|
| Label an issue | Add the `ai-fix` label to any issue |
| Mention in comment | Write `@claude` in an issue comment |
| Manual run | Go to Actions > "Claude Android Fix" > Run workflow |

### Manual Dispatch Inputs

- **Issue number** -- Required. The GitHub issue to fix.
- **Max build iterations** -- How many times to retry fixing build errors (default: 5)
- **Max test iterations** -- How many times to retry fixing test failures (default: 5)

### Required Secrets

Configure these in **Settings > Secrets and variables > Actions**:

| Secret | Description |
|--------|-------------|
| `ANTHROPIC_API_KEY` | Your Anthropic API key for Claude Code |
| `GITHUB_TOKEN` | Automatically provided by GitHub Actions |

> **Note:** The workflow uses `ANTHROPIC_BASE_URL=https://api.kimi.com/coding/` as the API endpoint. This is configured automatically in the workflow.

### Workflow Permissions

The workflow requests these permissions:
- `contents: write` -- to commit fixes to a branch
- `pull-requests: write` -- to create the PR
- `issues: read` -- to read issue descriptions

### Branch Protection Recommendations

- Require at least 1 reviewer before merging AI-generated PRs
- Do NOT enable auto-merge for PRs with the `ai-generated` label
- The `needs-review` label is automatically applied to all AI PRs

### Cost Controls

| Setting | Value | Purpose |
|---------|-------|---------|
| `--max-budget-usd` | $10.00 | Per-run cost cap |
| `--max-turns` | 30 (initial), 20 (fix) | Limits per-Claude-invocation turns |
| Timeout | 90 minutes | GitHub Actions job timeout |

Typical cost ranges from $0.30 (simple fix, passes first try) to $3--7 (complex fix with multiple iterations).

### Files Added

| File | Purpose |
|------|---------|
| `.github/workflows/claude-android-fix.yml` | Main CI/CD workflow |
| `scripts/test-iterate.sh` | Build/test iteration logic |
| `scripts/gradle-parser.py` | Gradle output parser (optional) |
| `.claude/CLAUDE.md` | Project context for Claude |

### Testing the Workflow

1. Create a test issue with a simple, known bug (e.g., a typo in a method name)
2. Add the `ai-fix` label
3. Watch the Actions tab for the workflow run
4. Verify a PR is created only when build + tests pass
5. Check the PR has labels `ai-generated`, `needs-review`, `claude-fix`

### Disabling

To temporarily disable the workflow without deleting it, add a branch protection rule or comment out the trigger conditions in `.github/workflows/claude-android-fix.yml`.
