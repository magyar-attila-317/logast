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

# macOS ships a /usr/bin/java stub without a JDK: its output has no version, which counts as "missing".
java_major="$(java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1 || true)"
if [ -z "$java_major" ] || [ "$java_major" -lt 21 ]; then
  echo "logast needs Java 21+ on PATH (25 recommended)." >&2; exit 1
fi

GRADLE_INIT="${GRADLE_USER_HOME:-$HOME/.gradle}/init.d/logast.gradle"
INIT_MARKER="// logast init script"   # the first line of the file logast writes: only such a file is replaced
GRADLE_SKIPPED=""
if [ "$GRADLE" != 0 ] && [ -n "$ZIP" ]; then
  GRADLE=0; GRADLE_SKIPPED="a release zip has no sources to publish the plugin from: run install.sh from a checkout"
elif [ "$GRADLE" = ask ]; then
  if [ -t 0 ]; then
    echo "Set up Gradle, so ./gradlew logastApply works in any Java build on this machine? This will:"
    echo "  - publish dev.logast:core, dev.logast:cli and dev.logast:gradle-plugin to Maven Local (~/.m2)"
    echo "  - write $GRADLE_INIT (it applies dev.logast where a logast task is run; opt out with -Plogast.disabled)"
    printf "Proceed? [y/N] "
    answer=""; read -r answer || true
    case "$answer" in
      y|Y|yes|Yes|YES) GRADLE=1 ;;
      *) GRADLE=0; GRADLE_SKIPPED="not confirmed (rerun with -y to set it up)" ;;
    esac
  else
    GRADLE=0; GRADLE_SKIPPED="no terminal to ask: rerun with -y to publish the plugin to Maven Local and write $GRADLE_INIT"
  fi
fi

if [ -z "$ZIP" ]; then
  echo "building logast…"
  ./gradlew -q :cli:distZip $([ "$STARTER" = 1 ] && echo ":logging-support:publishToMavenLocal :test-capture:publishToMavenLocal") \
    $([ "$GRADLE" = 1 ] && echo ":core:publishToMavenLocal :cli:publishToMavenLocal :gradle-plugin:publishToMavenLocal")
  ZIP="$(ls -t cli/build/distributions/logast-*.zip | head -1)"
elif [ "$STARTER" = 1 ]; then
  echo "--starter needs a source build (omit --zip)" >&2; exit 2
fi

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
unzip -q "$ZIP" -d "$tmp"
old="$([ -x "$PREFIX/bin/logast" ] && "$PREFIX/bin/logast" --version 2>/dev/null || true)"
rm -rf "$PREFIX"; mkdir -p "$(dirname "$PREFIX")" "$BIN_DIR"
mv "$tmp"/logast-* "$PREFIX"
ln -sf "$PREFIX/bin/logast" "$BIN_DIR/logast"
echo "installed: $BIN_DIR/logast -> $PREFIX ($("$BIN_DIR/logast" --version)${old:+, was: $old})"
# another logast earlier on PATH (an older install, a copied script) would shadow this one
found="$(command -v logast 2>/dev/null || true)"
if [ -n "$found" ] && [ "$found" != "$BIN_DIR/logast" ]; then
  echo "WARNING: 'logast' on your PATH is $found ($("$found" --version 2>/dev/null || echo '?')), not $BIN_DIR/logast." >&2
  echo "         Remove it or put $BIN_DIR first on PATH; run 'hash -r' in open shells." >&2
fi

if [ "$SKILL" = 1 ] || [ -f "$HOME/.claude/skills/logast/SKILL.md" ]; then   # re-runs refresh an installed skill
  mkdir -p "$HOME/.claude/skills/logast"
  cp "$PREFIX/claude-skill/logast/SKILL.md" "$HOME/.claude/skills/logast/SKILL.md"
  echo "Claude Code skill: ~/.claude/skills/logast/SKILL.md"
fi
[ "$STARTER" = 1 ] && echo "starter: dev.logast:logging-support:$("$BIN_DIR/logast" --version | cut -d' ' -f2) in ~/.m2"

# the init script from the installed distribution; a file of the same name that logast did not write is left alone
install_init_script() {   # $1: the script, $2: where it goes
  if [ -f "$2" ] && [ "$(head -n 1 "$2" | cut -c1-${#INIT_MARKER})" != "$INIT_MARKER" ]; then
    echo "WARNING: $2 exists and was not written by logast: left as is, Gradle setup incomplete." >&2
    echo "         Move it away and rerun, or apply the plugin in the build (docs/gradle-plugin.md)." >&2
    return 0
  fi
  mkdir -p "$(dirname "$2")"
  cp "$1" "$2"
  echo "Gradle init script: $2 (./gradlew logastApply in any Java build; -Plogast.disabled opts out)"
}
if [ "$GRADLE" = 1 ]; then
  echo "Gradle plugin: dev.logast:gradle-plugin:$("$BIN_DIR/logast" --version | cut -d' ' -f2) (with core and cli) in ~/.m2"
  install_init_script "$PREFIX/gradle/logast.gradle" "$GRADLE_INIT"
elif [ -n "$GRADLE_SKIPPED" ]; then
  echo "Gradle setup skipped: $GRADLE_SKIPPED"
fi
case ":$PATH:" in *":$BIN_DIR:"*) ;; *) echo "note: add $BIN_DIR to PATH";; esac
