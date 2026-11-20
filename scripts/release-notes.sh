#!/usr/bin/env bash
# The notes of a GitHub release (#155), from the commits since the previous version tag: changes go straight to main,
# so GitHub's own notes (built from pull requests) would be empty. Commits are grouped by their conventional prefix
# (feat, fix, docs; the rest under "Other"), each with the issues its message closes ("Fixes #n").
#
#   scripts/release-notes.sh v0.2.0 > notes.md
set -euo pipefail
tag="${1:?give the tag, e.g. v0.2.0}"
repo="${GITHUB_REPOSITORY:-magyar-attila-317/logast}"
prev="$(git describe --tags --abbrev=0 --match 'v[0-9]*' "$tag^" 2>/dev/null || true)"
range="${prev:+$prev..}$tag"

declare -a feat=() fix=() docs=() other=()
while IFS= read -r -d $'\x1e' entry; do
  entry="${entry#$'\n'}"
  [ -n "$entry" ] || continue
  sha="${entry%%$'\x1f'*}"; rest="${entry#*$'\x1f'}"
  subject="${rest%%$'\x1f'*}"; body="${rest#*$'\x1f'}"
  case "$subject" in "release: v"*) continue ;; esac
  issues="$(printf '%s\n' "$body" | grep -oiE '(fixes|closes|resolves) #[0-9]+' | grep -oE '#[0-9]+' | sort -u -t'#' -k2 -n | paste -sd' ' - || true)"
  text="$subject"
  type="$(printf '%s' "$subject" | sed -nE 's/^([a-z]+)(\([^)]*\))?!?: .*/\1/p')"
  scope="$(printf '%s' "$subject" | sed -nE 's/^[a-z]+\(([^)]*)\)!?: .*/\1/p')"
  [ -n "$type" ] && text="$(printf '%s' "$subject" | sed -E 's/^[a-z]+(\([^)]*\))?!?: //')"
  line="- ${scope:+**$scope**: }$text${issues:+ ($issues)} (${sha:0:7})"
  case "$type" in
    feat) feat+=("$line") ;;
    fix) fix+=("$line") ;;
    docs) docs+=("$line") ;;
    *) other+=("$line") ;;
  esac
done < <(git log --reverse --no-merges --format='%H%x1f%s%x1f%b%x1e' "$range")

section() {   # $1: title, then the lines
  local title="$1"; shift
  [ $# -gt 0 ] || return 0
  printf '## %s\n\n' "$title"; printf '%s\n' "$@"; printf '\n'
}
section "Features" "${feat[@]+"${feat[@]}"}"
section "Fixes" "${fix[@]+"${fix[@]}"}"
section "Documentation" "${docs[@]+"${docs[@]}"}"
section "Other" "${other[@]+"${other[@]}"}"
if [ -n "$prev" ]; then
  printf '**Full changelog**: https://github.com/%s/compare/%s...%s\n' "$repo" "$prev" "$tag"
else
  printf '**First release.**\n'
fi
