#!/usr/bin/env bash
# Builds logast and installs the `logast` command for the current user.
#   ./install.sh                      build from source, install to ~/.local/share/logast, link ~/.local/bin/logast
#   ./install.sh --zip logast-X.zip   install a release zip instead of building
#   ./install.sh --skill              also install the Claude Code skill (~/.claude/skills/logast/SKILL.md)
#   ./install.sh --starter            also publish the logging-support starter to Maven Local (~/.m2)
# Gradle setup: then `./gradlew logastApply` (logastScan, logastCheck, ...) works in any Java build on this machine.
#   It publishes core, cli and gradle-plugin to Maven Local (~/.m2) and writes the init script
#   ${GRADLE_USER_HOME:-~/.gradle}/init.d/logast.gradle. install.sh asks first (Enter = no); without a terminal it skips.
#   -y, --yes                         set up Gradle without asking
#   --no-gradle                       skip it without asking
#   (a release zip, --zip, has nothing to publish: no Gradle setup)
set -euo pipefail
cd "$(dirname "$0")"

PREFIX="${LOGAST_HOME:-$HOME/.local/share/logast}"
BIN_DIR="${LOGAST_BIN:-$HOME/.local/bin}"
ZIP="" SKILL=0 STARTER=0 GRADLE=ask
while [ $# -gt 0 ]; do
  case "$1" in
    --zip) ZIP="$2"; shift ;;
    --skill) SKILL=1 ;;
    --starter) STARTER=1 ;;
    -y|--yes) GRADLE=1 ;;
    --no-gradle) GRADLE=0 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

# ── output: colours only on a terminal, and never with NO_COLOR ────────────────────────────────────────────────
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ] && [ "${TERM:-}" != dumb ]; then
  B=$'\e[1m' D=$'\e[2m' G=$'\e[32m' Y=$'\e[33m' R=$'\e[31m' C=$'\e[36m' N=$'\e[0m'
else
  B="" D="" G="" Y="" R="" C="" N=""
fi
STEP=0
step() { STEP=$((STEP + 1)); printf '\n%s%d ›%s %s%s%s\n' "$C" "$STEP" "$N" "$B" "$1" "$N"; }
ok()   { printf '  %s✓%s %s\n' "$G" "$N" "$1"; }
info() { printf '  %s•%s %s\n' "$D" "$N" "$1"; }
warn() { printf '  %s!%s %s\n' "$Y" "$N" "$1" >&2; }
fail() { printf '  %s✗%s %s\n' "$R" "$N" "$1" >&2; exit 1; }
home() { case "$1" in "$HOME"*) printf '~%s' "${1#"$HOME"}" ;; *) printf '%s' "$1" ;; esac; }
since() { local s=$(( $(date +%s) - $1 )); [ "$s" -ge 60 ] && printf '%dm %02ds' $((s / 60)) $((s % 60)) || printf '%ds' "$s"; }

printf '%s' "$B"
cat <<'BANNER'
   _                       _
  | | ___   __ _  __ _ ___| |_
  | |/ _ \ / _` |/ _` / __| __|
  | | (_) | (_| | (_| \__ \ |_
  |_|\___/ \__, |\__,_|___/\__|
           |___/
BANNER
printf '%s  %slogging, tracing and metrics for Spring Boot services, standardised from the code%s\n' "$N" "$D" "$N"

# ── 1. checks and choices ─────────────────────────────────────────────────────────────────────────────────────
step "Checking the environment"
# macOS ships a /usr/bin/java stub without a JDK: its output has no version, which counts as "missing".
java_major="$(java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1 || true)"
if [ -z "$java_major" ] || [ "$java_major" -lt 21 ]; then
  fail "logast needs Java 21+ on PATH (25 recommended)${java_major:+; found Java $java_major}"
fi
ok "Java $java_major ($(home "$(command -v java)"))$([ "$java_major" -lt 25 ] && printf ' %s· 25 recommended%s' "$D" "$N")"
old="$([ -x "$PREFIX/bin/logast" ] && "$PREFIX/bin/logast" --version 2>/dev/null || true)"
if [ -n "$old" ]; then info "installed now: $old (will be replaced)"; else info "first install"; fi
if [ -n "$ZIP" ]; then info "from the release zip $ZIP"; else info "from source: $(git rev-parse --short HEAD 2>/dev/null || echo 'this checkout')"; fi

GRADLE_INIT="${GRADLE_USER_HOME:-$HOME/.gradle}/init.d/logast.gradle"
INIT_MARKER="// logast init script"   # the first line of the file logast writes: only such a file is replaced
GRADLE_SKIPPED=""
if [ "$GRADLE" != 0 ] && [ -n "$ZIP" ]; then
  GRADLE=0; GRADLE_SKIPPED="a release zip has no sources to publish the plugin from: run install.sh from a checkout"
elif [ "$GRADLE" = ask ]; then
  if [ -t 0 ]; then
    printf '\n  %sSet up Gradle too?%s Then %s./gradlew logastApply%s works in any Java build on this machine:\n' "$B" "$N" "$C" "$N"
    info "publishes dev.logast:core, cli and gradle-plugin to Maven Local ($(home "$HOME/.m2"))"
    info "writes $(home "$GRADLE_INIT"): adds dev.logast to builds with a logast.yaml"
    info "and wherever a logast task is asked for; -Plogast.disabled opts a build out"
    printf '  Proceed? [y/N] '
    answer=""; read -r answer || true
    case "$answer" in
      y|Y|yes|Yes|YES) GRADLE=1 ;;
      *) GRADLE=0; GRADLE_SKIPPED="not confirmed (rerun with -y to set it up)" ;;
    esac
  else
    GRADLE=0; GRADLE_SKIPPED="no terminal to ask: rerun with -y to publish the plugin to Maven Local and write $(home "$GRADLE_INIT")"
  fi
