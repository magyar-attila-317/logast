#!/usr/bin/env bash
# Builds logast and installs the `logast` command for the current user.
#   ./install.sh                      build from source, install to ~/.local/share/logast, link ~/.local/bin/logast
#   ./install.sh --zip logast-X.zip   install a release zip instead of building
#   ./install.sh --skill              also install the Claude Code skill (~/.claude/skills/logast/SKILL.md)
# A re-run whose installed `logast --version` names this commit (and the checkout has no local changes) builds nothing:
#   "already installed — nothing to do"; it still checks the setup below. --force rebuilds and reinstalls.
# Three setup questions (Enter = no; without a terminal each is a no), each asked only while it is not done:
#   1. Gradle setup: then `./gradlew logastApply` (logastScan, logastCheck, ...) works in any Java build on this machine.
#      It publishes core, cli and gradle-plugin to Maven Local (~/.m2) and writes the init script
#      ${GRADLE_USER_HOME:-~/.gradle}/init.d/logast.gradle.
#   2. the support library (dev.logast:logging-support, test-capture) to Maven Local: builds on this machine resolve it,
#      and `logast apply` adds the jar instead of copying its classes in.
#   3. the support library to a remote repository: one this machine's Gradle already knows (with its credentials), or
#      another URL (REPO_USER / REPO_PASSWORD, or asked): every build that resolves from there gets the jar.
#   Done for this version: not asked. Done for another version: refreshed without asking (you chose it before).
#   A "no" is remembered in ${XDG_CONFIG_HOME:-~/.config}/logast/config.yaml (install.*) and not asked again.
#   -y, --yes                         yes to 1 and 2, without asking (3 only with --publish-to); forgets a "no" to them
#   --ask                             ask again what a remembered "no" (or an earlier remote publish) skips
#   --force                           rebuild and reinstall even when this commit is installed
#   --no-gradle                       no to 1, without asking
#   --starter                         yes to 2, without asking
#   --publish-to <name|url>           3 without asking: a repository name (./gradlew -q logastRepositories) or a URL
#   (a release zip, --zip, has nothing to publish: none of the three)

# ── output: colours only on a terminal, and never with NO_COLOR (set in main) ──────────────────────────────────────
B="" D="" G="" Y="" R="" C="" N=""
STEP=0
step() { STEP=$((STEP + 1)); printf '\n%s%d ›%s %s%s%s\n' "$C" "$STEP" "$N" "$B" "$1" "$N"; }
ok()   { printf '  %s✓%s %s\n' "$G" "$N" "$1"; }
info() { printf '  %s•%s %s\n' "$D" "$N" "$1"; }
dim()  { printf '  %s%s%s\n' "$D" "$1" "$N"; }
warn() { printf '  %s!%s %s\n' "$Y" "$N" "$1" >&2; }
fail() { printf '  %s✗%s %s\n' "$R" "$N" "$1" >&2; exit 1; }
home() { case "$1" in "$HOME"*) printf '~%s' "${1#"$HOME"}" ;; *) printf '%s' "$1" ;; esac; }
since() { local s=$(( $(date +%s) - $1 )); [ "$s" -ge 60 ] && printf '%dm %02ds' $((s / 60)) $((s % 60)) || printf '%ds' "$s"; }

# ── what is set up already (scripts/test-install.sh checks these) ─────────────────────────────────────────────────
INIT_MARKER="// logast init script"   # the first line of the file logast writes: only such a file is replaced

# a jar that can be read: not empty, and a zip (an interrupted publish leaves an empty file)
jar_ok() { [ -s "$1" ] && [ "$(head -c 2 "$1" 2>/dev/null)" = PK ]; }

# the value of a key in a .properties file, the last one wins
prop() {   # $1: key, $2: file
  [ -f "$2" ] || return 0
  local k="${1//./\\.}"
  sed -nE "s/^[[:space:]]*${k}[[:space:]]*[=:][[:space:]]*//p" "$2" | tail -1 | tr -d '\r' | sed -E 's/[[:space:]]+$//'
}

