#!/usr/bin/env bash
# Builds logast and installs the `logast` command for the current user.
#   ./install.sh                      build from source, install to ~/.local/share/logast, link ~/.local/bin/logast
#   ./install.sh --zip logast-X.zip   install a release zip instead of building
#   ./install.sh --skill              also install the Claude Code skill (~/.claude/skills/logast/SKILL.md)
# Three questions (Enter = no; without a terminal each is a no):
#   1. Gradle setup: then `./gradlew logastApply` (logastScan, logastCheck, ...) works in any Java build on this machine.
#      It publishes core, cli and gradle-plugin to Maven Local (~/.m2) and writes the init script
#      ${GRADLE_USER_HOME:-~/.gradle}/init.d/logast.gradle.
#   2. the support library (dev.logast:logging-support, test-capture) to Maven Local: builds on this machine resolve it,
#      and `logast apply` adds the jar instead of copying its classes in.
#   3. the support library to a remote repository: one this machine's Gradle already knows (with its credentials), or
#      another URL (REPO_USER / REPO_PASSWORD, or asked): every build that resolves from there gets the jar.
#   -y, --yes                         yes to 1 and 2, without asking (3 only with --publish-to)
#   --no-gradle                       no to 1, without asking
#   --starter                         yes to 2, without asking
#   --publish-to <name|url>           3 without asking: a repository name (./gradlew -q logastRepositories) or a URL
#   (a release zip, --zip, has nothing to publish: none of the three)
set -euo pipefail
cd "$(dirname "$0")"

PREFIX="${LOGAST_HOME:-$HOME/.local/share/logast}"
BIN_DIR="${LOGAST_BIN:-$HOME/.local/bin}"
ZIP="" SKILL=0 STARTER=ask GRADLE=ask YES=0 PUBLISH_TO=""
while [ $# -gt 0 ]; do
  case "$1" in
    --zip) ZIP="$2"; shift ;;
    --skill) SKILL=1 ;;
    --starter) STARTER=1 ;;
    -y|--yes) YES=1 ;;
    --no-gradle) GRADLE=0 ;;
    --publish-to) PUBLISH_TO="${2:?--publish-to needs a repository name or URL}"; shift ;;
    -h|--help) sed -n '2,21p' "$0"; exit 0 ;;
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
yes_no() { local a=""; printf '  %s [y/N] ' "$1"; read -r a || true; case "$a" in y|Y|yes|Yes|YES) return 0 ;; *) return 1 ;; esac; }
[ "$YES" = 1 ] && [ "$GRADLE" = ask ] && GRADLE=1
[ "$YES" = 1 ] && [ "$STARTER" = ask ] && STARTER=1
if [ -n "$ZIP" ]; then
  [ "$STARTER" = 1 ] && { echo "--starter needs a source build (omit --zip)" >&2; exit 2; }
  [ -n "$PUBLISH_TO" ] && { echo "--publish-to needs a source build (omit --zip)" >&2; exit 2; }
  STARTER=0
fi

# 1. Gradle setup
if [ "$GRADLE" != 0 ] && [ -n "$ZIP" ]; then
  GRADLE=0; GRADLE_SKIPPED="a release zip has no sources to publish the plugin from: run install.sh from a checkout"
elif [ "$GRADLE" = ask ]; then
  if [ -t 0 ]; then
    printf '\n  %sSet up Gradle too?%s Then %s./gradlew logastApply%s works in any Java build on this machine:\n' "$B" "$N" "$C" "$N"
    info "publishes dev.logast:core, cli and gradle-plugin to Maven Local ($(home "$HOME/.m2"))"
    info "writes $(home "$GRADLE_INIT"): adds dev.logast to builds with a logast.yaml"
    info "and wherever a logast task is asked for; -Plogast.disabled opts a build out"
    if yes_no "Proceed?"; then GRADLE=1; else GRADLE=0; GRADLE_SKIPPED="not confirmed (rerun with -y to set it up)"; fi
  else
    GRADLE=0; GRADLE_SKIPPED="no terminal to ask: rerun with -y to publish the plugin to Maven Local and write $(home "$GRADLE_INIT")"
  fi
fi

