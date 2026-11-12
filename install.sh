#!/usr/bin/env bash
# Builds logast and installs the `logast` command for the current user.
#   ./install.sh                      build from source, install to ~/.local/share/logast, link ~/.local/bin/logast
#   ./install.sh --zip logast-X.zip   install a release zip instead of building
#   ./install.sh --skill              also install the Claude Code skill (~/.claude/skills/logast/SKILL.md)
#   ./install.sh --starter            also publish the logging-support starter to Maven Local (~/.m2)
set -euo pipefail
cd "$(dirname "$0")"

PREFIX="${LOGAST_HOME:-$HOME/.local/share/logast}"
BIN_DIR="${LOGAST_BIN:-$HOME/.local/bin}"
ZIP="" SKILL=0 STARTER=0
while [ $# -gt 0 ]; do
  case "$1" in
    --zip) ZIP="$2"; shift ;;
    --skill) SKILL=1 ;;
    --starter) STARTER=1 ;;
    -h|--help) sed -n '2,6p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

if ! command -v java >/dev/null || [ "$(java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p')" -lt 21 ]; then
  echo "logast needs Java 21+ on PATH (25 recommended)." >&2; exit 1
fi

if [ -z "$ZIP" ]; then
  echo "building logast…"
  ./gradlew -q :cli:distZip $([ "$STARTER" = 1 ] && echo ":logging-support:publishToMavenLocal :test-capture:publishToMavenLocal")
  ZIP="$(ls -t cli/build/distributions/logast-*.zip | head -1)"
elif [ "$STARTER" = 1 ]; then
  echo "--starter needs a source build (omit --zip)" >&2; exit 2
fi

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
unzip -q "$ZIP" -d "$tmp"
rm -rf "$PREFIX"; mkdir -p "$(dirname "$PREFIX")" "$BIN_DIR"
mv "$tmp"/logast-* "$PREFIX"
ln -sf "$PREFIX/bin/logast" "$BIN_DIR/logast"
echo "installed: $BIN_DIR/logast -> $PREFIX ($("$BIN_DIR/logast" --version))"

if [ "$SKILL" = 1 ]; then
  mkdir -p "$HOME/.claude/skills/logast"
  cp "$PREFIX/claude-skill/logast/SKILL.md" "$HOME/.claude/skills/logast/SKILL.md"
  echo "Claude Code skill: ~/.claude/skills/logast/SKILL.md"
fi
[ "$STARTER" = 1 ] && echo "starter: dev.logast:logging-support:$("$BIN_DIR/logast" --version | cut -d' ' -f2) in ~/.m2"
case ":$PATH:" in *":$BIN_DIR:"*) ;; *) echo "note: add $BIN_DIR to PATH";; esac
