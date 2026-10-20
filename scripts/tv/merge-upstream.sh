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

if ! git merge --no-edit --no-ff "$upstream_sha" -m "chore(sync): merge Flow-Tube/Flow main"; then
  git merge --abort
  printf 'Upstream has conflicts with the TV edition. Main was not changed.\n' >&2
  exit 1
fi
printf 'changed=true\n'