# Maven Local as Gradle's mavenLocal() finds it: -Dmaven.repo.local (GRADLE_OPTS, the Gradle home's gradle.properties, the
# checkout's), else <localRepository> in ~/.m2/settings.xml, else ~/.m2/repository
m2_repo() {   # $1: the checkout (for its gradle.properties)
  local v f
  v="$(printf '%s' "${GRADLE_OPTS:-}" | sed -nE 's/.*-Dmaven\.repo\.local=([^[:space:]]+).*/\1/p')"
  for f in "${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties" "${1:-.}/gradle.properties"; do
    [ -n "$v" ] || v="$(prop systemProp.maven.repo.local "$f")"
  done
  if [ -z "$v" ] && [ -f "$HOME/.m2/settings.xml" ]; then
    v="$(sed -nE 's/.*<localRepository>[[:space:]]*([^<]*[^<[:space:]])[[:space:]]*<\/localRepository>.*/\1/p' "$HOME/.m2/settings.xml" | head -1)"
    v="${v//\$\{user.home\}/$HOME}"
  fi
  printf '%s' "${v:-$HOME/.m2/repository}"
}

# the version logast's init script at $1 is for; nothing when the file is not logast's
init_version() {
  [ -f "$1" ] && [ "$(head -n 1 "$1" | cut -c1-${#INIT_MARKER})" = "$INIT_MARKER" ] || return 0
  sed -nE "s/^[[:space:]]*def version = '([^']*)'.*/\1/p" "$1" | head -1
}

# the Gradle setup: current (this version's init script, plugin, cli and core jars), stale (logast's init script, but
# another version or a missing or unusable jar), foreign (an init.d/logast.gradle logast did not write), none
gradle_state() {   # $1: version, $2: the init script, $3: Maven Local
  if [ ! -f "$2" ]; then echo none; return; fi
  if [ "$(head -n 1 "$2" | cut -c1-${#INIT_MARKER})" != "$INIT_MARKER" ]; then echo foreign; return; fi
  local m
  if [ "$(init_version "$2")" != "$1" ]; then echo stale; return; fi
  for m in gradle-plugin cli core facts; do   # the plugin and what it runs
    jar_ok "$3/dev/logast/$m/$1/$m-$1.jar" || { echo stale; return; }
  done
  echo current
}

# the support library in Maven Local: current (this version's jar), stale (another version's, or a broken one), none
support_state() {   # $1: version, $2: Maven Local
  local dir="$2/dev/logast/logging-support" d v
  if jar_ok "$dir/$1/logging-support-$1.jar"; then echo current; return; fi
  for d in "$dir"/*/; do
    [ -d "$d" ] || continue
    v="$(basename "$d")"
    if [ "$v" = "$1" ] || jar_ok "$d/logging-support-$v.jar"; then echo stale; return; fi
  done
  echo none
}

# ── the program's folder is shared with what logast keeps there (#135: fleets/ by default): an install replaces
#    only the entries the release brings (bin, lib, gradle, …), never the folder as a whole ─────────────────────────
replace_program() {   # $1: the unpacked release folder, $2: the program's folder
  local entry
  mkdir -p "$2"
  for entry in "$1"/* "$1"/.[!.]*; do
    [ -e "$entry" ] || continue
    rm -rf "${2:?}/$(basename "$entry")"
    mv "$entry" "$2/"
  done
}

# ── the user config: install.* keys, one line each; everything else in the file (fleets.*) is kept ───────────────
config_file() { printf '%s' "${XDG_CONFIG_HOME:-$HOME/.config}/logast/config.yaml"; }

cfg_get() {   # $1: key, $2: file → the value, unquoted
  [ -f "$2" ] || return 0
  local k="${1//./\\.}"
  sed -nE "s/^${k}:[[:space:]]*//p" "$2" | tail -1 | tr -d '\r' \
    | sed -E 's/[[:space:]]+#.*$//; s/[[:space:]]+$//; s/^"(.*)"$/\1/; s/^'"'"'(.*)'"'"'$/\1/'
}

cfg_unset() {   # $1: key, $2: file
  [ -f "$2" ] || return 0
  local k="${1//./\\.}" t
  grep -qE "^${k}:" "$2" || return 0
  t="$(mktemp "$2.XXXXXX")"
  grep -vE "^${k}:" "$2" >"$t" || true
  cat "$t" >"$2"; rm -f "$t"
}

cfg_set() {   # $1: key, $2: value (written as given: quote it yourself), $3: file
  cfg_unset "$1" "$3"
  mkdir -p "$(dirname "$3")"
  if [ -s "$3" ] && [ -n "$(tail -c 1 "$3")" ]; then printf '\n' >>"$3"; fi   # the last line had no newline
  printf '%s: %s\n' "$1" "$2" >>"$3"
}

