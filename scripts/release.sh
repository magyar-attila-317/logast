#!/usr/bin/env bash
# Releases logast (#155): sets the version in gradle.properties, commits, tags v<version> and pushes; the release
# workflow (.github/workflows/release.yml) then builds, tests and creates the GitHub release with the zip.
# Releases come at the end of a milestone, not after every change (docs/releasing.md).
#
#   scripts/release.sh 0.2.0            # on a clean main that is pushed and green on CI
#   RELEASE_SKIP_CI=1 scripts/release.sh 0.2.0   # without the CI check (the workflow still builds and tests)
set -euo pipefail

fail() { printf 'release: %s\n' "$1" >&2; exit 1; }

version="${1:-}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] || fail "give the version as x.y.z (or x.y.z-rc.1), not '${version}'"
tag="v$version"

[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || fail "not on main"
[ -z "$(git status --porcelain)" ] || fail "uncommitted changes: commit or stash them first"
git fetch --quiet origin main --tags
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || fail "main is not the same as origin/main: push or pull first"
! git rev-parse -q --verify "refs/tags/$tag" >/dev/null || fail "$tag exists already"

current="$(sed -n 's/^version=//p' gradle.properties)"
if [ "${RELEASE_SKIP_CI:-}" != 1 ]; then
  conclusion="$(gh run list --commit "$(git rev-parse HEAD)" --workflow build.yml --json conclusion -q '.[0].conclusion' 2>/dev/null || true)"
  [ "$conclusion" = success ] || fail "CI of $(git rev-parse --short HEAD) is '${conclusion:-not found}', not success: wait for it or fix it"
fi

if [ "$current" != "$version" ]; then
  sed -i.bak "s/^version=.*/version=$version/" gradle.properties && rm -f gradle.properties.bak
  # snapshots that name the starter's version (the dependency-mode scenarios) follow it, or the release's CI fails
  snapshots=()
  if [ -d testing/e2e ]; then
    while IFS= read -r f; do snapshots+=("$f"); done < <(grep -rl "dev.logast:logging-support:$current" testing/e2e || true)
  fi
  for f in "${snapshots[@]}"; do
    sed -i.bak "s/dev\.logast:logging-support:$current/dev.logast:logging-support:$version/g" "$f" && rm -f "$f.bak"
  done
  git commit --quiet -m "release: $tag" -- gradle.properties "${snapshots[@]}"
fi
git tag -a "$tag" -m "logast $version"
git push --quiet origin main "$tag"
echo "released $tag: the release workflow builds it and creates the GitHub release"
