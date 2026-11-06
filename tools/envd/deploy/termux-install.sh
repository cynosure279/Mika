#!/bin/sh
# Install mika-envd in Termux (one layer, no proot).
#
# Builds the arm64 binary, requests shared-storage access, creates a narrow
# default -allow-root, and starts envd in the background with a wake lock.
# For a full Linux userland use termux-proot-setup.sh instead.

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ENVD_SRC=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
BIN_DEST="$TERMUX_PREFIX/bin/mika-envd"
DATA="$HOME/.mika-envd"
LISTEN="127.0.0.1:18787"
MODE="background"
BIN=""
ALLOW_FILE=""
TMP_BIN=""

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

have() {
    command -v "$1" >/dev/null 2>&1
}

cleanup() {
    if [ -n "$ALLOW_FILE" ]; then rm -f "$ALLOW_FILE"; fi
    if [ -n "$TMP_BIN" ]; then rm -f "$TMP_BIN"; fi
    return 0
}
trap cleanup EXIT HUP INT TERM

TMP_ROOT="${TMPDIR:-$TERMUX_PREFIX/tmp}"
mkdir -p "$TMP_ROOT" 2>/dev/null || TMP_ROOT="$HOME"

usage() {
    cat <<'EOF'
Usage: termux-install.sh [options]

Options:
  --binary PATH        Use an existing linux/arm64 binary.
  --data DIR           Data directory (default: ~/.mika-envd).
  --listen ADDR        Listen address (default: 127.0.0.1:18787).
  --allow-root DIR     Allow env_create to attach directories under DIR.
                       Repeatable. Default: ~/storage/shared/Mika when
                       termux-setup-storage has been granted.
  --foreground         Run in the terminal instead of the background.
  --no-start           Configure only; do not start envd.
  -h, --help           Show this help.

This is the plain-Termux setup. exec uses Android's /bin/sh and a fixed Linux
PATH, so tools installed with pkg may not be visible. Use
termux-proot-setup.sh when you need a full Linux userland.
EOF
}

shell_quote() {
    printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --binary)
            [ $# -ge 2 ] || die "--binary needs a path"
            BIN=$2
            shift 2
            ;;
        --data)
            [ $# -ge 2 ] || die "--data needs a path"
            DATA=$2
            shift 2
            ;;
        --listen)
            [ $# -ge 2 ] || die "--listen needs an address"
            LISTEN=$2
            shift 2
            ;;
        --allow-root)
            [ $# -ge 2 ] || die "--allow-root needs a directory"
            [ -n "$ALLOW_FILE" ] || ALLOW_FILE=$(mktemp "$TMP_ROOT/mika-envd-allow.XXXXXX")
            printf '%s\n' "$2" >> "$ALLOW_FILE"
            shift 2
            ;;
        --foreground)
            MODE="foreground"
            shift
            ;;
        --no-start)
            MODE="none"
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "unknown option: $1 (try --help)"
            ;;
    esac
done

case "${PREFIX:-}" in
    *com.termux*) ;;
    *)
        printf 'warning: this does not look like Termux (PREFIX=%s); continuing anyway.\n' "${PREFIX:-unset}" >&2
        ;;
esac