# 2. the support library to Maven Local
if [ "$STARTER" = ask ]; then
  STARTER=0
  if [ -t 0 ]; then
    printf '\n  %sPublish the support library to Maven Local?%s Then %slogast apply%s adds the jar instead of copying its classes in:\n' "$B" "$N" "$C" "$N"
    info "publishes dev.logast:logging-support and test-capture to Maven Local ($(home "$HOME/.m2"))"
    info "for builds on this machine that resolve from Maven Local (mavenLocal() among their repositories)"
    yes_no "Proceed?" && STARTER=1
  fi
fi

# 3. the support library to a remote repository: one Gradle already knows on this machine, or another URL
PUBLISH_NAME="" PUBLISH_URL="" PUBLISH_LABEL=""
publish_url() {   # $1: the URL; credentials from REPO_USER / REPO_PASSWORD, else asked when there is a terminal
  PUBLISH_URL="$1"; PUBLISH_LABEL="$1"
  if [ -z "${REPO_USER:-}" ] && [ -t 0 ]; then
    printf '  user (Enter = none): '; REPO_USER=""; read -r REPO_USER || true
    if [ -n "$REPO_USER" ]; then printf '  password: '; REPO_PASSWORD=""; read -rs REPO_PASSWORD || true; printf '\n'; fi
  fi
}
if [ -n "$PUBLISH_TO" ]; then
  case "$PUBLISH_TO" in
    *://*) publish_url "$PUBLISH_TO" ;;
    *) PUBLISH_NAME="$PUBLISH_TO"; PUBLISH_LABEL="$PUBLISH_TO" ;;
  esac
elif [ -z "$ZIP" ] && [ "$YES" = 0 ] && [ -t 0 ]; then
  printf '\n  %sPublish the support library to a repository too?%s Then every build that resolves from there gets the jar:\n' "$B" "$N"
  info "asking Gradle which repositories this machine knows (logast's own build, with your init scripts)…"
  repos=()
  while IFS=$'\t' read -r name url; do
    [ -n "$name" ] && repos+=("$name"$'\t'"$url")
  done < <(./gradlew -q logastRepositories </dev/null 2>/dev/null || warn "Gradle could not list them; another URL still works")
  i=0
  for r in ${repos[@]+"${repos[@]}"}; do
    i=$((i + 1)); name="${r%%$'\t'*}"; url="${r#*$'\t'}"
    case "$(printf '%s' "$name" | tr '[:upper:]' '[:lower:]')" in
      *snapshot*) tag=" ${D}(snapshots: a release version may be refused)${N}" ;;
      *release*|*hosted*) tag=" ${G}(hosted: takes uploads)${N}" ;;
      *) tag="" ;;
    esac
    printf '    %s%d)%s %-24s %s%s\n' "$C" "$i" "$N" "$name" "$url" "$tag"
  done
  printf '    %s%d)%s another URL\n' "$C" $((i + 1)) "$N"
  info "a group repository (maven-public) usually takes no uploads: pick a hosted one"
  printf '  Choose [1-%d, Enter = no] ' $((i + 1)); answer=""; read -r answer || true
  if [[ "$answer" =~ ^[0-9]+$ ]] && [ "$answer" -ge 1 ] && [ "$answer" -le "$i" ]; then
    PUBLISH_NAME="${repos[$((answer - 1))]%%$'\t'*}"; PUBLISH_LABEL="$PUBLISH_NAME"
  elif [ "$answer" = $((i + 1)) ]; then
    printf '  URL: '; url=""; read -r url || true
    [ -n "$url" ] && publish_url "$url"
  fi
fi

# ── 2. build ──────────────────────────────────────────────────────────────────────────────────────────────────
if [ -z "$ZIP" ]; then
  step "Building logast"
  info "Gradle, quietly: the first build downloads dependencies and takes a few minutes"
  t0=$(date +%s)
  ./gradlew -q :cli:distZip $([ "$STARTER" = 1 ] && echo ":logging-support:publishToMavenLocal :test-capture:publishToMavenLocal") \
    $([ "$GRADLE" = 1 ] && echo ":core:publishToMavenLocal :cli:publishToMavenLocal :gradle-plugin:publishToMavenLocal") \
    </dev/null 2> >(grep -v '^Note: ' >&2) || fail "the build failed (its output is above)"
  ZIP="$(ls -t cli/build/distributions/logast-*.zip | head -1)"
  ok "built $(basename "$ZIP") in $(since "$t0")"
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
[ "$STARTER" = 1 ] && ok "support library: dev.logast:logging-support:$(echo "$version" | cut -d' ' -f2) in ~/.m2"

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

