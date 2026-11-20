#!/usr/bin/env bash
# Checks install.sh's decisions (#130): what counts as set up, what is asked, what the user config remembers, and when a
# re-run has nothing to do. Every path is a temporary folder (HOME too): never the real ~/.gradle, ~/.m2 or ~/.config.
# Runs in `./gradlew check` (:gradle-plugin:installScriptTest); by hand: bash scripts/test-install.sh
# Keep it macOS bash 3.2 compatible, like install.sh.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../install.sh
. "$ROOT/install.sh"   # functions only: main runs when install.sh is executed, not sourced

FAILED=0 PASSED=0
check() {   # $1: what, $2: expected, $3: actual
  if [ "$2" = "$3" ]; then PASSED=$((PASSED + 1)); else FAILED=$((FAILED + 1)); printf 'FAIL %s\n  expected: [%s]\n  actual:   [%s]\n' "$1" "$2" "$3"; fi
}
holds() {   # $1: what, then the command that must succeed
  local what="$1"; shift
  if "$@"; then PASSED=$((PASSED + 1)); else FAILED=$((FAILED + 1)); printf 'FAIL %s\n' "$what"; fi
}
fails() {   # $1: what, then the command that must fail
  local what="$1"; shift
  if "$@"; then FAILED=$((FAILED + 1)); printf 'FAIL %s (succeeded)\n' "$what"; else PASSED=$((PASSED + 1)); fi
}

T="$(mktemp -d "${TMPDIR:-/tmp}/logast-install-test.XXXXXX")"
trap 'rm -rf "$T"' EXIT
export HOME="$T/home"
mkdir -p "$HOME"
unset GRADLE_USER_HOME XDG_CONFIG_HOME GRADLE_OPTS LOGAST_HOME LOGAST_BIN
pk() { mkdir -p "$(dirname "$1")"; printf 'PK\003\004rest' >"$1"; }   # enough of a zip for jar_ok
empty() { mkdir -p "$(dirname "$1")"; : >"$1"; }

# ── jar_ok ────────────────────────────────────────────────────────────────────────────────────────────────────────
fails "a missing jar" jar_ok "$T/none.jar"
empty "$T/empty.jar"; fails "an empty jar" jar_ok "$T/empty.jar"
printf 'not a zip' >"$T/text.jar"; fails "a jar that is no zip" jar_ok "$T/text.jar"
pk "$T/ok.jar"; holds "a zip" jar_ok "$T/ok.jar"

# ── m2_repo: where Gradle's mavenLocal() is ───────────────────────────────────────────────────────────────────────
mkdir -p "$T/checkout"
check "the default" "$HOME/.m2/repository" "$(m2_repo "$T/checkout")"
mkdir -p "$HOME/.m2"
printf '<settings>\n  <localRepository>${user.home}/m2-settings</localRepository>\n</settings>\n' >"$HOME/.m2/settings.xml"
check "settings.xml" "$HOME/m2-settings" "$(m2_repo "$T/checkout")"
printf 'systemProp.maven.repo.local=%s\n' "$T/m2-checkout" >"$T/checkout/gradle.properties"
check "the checkout's gradle.properties" "$T/m2-checkout" "$(m2_repo "$T/checkout")"
mkdir -p "$HOME/.gradle"
printf 'org.gradle.jvmargs=-Xmx1g\nsystemProp.maven.repo.local = %s \n' "$T/m2-home" >"$HOME/.gradle/gradle.properties"
check "the Gradle home's gradle.properties wins" "$T/m2-home" "$(m2_repo "$T/checkout")"
check "GRADLE_USER_HOME's gradle.properties" "$T/m2-checkout" "$(GRADLE_USER_HOME="$T/gh" m2_repo "$T/checkout")"
check "GRADLE_OPTS" "$T/m2-opts" "$(GRADLE_OPTS="-Xmx1g -Dmaven.repo.local=$T/m2-opts" m2_repo "$T/checkout")"

