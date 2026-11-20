#!/usr/bin/env bash
# Checks scripts/release.sh (#155) in temporary git repositories: what it refuses, and that a release commits the
# version, tags it and pushes both. Runs in `./gradlew check` (:gradle-plugin:releaseScriptTest).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FAILED=0 PASSED=0
T="$(mktemp -d "${TMPDIR:-/tmp}/logast-release-test.XXXXXX")"
trap 'rm -rf "$T"' EXIT
export HOME="$T/home" GIT_CONFIG_NOSYSTEM=1 RELEASE_SKIP_CI=1
mkdir -p "$HOME"
git config --global user.email test@example.com
git config --global user.name test
git config --global init.defaultBranch main

expect() {   # $1: what, $2: 0 = must succeed / 1 = must fail, $3: text the output must contain, then the version
  local what="$1" want="$2" text="$3" out rc
  shift 3
  out="$(cd "$T/work" && bash "$ROOT/scripts/release.sh" "$@" 2>&1)"; rc=$?
  if { [ "$want" = 0 ] && [ $rc -eq 0 ]; } || { [ "$want" = 1 ] && [ $rc -ne 0 ]; }; then
    case "$out" in *"$text"*) PASSED=$((PASSED + 1)); return ;; esac
  fi
  FAILED=$((FAILED + 1)); printf 'FAIL %s (exit %s)\n  %s\n' "$what" "$rc" "$out"
}

git init --quiet --bare "$T/origin.git"
git clone --quiet "$T/origin.git" "$T/work" 2>/dev/null
(cd "$T/work" && printf 'group=dev.logast\nversion=0.1.0\n' > gradle.properties && git add . && git commit --quiet -m init && git push --quiet origin main)

expect "a version that is not x.y.z" 1 "give the version as x.y.z" 1.2
expect "no version" 1 "give the version as x.y.z"
(cd "$T/work" && echo x > dirty.txt)
expect "uncommitted changes" 1 "uncommitted changes" 0.2.0
(cd "$T/work" && rm dirty.txt && git commit --quiet --allow-empty -m local)
expect "main not pushed" 1 "not the same as origin/main" 0.2.0
(cd "$T/work" && git push --quiet origin main)
(cd "$T/work" && git checkout --quiet -b feature)
expect "not on main" 1 "not on main" 0.2.0
(cd "$T/work" && git checkout --quiet main)

expect "a release" 0 "released v0.2.0" 0.2.0
check() { if [ "$2" = "$3" ]; then PASSED=$((PASSED + 1)); else FAILED=$((FAILED + 1)); printf 'FAIL %s\n  expected: [%s]\n  actual:   [%s]\n' "$1" "$2" "$3"; fi; }
check "the version is committed" "version=0.2.0" "$(git -C "$T/origin.git" show main:gradle.properties | grep '^version=')"
check "the tag is pushed, on the release commit" "$(git -C "$T/origin.git" rev-parse main)" "$(git -C "$T/origin.git" rev-parse 'v0.2.0^{commit}')"
expect "the same version again" 1 "v0.2.0 exists already" 0.2.0
expect "a release candidate" 0 "released v0.3.0-rc.1" 0.3.0-rc.1

printf 'release.sh: %d passed, %d failed\n' "$PASSED" "$FAILED"
[ "$FAILED" -eq 0 ]