# ── 5. the support library to a repository ────────────────────────────────────────────────────────────────────
# Gradle's refusal in one line: an upload a repository does not take is the usual failure, and its stack trace says less
refusal() {   # $1: Gradle's output
  local code; code="$(grep -oE 'status code [0-9]{3}' "$1" | head -1 | grep -oE '[0-9]{3}' || true)"
  case "$code" in
    401) echo "refused (401): the repository did not accept the credentials" ;;
    403) echo "refused (403): this user may not publish there" ;;
    405) echo "refused (405): the repository takes no uploads (a group or proxy repository?): pick a hosted one" ;;
    400) echo "refused (400): the version is there already, or a release went to a snapshot repository" ;;
    "") local why   # the innermost cause Gradle names under "What went wrong"
        why="$(awk '/What went wrong/ { on = 1; next } /^\* Try/ { on = 0 } on && NF { last = $0 } END { print last }' "$1" \
          | sed -E 's/^[[:space:]]*>?[[:space:]]*//' | cut -c1-200 || true)"
        echo "${why:-the upload failed}" ;;
    *) echo "refused (HTTP $code)" ;;
  esac
}
PUBLISHED="" PUBLISH_FAILED=""
if [ -n "$PUBLISH_LABEL" ]; then
  step "Publishing the support library"
  info "dev.logast:logging-support and test-capture to $PUBLISH_LABEL"
  t0=$(date +%s)
  log="$tmp/publish.log"
  if [ -n "$PUBLISH_NAME" ]; then
    set -- "-PrepoName=$PUBLISH_NAME"
  else
    set --   # the URL and credentials go in the environment: never on a command line others can read
  fi
  if REPO_URL="$PUBLISH_URL" REPO_USER="${REPO_USER:-}" REPO_PASSWORD="${REPO_PASSWORD:-}" \
      ./gradlew -q :logging-support:publishAllPublicationsToCompanyRepository :test-capture:publishAllPublicationsToCompanyRepository \
      ${1+"$@"} </dev/null >"$log" 2>&1; then
    PUBLISHED="$PUBLISH_LABEL"
    ok "published to $PUBLISH_LABEL in $(since "$t0")"
  else
    PUBLISH_FAILED="$(refusal "$log")"
    warn "not published to $PUBLISH_LABEL: $PUBLISH_FAILED"
    info "Gradle's whole output: ./gradlew :logging-support:publishAllPublicationsToCompanyRepository $([ -n "$PUBLISH_NAME" ] && echo "-PrepoName=$PUBLISH_NAME" || echo "-PrepoUrl=$PUBLISH_URL")"
  fi
fi

# ── summary ───────────────────────────────────────────────────────────────────────────────────────────────────
printf '\n%s%s✓ logast is installed%s %s(%s)%s\n' "$B" "$G" "$N" "$D" "$version" "$N"
[ -n "$GRADLE_SKIPPED" ] && printf '  %sGradle setup skipped:%s %s\n' "$Y" "$N" "$GRADLE_SKIPPED"
[ -n "$PUBLISHED" ] && printf '  %ssupport library:%s published to %s; apply adds the jar where a build resolves from there\n' "$G" "$N" "$PUBLISHED"
[ -n "$PUBLISH_FAILED" ] && printf '  %ssupport library not published:%s %s\n' "$Y" "$N" "$PUBLISH_FAILED"
case ":$PATH:" in *":$BIN_DIR:"*) ;; *) printf '  %s!%s add %s to PATH: export PATH="%s:$PATH"\n' "$Y" "$N" "$(home "$BIN_DIR")" "$BIN_DIR";; esac
printf '\n%sNext, in a service:%s\n' "$B" "$N"
printf '  %slogast apply .%s%s            migrate it (sets itself up; the result compiles)\n' "$C" "$N" "$([ "$GRADLE" = 1 ] && printf ' %sor ./gradlew logastApply%s' "$C" "$N")"
printf '  %sgit diff%s                  review the changes\n' "$C" "$N"
printf '  %slogast review%s             optional: decide message texts, metrics, retries; then apply again\n' "$C" "$N"
printf '  %slogast fleet join <name>%s  optional: compare it with your other services\n' "$C" "$N"
printf '\n  %sDocs: README.md · docs/commands.md · docs/fleet.md · docs/platform.md (for platform teams)%s\n' "$D" "$N"
[ -z "$PUBLISH_FAILED" ]   # a refused upload fails the run (scripts and CI see it); the command is installed either way
