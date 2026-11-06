#!/bin/sh
# Install mika-envd inside a single proot-distro layer, started from Termux.
#
# One PRoot layer only: proot-distro is already a PRoot environment. Do not
# start another proot inside it.
#
# The binary and data stay in ~/.mika-envd on the Termux side and are bound to
# /opt/mika. Each --allow-root SOURCE is bound to /mnt/mika/N, and envd is
# started with -allow-root /mnt/mika/N.

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ENVD_SRC=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
DATA="$HOME/.mika-envd"
LISTEN="127.0.0.1:18787"
DISTRO="ubuntu"
MODE="background"
BIN=""
STOP=0
ALLOW_FILE=""

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

have() {
    command -v "$1" >/dev/null 2>&1
}

cleanup() {
    if [ -n "$ALLOW_FILE" ]; then rm -f "$ALLOW_FILE"; fi
    return 0
}
trap cleanup EXIT HUP INT TERM

TMP_ROOT="${TMPDIR:-$TERMUX_PREFIX/tmp}"
mkdir -p "$TMP_ROOT" 2>/dev/null || TMP_ROOT="$HOME"

usage() {
    cat <<'EOF'
Usage: termux-proot-setup.sh [options]

Installs and starts mika-envd inside one proot-distro layer.

Options:
  --distro NAME        proot-distro alias (default: ubuntu; alpine works too).
  --binary PATH        Use an existing linux/arm64 binary.
  --data DIR           Termux-side data directory (default: ~/.mika-envd).
  --listen ADDR        Listen address (default: 127.0.0.1:18787).
  --allow-root DIR     Bind this directory into the distro and allow
                       env_create under it. Repeatable. Default:
                       ~/storage/shared/Mika.
  --foreground         Run attached to this terminal.
  --no-start           Configure only; do not start.
  --stop               Stop a previously started instance and exit.
  -h, --help           Show this help.

Notes:
  * proot-distro is already a PRoot layer; do not nest another proot.
  * -allow-root constrains file tools and env_create, not exec. exec can see
    the whole distro rootfs and every bound directory.
  * Keep data and tokens in ~/.mika-envd, not on /sdcard.
EOF
}

shell_quote() {
    printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --distro)
            [ $# -ge 2 ] || die "--distro needs a name"
            DISTRO=$2
            shift 2
            ;;
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
        --stop)
            STOP=1
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

if [ "$STOP" = 1 ]; then
    if [ -f "$DATA/proot.pid" ]; then
        PID=$(cat "$DATA/proot.pid")
        if kill -0 "$PID" 2>/dev/null; then
            kill "$PID" 2>/dev/null || true
            printf 'Stopped mika-envd proot process %s.\n' "$PID"
        else
            printf 'No running process for pid %s.\n' "$PID"
        fi
        rm -f "$DATA/proot.pid"
    else
        printf 'No pid file at %s/proot.pid.\n' "$DATA"
    fi
    have termux-wake-unlock && termux-wake-unlock || true
    exit 0
fi

[ -n "$ALLOW_FILE" ] || ALLOW_FILE=$(mktemp "$TMP_ROOT/mika-envd-allow.XXXXXX")

mkdir -p "$DATA"

if ! have proot-distro; then
    have pkg || die "proot-distro is required; install Termux packages first"
    printf 'Installing proot-distro...\n'
    pkg install -y proot-distro
fi

if [ -z "$BIN" ]; then
    if ! have go; then
        have pkg || die "Go is required; install it with: pkg install golang"
        printf 'Installing Go in Termux...\n'
        pkg install -y golang
    fi
    printf 'Building mika-envd for Termux...\n'
    (cd "$ENVD_SRC" && CGO_ENABLED=0 go build -trimpath -o "$DATA/mika-envd" .)
    BIN="$DATA/mika-envd"
fi

[ -f "$BIN" ] || die "binary not found: $BIN"

mkdir -p "$DATA/proot-data"
chmod 700 "$DATA" "$DATA/proot-data" 2>/dev/null || true
if [ "$BIN" != "$DATA/mika-envd" ]; then
    cp "$BIN" "$DATA/mika-envd"
fi
chmod 755 "$DATA/mika-envd"
cp "$SCRIPT_DIR/proot-install.sh" "$DATA/proot-install.sh"
chmod 755 "$DATA/proot-install.sh"

if [ -z "$(cat "$ALLOW_FILE")" ]; then
    if [ ! -d "$HOME/storage/shared" ]; then
        if have termux-setup-storage; then
            printf 'Requesting shared-storage access; approve the Android prompt.\n'
            termux-setup-storage || true
        fi
    fi
    if [ -d "$HOME/storage/shared" ]; then
        DEFAULT_ROOT="$HOME/storage/shared/Mika"
        mkdir -p "$DEFAULT_ROOT" 2>/dev/null || true
        if [ -d "$DEFAULT_ROOT" ]; then
            printf '%s\n' "$DEFAULT_ROOT" >> "$ALLOW_FILE"
            printf 'Using narrow default allow-root: %s\n' "$DEFAULT_ROOT"
        fi
    fi