is_no() { case "$1" in no|No|NO|n|N|false|False|FALSE) return 0 ;; *) return 1 ;; esac; }

# what to do about the Gradle setup or the support library in Maven Local:
#   no (a flag said so), current (done for this version), refresh (done for another: chosen before), yes (a flag said
#   so), remembered (a "no" from an earlier run), ask
decide() {   # $1: state, $2: flag (1 | 0 | ask), $3: the remembered answer, $4: 1 with --ask
  if [ "$2" = 0 ]; then echo no; return; fi
  case "$1" in
    current) echo current; return ;;
    stale) echo refresh; return ;;
  esac
  if [ "$2" = 1 ]; then echo yes; return; fi
  if is_no "$3" && [ "$4" != 1 ]; then echo remembered; return; fi
  echo ask
}

# what to do about publishing the support library to a remote repository:
#   given (--publish-to), published (this version went there before), remembered (a "no"), none (-y), ask
decide_remote() {   # $1: --publish-to, $2: 1 with -y, $3: the remembered value (no | <target>@<version>), $4: version, $5: 1 with --ask
  if [ -n "$1" ]; then echo given; return; fi
  if [ "$5" != 1 ]; then
    case "$3" in *@"$4") echo published; return ;; esac
    if is_no "$3"; then echo remembered; return; fi
  fi
  if [ "$2" = 1 ]; then echo none; return; fi
  echo ask
}

# a URL without user:password@, as it may be written down
without_credentials() { printf '%s' "$1" | sed -E 's#^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@]*@#\1#'; }

# the installed version names this checkout: same version and commit, and nothing changed since that commit
source_installed() {   # $1: the installed `logast --version`, $2: the version in gradle.properties
  local commit
  [ -n "$1" ] || return 1
  commit="$(git describe --always --dirty --abbrev=7 2>/dev/null)" || return 1
  [ -z "$(git status --porcelain 2>/dev/null)" ] || return 1
  [ "$1" = "logast $2 ($commit)" ]
}