fi

# ── 2. build ──────────────────────────────────────────────────────────────────────────────────────────────────
if [ -z "$ZIP" ]; then
  step "Building logast"
  info "Gradle, quietly: the first build downloads dependencies and takes a few minutes"
  t0=$(date +%s)
  ./gradlew -q :cli:distZip $([ "$STARTER" = 1 ] && echo ":logging-support:publishToMavenLocal :test-capture:publishToMavenLocal") \
    $([ "$GRADLE" = 1 ] && echo ":core:publishToMavenLocal :cli:publishToMavenLocal :gradle-plugin:publishToMavenLocal") \
    2> >(grep -v '^Note: ' >&2) || fail "the build failed (its output is above)"
  ZIP="$(ls -t cli/build/distributions/logast-*.zip | head -1)"
  ok "built $(basename "$ZIP") in $(since "$t0")"
elif [ "$STARTER" = 1 ]; then
  echo "--starter needs a source build (omit --zip)" >&2; exit 2
else
  step "Using the release zip"
  ok "$(basename "$ZIP")"
fi

# ── 3. install ────────────────────────────────────────────────────────────────────────────────────────────────
step "Installing the command"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
unzip -q "$ZIP" -d "$tmp"
rm -rf "$PREFIX"; mkdir -p "$(dirname "$PREFIX")" "$BIN_DIR"
mv "$tmp"/logast-* "$PREFIX"
ln -sf "$PREFIX/bin/logast" "$BIN_DIR/logast"
version="$("$BIN_DIR/logast" --version)"
ok "$version  ${D}${old:+(was: $old)}${N}"
info "command: $(home "$BIN_DIR/logast") → $(home "$PREFIX")"
# another logast earlier on PATH (an older install, a copied script) would shadow this one
found="$(command -v logast 2>/dev/null || true)"
if [ -n "$found" ] && [ "$found" != "$BIN_DIR/logast" ]; then
  warn "'logast' on your PATH is $found ($("$found" --version 2>/dev/null || echo '?')), not $(home "$BIN_DIR/logast")."
  warn "remove it or put $(home "$BIN_DIR") first on PATH; run 'hash -r' in open shells."
fi
if [ "$SKILL" = 1 ] || [ -f "$HOME/.claude/skills/logast/SKILL.md" ]; then   # re-runs refresh an installed skill
  mkdir -p "$HOME/.claude/skills/logast"
  cp "$PREFIX/claude-skill/logast/SKILL.md" "$HOME/.claude/skills/logast/SKILL.md"
  ok "Claude Code skill: ~/.claude/skills/logast/SKILL.md"
fi
[ "$STARTER" = 1 ] && ok "starter: dev.logast:logging-support:$(echo "$version" | cut -d' ' -f2) in ~/.m2"

# ── 4. Gradle ─────────────────────────────────────────────────────────────────────────────────────────────────
# the init script from the installed distribution; a file of the same name that logast did not write is left alone
install_init_script() {   # $1: the script, $2: where it goes
  if [ -f "$2" ] && [ "$(head -n 1 "$2" | cut -c1-${#INIT_MARKER})" != "$INIT_MARKER" ]; then
    warn "$(home "$2") exists and was not written by logast: left as is, Gradle setup incomplete."
    warn "move it away and rerun, or apply the plugin in the build (docs/gradle-plugin.md)."
    return 0
  fi
  mkdir -p "$(dirname "$2")"
  cp "$1" "$2"
  ok "init script: $(home "$2")"
}
if [ "$GRADLE" = 1 ]; then
  step "Setting up Gradle"
  ok "plugin: dev.logast:gradle-plugin:$(echo "$version" | cut -d' ' -f2) (with core and cli) in ~/.m2"
  install_init_script "$PREFIX/gradle/logast.gradle" "$GRADLE_INIT"
fi

# ── summary ───────────────────────────────────────────────────────────────────────────────────────────────────
printf '\n%s%s✓ logast is installed%s %s(%s)%s\n' "$B" "$G" "$N" "$D" "$version" "$N"
[ -n "$GRADLE_SKIPPED" ] && printf '  %sGradle setup skipped:%s %s\n' "$Y" "$N" "$GRADLE_SKIPPED"
case ":$PATH:" in *":$BIN_DIR:"*) ;; *) printf '  %s!%s add %s to PATH: export PATH="%s:$PATH"\n' "$Y" "$N" "$(home "$BIN_DIR")" "$BIN_DIR";; esac
printf '\n%sNext, in a service:%s\n' "$B" "$N"
printf '  %slogast apply .%s%s            migrate it (sets itself up; the result compiles)\n' "$C" "$N" "$([ "$GRADLE" = 1 ] && printf ' %sor ./gradlew logastApply%s' "$C" "$N")"
printf '  %sgit diff%s                  review the changes\n' "$C" "$N"
printf '  %slogast review%s             optional: decide message texts, metrics, retries; then apply again\n' "$C" "$N"
printf '  %slogast fleet join <name>%s  optional: compare it with your other services\n' "$C" "$N"
printf '\n  %sDocs: README.md · docs/commands.md · docs/fleet.md · docs/platform.md (for platform teams)%s\n' "$D" "$N"
