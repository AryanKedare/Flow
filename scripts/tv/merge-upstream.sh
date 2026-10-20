#!/usr/bin/env bash
set -euo pipefail

upstream_url="${FLOW_UPSTREAM_URL:-https://github.com/Flow-Tube/Flow.git}"
upstream_branch="${FLOW_UPSTREAM_BRANCH:-main}"
git fetch --no-tags "$upstream_url" "$upstream_branch"
upstream_sha="$(git rev-parse FETCH_HEAD)"

if git merge-base --is-ancestor "$upstream_sha" HEAD; then
  printf 'changed=false\n'
  exit 0
fi

before_merge="$(git rev-parse HEAD)"
if ! git merge --no-edit --no-ff --no-commit "$upstream_sha"; then
  git rev-parse -q --verify MERGE_HEAD > /dev/null || exit 1
fi

# The fork owns its CI; GITHUB_TOKEN cannot import upstream workflow definitions.
if git ls-files -- .github/workflows | grep -q .; then
  git restore --source="$before_merge" --staged --worktree -- .github/workflows
fi

if [ -n "$(git diff --name-only --diff-filter=U)" ]; then
  git merge --abort
  printf 'Upstream has conflicts with the TV edition. Main was not changed.\n' >&2
  exit 1
fi
git commit -m "chore(sync): merge Flow-Tube/Flow main"
printf 'changed=true\n'