# ── main ──────────────────────────────────────────────────────────────────────────────────────────────────────────
main() {
  set -euo pipefail
  PREFIX="${LOGAST_HOME:-$HOME/.local/share/logast}"
  BIN_DIR="${LOGAST_BIN:-$HOME/.local/bin}"
  ZIP="" SKILL=0 STARTER=ask GRADLE=ask YES=0 PUBLISH_TO="" ASK_AGAIN=0 FORCE=0
  while [ $# -gt 0 ]; do
    case "$1" in
      --zip) ZIP="${2:?--zip needs a file}"; shift ;;
      --skill) SKILL=1 ;;
      --starter) STARTER=1 ;;
      -y|--yes) YES=1 ;;
      --ask) ASK_AGAIN=1 ;;
      --force) FORCE=1 ;;
      --no-gradle) GRADLE=0 ;;
      --publish-to) PUBLISH_TO="${2:?--publish-to needs a repository name or URL}"; shift ;;
      -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
      *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
    shift
  done
  case "$ZIP" in ""|/*) ;; *) ZIP="$PWD/$ZIP" ;; esac   # before the cd below
  cd "$(dirname "$0")"

  if [ -t 1 ] && [ -z "${NO_COLOR:-}" ] && [ "${TERM:-}" != dumb ]; then
    B=$'\e[1m' D=$'\e[2m' G=$'\e[32m' Y=$'\e[33m' R=$'\e[31m' C=$'\e[36m' N=$'\e[0m'
  fi

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

  # ── 1. checks and choices ───────────────────────────────────────────────────────────────────────────────────────
  step "Checking the environment"
  # macOS ships a /usr/bin/java stub without a JDK: its output has no version, which counts as "missing".
  java_major="$(java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1 || true)"
  if [ -z "$java_major" ] || [ "$java_major" -lt 21 ]; then
    fail "logast needs Java 21+ on PATH (25 recommended)${java_major:+; found Java $java_major}"
  fi
  ok "Java $java_major ($(home "$(command -v java)"))$([ "$java_major" -lt 25 ] && printf ' %s· 25 recommended%s' "$D" "$N")"
  tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
  old="$([ -x "$PREFIX/bin/logast" ] && "$PREFIX/bin/logast" --version 2>/dev/null || true)"
  UPTODATE=0
  if [ -n "$ZIP" ]; then
    [ -f "$ZIP" ] || fail "no release zip at $ZIP"
    unzip -q "$ZIP" -d "$tmp/dist"
    target="$("$tmp"/dist/logast-*/bin/logast --version 2>/dev/null || true)"
    [ -n "$target" ] || fail "$(basename "$ZIP") holds no runnable logast"
    info "from the release zip $(basename "$ZIP"): $target"
    [ "$FORCE" = 0 ] && [ "$old" = "$target" ] && UPTODATE=1
    v="$(echo "$target" | cut -d' ' -f2)"
  else
    v="$(prop version gradle.properties)"
    info "from source: $(git describe --always --dirty --abbrev=7 2>/dev/null || echo 'this checkout')"
    [ "$FORCE" = 0 ] && source_installed "$old" "$v" && UPTODATE=1
  fi
  if [ "$UPTODATE" = 1 ]; then
    ok "$old is already installed — nothing to do (--force reinstalls)"
  elif [ -n "$old" ]; then
    info "installed now: $old (will be replaced)"
  else
    info "first install"
  fi

  GRADLE_INIT="${GRADLE_USER_HOME:-$HOME/.gradle}/init.d/logast.gradle"
  M2="$(m2_repo "$PWD")"
  CONFIG="$(config_file)"
  GRADLE_SKIPPED="" GRADLE_READY=0 STARTER_READY=0
  yes_no() { local a=""; printf '  %s [y/N] ' "$1"; read -r a || true; case "$a" in y|Y|yes|Yes|YES) return 0 ;; *) return 1 ;; esac; }
  remembered_no() { dim "$1 (skipped: you said no before; --ask to be asked again)"; }
  if [ "$YES" = 1 ]; then
    [ "$GRADLE" = ask ] && GRADLE=1
    [ "$STARTER" = ask ] && STARTER=1
  fi
  if [ -n "$ZIP" ]; then
    [ "$STARTER" = 1 ] && [ "$YES" = 0 ] && { echo "--starter needs a source build (omit --zip)" >&2; exit 2; }
    [ -n "$PUBLISH_TO" ] && { echo "--publish-to needs a source build (omit --zip)" >&2; exit 2; }
  fi

  # 1. Gradle setup
  gstate="$(gradle_state "$v" "$GRADLE_INIT" "$M2")"
  if [ -n "$ZIP" ]; then
    if [ "$gstate" = current ]; then
      GRADLE_READY=1; ok "Gradle: set up ($(home "$GRADLE_INIT"), plugin $v in $(home "$M2"))"
    elif [ "$GRADLE" != 0 ]; then
      GRADLE_SKIPPED="a release zip has no sources to publish the plugin from: run install.sh from a checkout"
    fi
    GRADLE=0
  else
    case "$(decide "$gstate" "$GRADLE" "$(cfg_get install.gradle "$CONFIG")" "$ASK_AGAIN")" in
      no) GRADLE=0 ;;
      current)
        if [ "$UPTODATE" = 1 ]; then
          GRADLE=0 GRADLE_READY=1; ok "Gradle: set up ($(home "$GRADLE_INIT"), plugin $v in $(home "$M2"))"
        else
          GRADLE=1; info "Gradle: set up; this build refreshes the plugin in $(home "$M2")"
        fi ;;
      refresh)
        GRADLE=1
        was="$(init_version "$GRADLE_INIT")"
        if [ -n "$was" ] && [ "$was" != "$v" ]; then info "Gradle: set up for logast $was; refreshing it for $v (you chose it before)"
        else info "Gradle: set up, but the plugin $v is missing or broken in $(home "$M2"); refreshing it"; fi ;;
      yes) GRADLE=1; cfg_unset install.gradle "$CONFIG" ;;
      remembered) GRADLE=0; remembered_no "Gradle setup" ;;
      ask)
        if [ -t 0 ]; then
          printf '\n  %sSet up Gradle too?%s Then %s./gradlew logastApply%s works in any Java build on this machine:\n' "$B" "$N" "$C" "$N"
          info "publishes dev.logast:facts, core, cli and gradle-plugin to Maven Local ($(home "$M2"))"
          info "writes $(home "$GRADLE_INIT"): adds dev.logast to builds with a logast.yaml"
          info "and wherever a logast task is asked for; -Plogast.disabled opts a build out"
          if yes_no "Proceed?"; then
            GRADLE=1; cfg_unset install.gradle "$CONFIG"
          else
            GRADLE=0; cfg_set install.gradle no "$CONFIG"
            GRADLE_SKIPPED="not confirmed, and remembered (rerun with -y to set it up, --ask to be asked)"
          fi
        else
          GRADLE=0; GRADLE_SKIPPED="no terminal to ask: rerun with -y to publish the plugin to Maven Local and write $(home "$GRADLE_INIT")"
        fi ;;
    esac
  fi

  # 2. the support library to Maven Local
  sstate="$(support_state "$v" "$M2")"
  if [ -n "$ZIP" ]; then
    [ "$sstate" = current ] && { STARTER_READY=1; ok "support library: $v in $(home "$M2")"; }
    STARTER=0
  else
    case "$(decide "$sstate" "$STARTER" "$(cfg_get install.supportLocal "$CONFIG")" "$ASK_AGAIN")" in
      no) STARTER=0 ;;
      current)
        if [ "$UPTODATE" = 1 ]; then
          STARTER=0 STARTER_READY=1; ok "support library: $v in $(home "$M2")"
        else
          STARTER=1; info "support library: in $(home "$M2"); this build refreshes it"
        fi ;;
      refresh) STARTER=1; info "support library: in $(home "$M2") for another version, or broken; refreshing it for $v (you chose it before)" ;;
      yes) STARTER=1; cfg_unset install.supportLocal "$CONFIG" ;;
      remembered) STARTER=0; remembered_no "support library to Maven Local" ;;
      ask)
        STARTER=0
        if [ -t 0 ]; then
          printf '\n  %sPublish the support library to Maven Local?%s Then %slogast apply%s adds the jar instead of copying its classes in:\n' "$B" "$N" "$C" "$N"
          info "publishes dev.logast:logging-support and test-capture to Maven Local ($(home "$M2"))"
          info "for builds on this machine that resolve from Maven Local (mavenLocal() among their repositories)"
          if yes_no "Proceed?"; then STARTER=1; cfg_unset install.supportLocal "$CONFIG"
          else cfg_set install.supportLocal no "$CONFIG"; fi
        fi ;;
    esac
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
  remote="$(cfg_get install.supportRemote "$CONFIG")"
  if [ -n "$ZIP" ]; then
    :
  else
    case "$(decide_remote "$PUBLISH_TO" "$YES" "$remote" "$v" "$ASK_AGAIN")" in
      given)
        case "$PUBLISH_TO" in
          *://*) publish_url "$PUBLISH_TO" ;;
          *) PUBLISH_NAME="$PUBLISH_TO"; PUBLISH_LABEL="$PUBLISH_TO" ;;
        esac ;;
      published) ok "support library: published to ${remote%@*}" ;;
      remembered) remembered_no "support library to a repository" ;;
      none) ;;
      ask)
        if [ -t 0 ]; then
          printf '\n  %sPublish the support library to a repository too?%s Then every build that resolves from there gets the jar:\n' "$B" "$N"
          case "$remote" in *@*) info "last published: ${remote%@*} (logast ${remote##*@})" ;; esac
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
          [ -z "$PUBLISH_LABEL" ] && cfg_set install.supportRemote no "$CONFIG"
        fi ;;
    esac
  fi

  # ── 2. build ────────────────────────────────────────────────────────────────────────────────────────────────────
  tasks=""
  [ -z "$ZIP" ] && [ "$UPTODATE" = 0 ] && tasks=":cli:distZip"
  [ "$STARTER" = 1 ] && tasks="$tasks :logging-support:publishToMavenLocal :test-capture:publishToMavenLocal"
  [ "$GRADLE" = 1 ] && tasks="$tasks :facts:publishToMavenLocal :core:publishToMavenLocal :cli:publishToMavenLocal :gradle-plugin:publishToMavenLocal"
  if [ -n "$tasks" ]; then
    if [ "$UPTODATE" = 1 ]; then step "Publishing to Maven Local"; else step "Building logast"; fi
    info "Gradle, quietly: the first build downloads dependencies and takes a few minutes"
    t0=$(date +%s)
    # shellcheck disable=SC2086 # task names, split on purpose
    ./gradlew -q $tasks </dev/null 2> >(grep -v '^Note: ' >&2) || fail "the build failed (its output is above)"
    if [ "$UPTODATE" = 0 ]; then
      ZIP="$(ls -t cli/build/distributions/logast-*.zip | head -1)"
      ok "built $(basename "$ZIP") in $(since "$t0")"
      unzip -q "$ZIP" -d "$tmp/dist"
    else
      ok "published to $(home "$M2") in $(since "$t0")"
    fi
  elif [ -n "$ZIP" ] && [ "$UPTODATE" = 0 ]; then
    step "Using the release zip"
    ok "$(basename "$ZIP")"
  fi

  # ── 3. install ──────────────────────────────────────────────────────────────────────────────────────────────────
  if [ "$UPTODATE" = 0 ]; then
    step "Installing the command"
    mkdir -p "$BIN_DIR"
    replace_program "$(echo "$tmp"/dist/logast-*)" "$PREFIX"
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
  else
    version="$old"
  fi
  if [ "$SKILL" = 1 ] || [ -f "$HOME/.claude/skills/logast/SKILL.md" ]; then   # re-runs refresh an installed skill
    mkdir -p "$HOME/.claude/skills/logast"
    cp "$PREFIX/claude-skill/logast/SKILL.md" "$HOME/.claude/skills/logast/SKILL.md"
    ok "Claude Code skill: ~/.claude/skills/logast/SKILL.md"
  fi
  [ "$STARTER" = 1 ] && ok "support library: dev.logast:logging-support:$v in $(home "$M2")"

  # ── 4. Gradle ───────────────────────────────────────────────────────────────────────────────────────────────────
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
    ok "plugin: dev.logast:gradle-plugin:$v (with core and cli) in $(home "$M2")"
    install_init_script "$PREFIX/gradle/logast.gradle" "$GRADLE_INIT"
    GRADLE_READY=1
  fi

  # ── 5. the support library to a repository ──────────────────────────────────────────────────────────────────────
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
    info "dev.logast:logging-support and test-capture to $(without_credentials "$PUBLISH_LABEL")"
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
      PUBLISHED="$(without_credentials "$PUBLISH_LABEL")"
      ok "published to $PUBLISHED in $(since "$t0")"
      cfg_set install.supportRemote "\"${PUBLISHED//\"/}@$v\"" "$CONFIG"
    else
      PUBLISH_FAILED="$(refusal "$log")"
      warn "not published to $(without_credentials "$PUBLISH_LABEL"): $PUBLISH_FAILED"
      info "Gradle's whole output: ./gradlew :logging-support:publishAllPublicationsToCompanyRepository $([ -n "$PUBLISH_NAME" ] && echo "-PrepoName=$PUBLISH_NAME" || echo "-PrepoUrl=$(without_credentials "$PUBLISH_URL")")"
    fi
  fi

  # ── summary ─────────────────────────────────────────────────────────────────────────────────────────────────────
  if [ "$UPTODATE" = 1 ]; then
    printf '\n%s%s✓ logast is up to date%s %s(%s)%s\n' "$B" "$G" "$N" "$D" "$version" "$N"
  else
    printf '\n%s%s✓ logast is installed%s %s(%s)%s\n' "$B" "$G" "$N" "$D" "$version" "$N"
  fi
  [ -n "$GRADLE_SKIPPED" ] && printf '  %sGradle setup skipped:%s %s\n' "$Y" "$N" "$GRADLE_SKIPPED"
  [ -n "$PUBLISHED" ] && printf '  %ssupport library:%s published to %s; apply adds the jar where a build resolves from there\n' "$G" "$N" "$PUBLISHED"
  [ -n "$PUBLISH_FAILED" ] && printf '  %ssupport library not published:%s %s\n' "$Y" "$N" "$PUBLISH_FAILED"
  case ":$PATH:" in *":$BIN_DIR:"*) ;; *) printf '  %s!%s add %s to PATH: export PATH="%s:$PATH"\n' "$Y" "$N" "$(home "$BIN_DIR")" "$BIN_DIR";; esac
  printf '\n%sNext, in a service:%s\n' "$B" "$N"
  printf '  %slogast apply .%s%s            migrate it (sets itself up; the result compiles)\n' "$C" "$N" "$([ "$GRADLE_READY" = 1 ] && printf ' %sor ./gradlew logastApply%s' "$C" "$N")"
  printf '  %sgit diff%s                  review the changes\n' "$C" "$N"
  printf '  %slogast review%s             optional: decide message texts, metrics, retries; then apply again\n' "$C" "$N"
  printf '  %slogast fleet join <name>%s  optional: compare it with your other services\n' "$C" "$N"
  printf '\n  %sDocs: README.md · docs/commands.md · docs/fleet.md · docs/platform.md (for platform teams)%s\n' "$D" "$N"
  [ -z "$PUBLISH_FAILED" ]   # a refused upload fails the run (scripts and CI see it); the command is installed either way
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