# ── gradle_state ──────────────────────────────────────────────────────────────────────────────────────────────────
M2="$T/m2"
INIT="$T/gradle/init.d/logast.gradle"
script() {   # $1: version
  mkdir -p "$(dirname "$INIT")"
  printf '%s: applies ...\ninitscript {\n    def version = '"'"'%s'"'"'\n}\n' "$INIT_MARKER" "$1" >"$INIT"
}
check "no init script" none "$(gradle_state 1.0 "$INIT" "$M2")"
mkdir -p "$(dirname "$INIT")"; printf '// someone else\n' >"$INIT"
check "someone else's init script" foreign "$(gradle_state 1.0 "$INIT" "$M2")"
check "someone else's init script has no version" "" "$(init_version "$INIT")"
script 1.0
check "the init script's version" 1.0 "$(init_version "$INIT")"
check "the init script without the plugin" stale "$(gradle_state 1.0 "$INIT" "$M2")"
empty "$M2/dev/logast/gradle-plugin/1.0/gradle-plugin-1.0.jar"
check "an empty plugin jar (an interrupted install)" stale "$(gradle_state 1.0 "$INIT" "$M2")"
pk "$M2/dev/logast/gradle-plugin/1.0/gradle-plugin-1.0.jar"
check "the plugin without cli and core" stale "$(gradle_state 1.0 "$INIT" "$M2")"
pk "$M2/dev/logast/cli/1.0/cli-1.0.jar"; empty "$M2/dev/logast/core/1.0/core-1.0.jar"
check "an empty core jar" stale "$(gradle_state 1.0 "$INIT" "$M2")"
pk "$M2/dev/logast/core/1.0/core-1.0.jar"
check "the plugin without facts (#152)" stale "$(gradle_state 1.0 "$INIT" "$M2")"
pk "$M2/dev/logast/facts/1.0/facts-1.0.jar"
check "set up for this version" current "$(gradle_state 1.0 "$INIT" "$M2")"
check "set up for an older version" stale "$(gradle_state 1.1 "$INIT" "$M2")"

# ── support_state ─────────────────────────────────────────────────────────────────────────────────────────────────
check "no support library" none "$(support_state 1.0 "$M2")"
empty "$M2/dev/logast/logging-support/1.0/logging-support-1.0.jar"
check "an empty support jar" stale "$(support_state 1.0 "$M2")"
pk "$M2/dev/logast/logging-support/1.0/logging-support-1.0.jar"
check "the support library of this version" current "$(support_state 1.0 "$M2")"
check "the support library of an older version" stale "$(support_state 1.1 "$M2")"
mkdir -p "$T/m2b/dev/logast/logging-support/0.9"
check "an older version's folder without a jar" none "$(support_state 1.0 "$T/m2b")"

# ── the user config: install.* lines, the rest kept ───────────────────────────────────────────────────────────────
CFG="$T/config/logast/config.yaml"
check "no config file" "" "$(cfg_get install.gradle "$CFG")"
cfg_set install.gradle no "$CFG"
check "a new config file" "install.gradle: no" "$(cat "$CFG")"
mkdir -p "$(dirname "$CFG")"
printf -- '---\nfleets:\n  paths:\n    shop: "/x/shop"\ninstall.gradle: "no"' >"$CFG"   # as Jackson writes it, no final newline
check "a quoted value" no "$(cfg_get install.gradle "$CFG")"
check "a nested key is not ours" "" "$(cfg_get paths "$CFG")"
cfg_set install.supportLocal no "$CFG"
cfg_set install.supportRemote '"https://repo.example/releases@0.2.0"' "$CFG"
check "the fleets block is kept, a missing newline added" \
  "$(printf -- '---\nfleets:\n  paths:\n    shop: "/x/shop"\ninstall.gradle: "no"\ninstall.supportLocal: no\ninstall.supportRemote: "https://repo.example/releases@0.2.0"')" \
  "$(cat "$CFG")"
check "a URL value" "https://repo.example/releases@0.2.0" "$(cfg_get install.supportRemote "$CFG")"
cfg_set install.supportRemote '"nexus@0.3.0"' "$CFG"
check "a key set again is replaced, once" 1 "$(grep -c '^install.supportRemote:' "$CFG")"
check "the new value" "nexus@0.3.0" "$(cfg_get install.supportRemote "$CFG")"
cfg_unset install.gradle "$CFG"
check "unset" "" "$(cfg_get install.gradle "$CFG")"
check "unset keeps the rest" '    shop: "/x/shop"' "$(grep shop "$CFG")"
printf 'install.gradle: false # Jackson may write a boolean\n' >>"$CFG"
holds "false counts as no" is_no "$(cfg_get install.gradle "$CFG")"
check "the config file's place" "$T/xdg/logast/config.yaml" "$(XDG_CONFIG_HOME="$T/xdg" config_file)"
check "the config file's default place" "$HOME/.config/logast/config.yaml" "$(config_file)"

# ── decide: Gradle setup / support library in Maven Local ─────────────────────────────────────────────────────────
#      state    flag remembered --ask  →
for c in "current  ask -    0  current" \
         "stale    ask -    0  refresh" \
         "stale    ask no   0  refresh" \
         "stale    0   -    0  no" \
         "none     0   -    0  no" \
         "none     ask -    0  ask" \
         "foreign  ask -    0  ask" \
         "none     ask no   0  remembered" \
         "none     ask no   1  ask" \
         "none     1   no   0  yes" \
         "current  1   -    0  current"; do
  set -- $c
  check "decide $1 $2 $3 $4" "$5" "$(decide "$1" "$2" "$([ "$3" = - ] || echo "$3")" "$4")"
done