case "$DATA" in
    /*) ;;
    *) DATA="$PWD/$DATA" ;;
esac

[ -n "$ALLOW_FILE" ] || ALLOW_FILE=$(mktemp "$TMP_ROOT/mika-envd-allow.XXXXXX")

if [ -z "$BIN" ]; then
    if ! have go; then
        have pkg || die "Go is required; install it with: pkg install golang"
        printf 'Installing Go in Termux...\n'
        pkg install -y golang
    fi
    TMP_BIN=$(mktemp "$TMP_ROOT/mika-envd.XXXXXX")
    printf 'Building mika-envd for Termux...\n'
    (cd "$ENVD_SRC" && CGO_ENABLED=0 go build -trimpath -o "$TMP_BIN" .)
    BIN=$TMP_BIN
fi

[ -f "$BIN" ] || die "binary not found: $BIN"

mkdir -p "$DATA" "$TERMUX_PREFIX/bin"
chmod 700 "$DATA" 2>/dev/null || true
if [ "$BIN" != "$BIN_DEST" ]; then
    cp "$BIN" "$BIN_DEST"
fi
chmod 755 "$BIN_DEST"

TOKEN_FILE="$DATA/token"
if [ ! -s "$TOKEN_FILE" ]; then
    umask 077
    if have openssl; then
        openssl rand -base64 48 | tr -d '\n' > "$TOKEN_FILE"
    elif have base64 && [ -r /dev/urandom ]; then
        head -c 48 /dev/urandom | base64 | tr -d '\n' > "$TOKEN_FILE"
    else
        die "cannot generate a token; install openssl"
    fi
    chmod 600 "$TOKEN_FILE"
fi
chmod 600 "$TOKEN_FILE" 2>/dev/null || true
TOKEN_BYTES=$(wc -c < "$TOKEN_FILE" | tr -d ' ')
[ "$TOKEN_BYTES" -ge 32 ] || die "token file is shorter than 32 characters: $TOKEN_FILE"

if [ -z "$(cat "$ALLOW_FILE")" ]; then
    if [ ! -d "$HOME/storage/shared" ]; then
        if have termux-setup-storage; then
            printf 'Requesting shared-storage access; approve the Android prompt.\n'
            termux-setup-storage || true
        fi
    fi
    if [ -d "$HOME/storage/shared" ]; then
        DEFAULT_ROOT="$HOME/storage/shared/Mika"
        if mkdir -p "$DEFAULT_ROOT" 2>/dev/null; then
            printf '%s\n' "$DEFAULT_ROOT" >> "$ALLOW_FILE"
            printf 'Using narrow default allow-root: %s\n' "$DEFAULT_ROOT"
        else
            printf 'warning: could not create %s; pass --allow-root explicitly.\n' "$DEFAULT_ROOT" >&2
        fi
    else
        printf 'warning: shared storage is not set up; pass --allow-root explicitly.\n' >&2
    fi
fi

set -- -listen "$LISTEN" -data "$DATA" -token-file "$TOKEN_FILE"
ALLOW_LIST=""
while IFS= read -r root; do
    [ -n "$root" ] || continue
    case "$root" in
        /*) ;;
        *) root="$PWD/$root" ;;
    esac
    [ -d "$root" ] || die "allow-root is not a directory: $root"
    set -- "$@" -allow-root "$root"
    ALLOW_LIST="${ALLOW_LIST}${root}
"
done < "$ALLOW_FILE"

RUN="$DATA/run.sh"
{
    printf '#!/bin/sh\n'
    printf 'command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock\n'
    printf 'exec %s' "$(shell_quote "$BIN_DEST")"
    for arg in "$@"; do
        printf ' %s' "$(shell_quote "$arg")"
    done
    printf '\n'
} > "$RUN"
chmod 755 "$RUN"

case "$LISTEN" in
    127.0.0.1:*|localhost:*|"[::1]":*) ;;
    *)
        printf 'warning: listening on %s is not loopback; keep the token private and use a TLS reverse proxy.\n' "$LISTEN" >&2
        ;;
esac

if [ "$MODE" = background ]; then
    if [ -f "$DATA/envd.pid" ] && kill -0 "$(cat "$DATA/envd.pid")" 2>/dev/null; then
        printf 'mika-envd is already running (pid %s).\n' "$(cat "$DATA/envd.pid")"
    else
        nohup sh "$RUN" >> "$DATA/envd.log" 2>&1 &
        printf '%s\n' "$!" > "$DATA/envd.pid"
        printf 'Started mika-envd in the background (pid %s).\n' "$!"
    fi
elif [ "$MODE" = foreground ]; then
    printf 'Starting mika-envd in the foreground; Ctrl-C to stop.\n'
    exec sh "$RUN"
fi

printf '\nMika envd is ready.\n'
printf '  binary:     %s\n' "$BIN_DEST"
printf '  data:       %s\n' "$DATA"
printf '  token file: %s\n' "$TOKEN_FILE"
printf '  MCP URL:    http://%s/mcp\n' "$LISTEN"
if [ -n "$ALLOW_LIST" ]; then
    printf '  allow roots:\n'
    printf '%s' "$ALLOW_LIST" | while IFS= read -r root; do
        [ -n "$root" ] && printf '    %s\n' "$root"
    done
else
    printf '  allow roots: none (env_create only creates managed workspaces)\n'
fi
printf '\nAdd the URL and the token file contents in Mika: Settings > MCP servers.\n'
printf 'Allow local HTTP for 127.0.0.1 in the MCP settings.\n'
printf 'To stop: kill "$(cat %s/envd.pid)" and run termux-wake-unlock.\n' "$DATA"
printf 'For a full Linux userland, use termux-proot-setup.sh instead.\n'
