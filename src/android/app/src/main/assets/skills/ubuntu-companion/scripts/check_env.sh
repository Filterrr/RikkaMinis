#!/bin/sh
# check_env.sh — one-shot Ubuntu sandbox health report (JSON on stdout).
#
# Zero-config companion diagnostics for the ubuntu-sandbox rootfs:
#   * os / kernel / cpu / memory
#   * presence of the tools scripts most often assume
#   * apt mirror currently active (parsed from the app-managed minis.sources)
#   * timezone consistency: TZ env vs /etc/localtime vs `date` offset
#   * outbound connectivity (pypi + github, HEAD, 5s cap)
#
# Exit code is 0 when nothing CRITICAL was found, 1 otherwise — usable as
# a gate in larger scripts. Human-readable detail goes to stderr; pass
# --quiet to silence it.

set -u
QUIET=0
[ "${1:-}" = "--quiet" ] && QUIET=1

note() { [ "$QUIET" = 1 ] || printf '%s\n' "$*" >&2; }

json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

# ── collect facts ────────────────────────────────────────────────────────────
PRETTY_NAME="$(. /etc/os-release 2>/dev/null && echo "${PRETTY_NAME:-unknown}")"
KERNEL="$(uname -r)"
ARCH="$(uname -m)"
CPUS="$(nproc 2>/dev/null || echo '?')"
MEM_MB="$(awk '/MemTotal/{printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo '?')"

# Tools: the usual suspects a "just write a quick script" request needs.
TOOLS="git python3 pip3 curl wget jq node npm gcc g++ make rg fd bat sqlite3 less vim unzip"
have=""
missing=""
for t in $TOOLS; do
    if command -v "$t" >/dev/null 2>&1; then
        have="$have $t"
    else
        missing="$missing $t"
    fi
done

# ── apt mirror ───────────────────────────────────────────────────────────────
SRC="/etc/apt/sources.list.d/minis.sources"
MIRROR_URI=""
MIRROR_ID="unknown"
if [ -f "$SRC" ]; then
    MIRROR_URI="$(awk '/^URIs:/{print $2; exit}' "$SRC")"
    case "$MIRROR_URI" in
        *tuna.tsinghua*)     MIRROR_ID="tuna" ;;
        *ustc.edu.cn*)       MIRROR_ID="ustc" ;;
        *aliyun.com*)        MIRROR_ID="aliyun" ;;
        *huaweicloud.com*)   MIRROR_ID="huawei" ;;
        *cloud.tencent.com*) MIRROR_ID="tencent" ;;
        *nju.edu.cn*)        MIRROR_ID="nju" ;;
        *bfsu.edu.cn*)       MIRROR_ID="bfsu" ;;
        *ports.ubuntu.com*)  MIRROR_ID="official" ;;
        *)                   MIRROR_ID="unknown" ;;
    esac
fi

# ── timezone consistency ─────────────────────────────────────────────────────
# TZ env (POSIX form, e.g. LCL-8) vs /etc/localtime target vs date output.
TZ_ENV="${TZ:-}"
LOCALTIME_LINK="$(readlink /etc/localtime 2>/dev/null || echo '(regular file/absent)')"
DATE_LOCAL="$(date +%z 2>/dev/null)"
# Consistent when localtime points at a real zone and `date` agrees with
# the zone the file names (i.e. env TZ and /etc/localtime render the same
# offset right now).
TZ_CONSISTENT="unknown"
if [ -n "$TZ_ENV" ] && [ -n "$DATE_LOCAL" ] && [ -e "$LOCALTIME_LINK" ]; then
    ZF_OFFSET="$(TZ=":$LOCALTIME_LINK" date +%z 2>/dev/null)"
    [ "$ZF_OFFSET" = "$DATE_LOCAL" ] && TZ_CONSISTENT="true" || TZ_CONSISTENT="false"
fi

# ── connectivity ─────────────────────────────────────────────────────────────
PYPI_CODE="$(curl -s -o /dev/null --max-time 5 -w '%{http_code}' https://pypi.org/simple/ 2>/dev/null)"
GH_CODE="$(curl -s -o /dev/null --max-time 5 -w '%{http_code}' https://github.com 2>/dev/null)"

# ── verdict ──────────────────────────────────────────────────────────────────
CRITICAL=""
[ "$MIRROR_ID" = "unknown" ] && CRITICAL="$CRITICAL apt-mirror-unreadable"
[ "$TZ_CONSISTENT" = "false" ] && CRITICAL="$CRITICAL tz-inconsistent"
[ "$PYPI_CODE" != "200" ] && [ "$PYPI_CODE" != "301" ] && [ "$PYPI_CODE" != "302" ] && CRITICAL="$CRITICAL no-egress-pypi"
[ "$GH_CODE" != "200" ] && [ "$GH_CODE" != "301" ] && [ "$GH_CODE" != "302" ] && CRITICAL="$CRITICAL no-egress-github"

note "── Ubuntu Sandbox Health ──"
note "os:        $PRETTY_NAME ($KERNEL, $ARCH)"
note "resources: ${CPUS} cpus, ${MEM_MB} MB mem"
note "tools:     have:$(echo $have | tr ' ' '\n' | sed 's/^/ /' | tr -d '\n')"
note "           missing:${missing}   (install: apt-get install -y <pkg>)"
note "apt:       mirror=$MIRROR_ID ($MIRROR_URI)"
note "timezone:  TZ=$TZ_ENV localtime=$LOCALTIME_LINK date=$DATE_LOCAL consistent=$TZ_CONSISTENT"
note "network:   pypi=$PYPI_CODE github=$GH_CODE"
note "verdict:   ${CRITICAL:-all clear}"

# ── emit JSON ────────────────────────────────────────────────────────────────
missing_json="$(printf '%s' "$missing" | tr ' ' '\n' | sed '/^$/d' | sed 's/.*/"&"/' | tr '\n' ',' | sed 's/,$//')"
printf '{"os":"%s","kernel":"%s","arch":"%s","cpus":"%s","mem_mb":"%s","tools_missing":[%s],"apt_mirror":{"id":"%s","uri":"%s"},"timezone":{"tz_env":"%s","localtime":"%s","date_offset":"%s","consistent":"%s"},"network":{"pypi":"%s","github":"%s"},"critical":"%s"}\n' \
    "$(json_escape "$PRETTY_NAME")" "$(json_escape "$KERNEL")" "$ARCH" "$CPUS" "$MEM_MB" \
    "$missing_json" \
    "$MIRROR_ID" "$(json_escape "$MIRROR_URI")" \
    "$(json_escape "$TZ_ENV")" "$(json_escape "$LOCALTIME_LINK")" "$DATE_LOCAL" "$TZ_CONSISTENT" \
    "$PYPI_CODE" "$GH_CODE" "$(json_escape "${CRITICAL# }")"

[ -z "$CRITICAL" ]