# ── decide_remote ─────────────────────────────────────────────────────────────────────────────────────────────────
#      --publish-to -y remembered        version --ask →
for c in "nexus      0  -                 1.0     0  given" \
         "-          0  nexus@1.0         1.0     0  published" \
         "-          0  https://r/x@1.0   1.0     0  published" \
         "-          0  nexus@0.9         1.0     0  ask" \
         "-          1  nexus@0.9         1.0     0  none" \
         "-          0  no                1.0     0  remembered" \
         "-          1  no                1.0     0  remembered" \
         "-          0  no                1.0     1  ask" \
         "-          0  nexus@1.0         1.0     1  ask" \
         "-          0  -                 1.0     0  ask" \
         "-          1  -                 1.0     0  none"; do
  set -- $c
  check "decide_remote $1 $2 $3 $4 $5" "$6" "$(decide_remote "$([ "$1" = - ] || echo "$1")" "$2" "$([ "$3" = - ] || echo "$3")" "$4" "$5")"
done
check "credentials are not written down" "https://repo.example/r" "$(without_credentials "https://me:secret@repo.example/r")"
check "a URL without credentials" "https://repo.example/r" "$(without_credentials "https://repo.example/r")"
check "a repository name" "nexus" "$(without_credentials "nexus")"

# ── source_installed: the installed version names this checkout's commit, and nothing changed ─────────────────────
REPO="$T/repo"
mkdir -p "$REPO"
(
  cd "$REPO" || exit 1
  git init -q . && git config user.email t@example.org && git config user.name t && git config commit.gpgsign false
  echo a >a.txt && git add a.txt && git commit -qm one
) >/dev/null 2>&1
sha="$(git -C "$REPO" describe --always --abbrev=7)"
in_repo() { (cd "$REPO" && "$@"); }
holds "this commit is installed" in_repo source_installed "logast 1.0 ($sha)" 1.0
fails "another version" in_repo source_installed "logast 0.9 ($sha)" 1.0
fails "another commit" in_repo source_installed "logast 1.0 (0000000)" 1.0
fails "nothing installed" in_repo source_installed "" 1.0
echo b >>"$REPO/a.txt"
fails "a changed file" in_repo source_installed "logast 1.0 ($sha)" 1.0
git -C "$REPO" checkout -q a.txt
echo new >"$REPO/new.txt"
fails "a new file" in_repo source_installed "logast 1.0 ($sha)" 1.0
rm -f "$REPO/new.txt"
holds "clean again" in_repo source_installed "logast 1.0 ($sha)" 1.0

# ── the whole run, from a release zip: the second run has nothing to do ──────────────────────────────────────────
if command -v java >/dev/null && command -v zip >/dev/null && command -v unzip >/dev/null; then
  Z="$T/zip/logast-1.0"
  mkdir -p "$Z/bin" "$Z/gradle"
  printf '#!/bin/sh\necho "logast 1.0 (abc1234)"\n' >"$Z/bin/logast"; chmod +x "$Z/bin/logast"
  printf '%s\n' "$INIT_MARKER" >"$Z/gradle/logast.gradle"
  (cd "$T/zip" && zip -qr logast-1.0.zip logast-1.0)
  run() { LOGAST_HOME="$T/inst/share" LOGAST_BIN="$T/inst/bin" GRADLE_USER_HOME="$T/inst/gradle" XDG_CONFIG_HOME="$T/inst/config" \
            NO_COLOR=1 bash "$ROOT/install.sh" --zip "$T/zip/logast-1.0.zip" "$@" </dev/null 2>&1; }
  first="$(run)"; code=$?
  check "the first run installs" 0 "$code"
  check "the command" "logast 1.0 (abc1234)" "$("$T/inst/bin/logast" --version)"
  case "$first" in *"logast is installed"*) PASSED=$((PASSED + 1)) ;; *) FAILED=$((FAILED + 1)); printf 'FAIL first run:\n%s\n' "$first" ;; esac
  touch "$T/inst/share/bin/marker"
  mkdir -p "$T/inst/share/fleets/f1" && echo keep >"$T/inst/share/fleets/f1/company.yaml"
  second="$(run)"
  case "$second" in
    *"logast 1.0 (abc1234) is already installed — nothing to do (--force reinstalls)"*"logast is up to date"*) PASSED=$((PASSED + 1)) ;;
    *) FAILED=$((FAILED + 1)); printf 'FAIL second run:\n%s\n' "$second" ;;
  esac
  holds "the second run leaves the install alone" test -f "$T/inst/share/bin/marker"
  run --force >/dev/null
  fails "--force reinstalls" test -f "$T/inst/share/bin/marker"
  check "a reinstall keeps the fleets (#135)" keep "$(cat "$T/inst/share/fleets/f1/company.yaml" 2>/dev/null)"
else
  echo "skipped the whole-run check: java, zip or unzip missing"
fi

printf '%d passed, %d failed\n' "$PASSED" "$FAILED"
[ "$FAILED" = 0 ]