fi

ROOTFS="$TERMUX_PREFIX/var/lib/proot-distro/installed-rootfs/$DISTRO"
if [ ! -d "$ROOTFS" ]; then
    printf 'Installing proot-distro %s...\n' "$DISTRO"
    proot-distro install "$DISTRO"
fi
[ -d "$ROOTFS" ] || die "proot-distro rootfs not found: $ROOTFS"

mkdir -p "$ROOTFS/opt/mika"
BIND_ARGS="--bind $(shell_quote "$DATA:/opt/mika")"
INDEX=0
ALLOW_LIST=""
while IFS= read -r root; do
    [ -n "$root" ] || continue
    case "$root" in
        /*) ;;
        *) root="$PWD/$root" ;;
    esac
    [ -d "$root" ] || die "allow-root is not a directory: $root"
    TARGET="/mnt/mika/$INDEX"
    mkdir -p "$ROOTFS$TARGET"
    BIND_ARGS="$BIND_ARGS --bind $(shell_quote "$root:$TARGET")"
    ALLOW_LIST="${ALLOW_LIST}${root} -> ${TARGET}
"
    INDEX=$((INDEX + 1))
done < "$ALLOW_FILE"

PROOT_BIN=$(command -v proot-distro)
INSTALL_RUNNER="$DATA/proot-install-runner.sh"
{
    printf '#!/bin/sh\n'
    printf 'exec %s login %s %s -- /bin/sh /opt/mika/proot-install.sh "$@"\n' \
        "$(shell_quote "$PROOT_BIN")" \
        "$(shell_quote "$DISTRO")" \
        "$BIND_ARGS"
} > "$INSTALL_RUNNER"
chmod 755 "$INSTALL_RUNNER"

set -- --binary /opt/mika/mika-envd --data /opt/mika/proot-data --listen "$LISTEN"
INDEX=0
while IFS= read -r root; do
    [ -n "$root" ] || continue
    set -- "$@" --allow-root "/mnt/mika/$INDEX"
    INDEX=$((INDEX + 1))
done < "$ALLOW_FILE"

sh "$INSTALL_RUNNER" "$@"

PROOT_RUN="$DATA/proot-run.sh"
{
    printf '#!/bin/sh\n'
    printf 'PATH=%s:$PATH\n' "$(shell_quote "$TERMUX_PREFIX/bin")"
    printf 'command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock\n'
    printf 'exec %s login %s %s -- /bin/sh /opt/mika/proot-data/run.sh\n' \
        "$(shell_quote "$PROOT_BIN")" \
        "$(shell_quote "$DISTRO")" \
        "$BIND_ARGS"
} > "$PROOT_RUN"
chmod 755 "$PROOT_RUN"

case "$LISTEN" in
    127.0.0.1:*|localhost:*|"[::1]":*) ;;
    *)
        printf 'warning: listening on %s is not loopback; keep the token private and use a TLS reverse proxy.\n' "$LISTEN" >&2
        ;;
esac

if [ "$MODE" = background ]; then
    if [ -f "$DATA/proot.pid" ] && kill -0 "$(cat "$DATA/proot.pid")" 2>/dev/null; then
        printf 'mika-envd proot is already running (pid %s).\n' "$(cat "$DATA/proot.pid")"
    else
        nohup sh "$PROOT_RUN" >> "$DATA/proot.log" 2>&1 &
        printf '%s\n' "$!" > "$DATA/proot.pid"
        printf 'Started mika-envd inside %s (pid %s).\n' "$DISTRO" "$!"
    fi
elif [ "$MODE" = foreground ]; then
    printf 'Starting mika-envd inside %s; Ctrl-C to stop.\n' "$DISTRO"
    exec sh "$PROOT_RUN"
fi

printf '\nMika envd is ready.\n'
printf '  distro:     %s\n' "$DISTRO"
printf '  binary:     %s (bound to /opt/mika/mika-envd)\n' "$DATA/mika-envd"
printf '  data:       %s (bound to /opt/mika/proot-data)\n' "$DATA/proot-data"
printf '  token file: %s\n' "$DATA/proot-data/token"
printf '  MCP URL:    http://%s/mcp\n' "$LISTEN"
printf '  binds:\n'
printf '%s' "$ALLOW_LIST" | while IFS= read -r line; do
    [ -n "$line" ] && printf '    %s\n' "$line"
done
printf '\nAdd the URL and the token file contents in Mika: Settings > MCP servers.\n'
printf 'Allow local HTTP for 127.0.0.1 in the MCP settings.\n'
printf 'This is one PRoot layer; do not nest another proot inside %s.\n' "$DISTRO"
printf 'To stop: sh %s --stop\n' "$0"
