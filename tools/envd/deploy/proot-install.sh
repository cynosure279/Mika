#!/bin/sh
# Configure mika-envd inside a proot-distro rootfs.
#
# This script is called by termux-proot-setup.sh through a single
# `proot-distro login` and is not started by itself. It creates/validates the
# data directory and token and writes the inner run.sh.

set -eu

BINARY="/opt/mika/mika-envd"
DATA="/opt/mika/proot-data"
LISTEN="127.0.0.1:18787"
READ_ONLY=0
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

usage() {
    cat <<'EOF'
Usage: proot-install.sh [options]

This script runs inside the proot distro. Use termux-proot-setup.sh from
Termux for the normal one-shot flow.

Options:
  --binary PATH        Binary path inside the distro (default: /opt/mika/mika-envd).
  --data DIR           Data directory (default: /opt/mika/proot-data).
  --listen ADDR        Listen address (default: 127.0.0.1:18787).
  --allow-root DIR     Bind target inside the distro. Repeatable.
  --read-only          Disable exec, writes, create, clone and delete.
  -h, --help           Show this help.
EOF
}

shell_quote() {
    printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --binary)
            [ $# -ge 2 ] || die "--binary needs a path"
            BINARY=$2
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
            [ -n "$ALLOW_FILE" ] || ALLOW_FILE=$(mktemp "/tmp/mika-envd-allow.XXXXXX")
            printf '%s\n' "$2" >> "$ALLOW_FILE"
            shift 2
            ;;
        --read-only)
            READ_ONLY=1
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

[ -n "$ALLOW_FILE" ] || ALLOW_FILE=$(mktemp "/tmp/mika-envd-allow.XXXXXX")
[ -x "$BINARY" ] || die "binary not executable: $BINARY (bind Termux ~/.mika-envd to /opt/mika)"

mkdir -p "$DATA"
chmod 700 "$DATA" 2>/dev/null || true

TOKEN_FILE="$DATA/token"
if [ ! -s "$TOKEN_FILE" ]; then
    umask 077
    if have openssl; then
        openssl rand -base64 48 | tr -d '\n' > "$TOKEN_FILE"
    elif have base64 && [ -r /dev/urandom ]; then
        head -c 48 /dev/urandom | base64 | tr -d '\n' > "$TOKEN_FILE"
    else
        die "cannot generate a token; install openssl or ca-certificates/base64"
    fi
    chmod 600 "$TOKEN_FILE"
fi
TOKEN_BYTES=$(wc -c < "$TOKEN_FILE" | tr -d ' ')
[ "$TOKEN_BYTES" -ge 32 ] || die "token file is shorter than 32 characters: $TOKEN_FILE"

set -- -listen "$LISTEN" -data "$DATA" -token-file "$TOKEN_FILE"
if [ "$READ_ONLY" = 1 ]; then
    set -- "$@" -read-only
fi

ALLOW_LIST=""
while IFS= read -r root; do
    [ -n "$root" ] || continue
    [ -d "$root" ] || die "allow-root is not a directory inside the distro: $root"
    set -- "$@" -allow-root "$root"
    ALLOW_LIST="${ALLOW_LIST}${root}
"
done < "$ALLOW_FILE"

RUN="$DATA/run.sh"
{
    printf '#!/bin/sh\n'
    printf 'exec %s' "$(shell_quote "$BINARY")"
    for arg in "$@"; do
        printf ' %s' "$(shell_quote "$arg")"
    done
    printf '\n'
} > "$RUN"
chmod 755 "$RUN"

printf '\nInner envd configuration written.\n'
printf '  binary:     %s\n' "$BINARY"
printf '  data:       %s\n' "$DATA"
printf '  token file: %s\n' "$TOKEN_FILE"
printf '  run script: %s\n' "$RUN"
printf '  listen:     %s\n' "$LISTEN"
if [ -n "$ALLOW_LIST" ]; then
    printf '  allow roots:\n'
    printf '%s' "$ALLOW_LIST" | while IFS= read -r root; do
        [ -n "$root" ] && printf '    %s\n' "$root"
    done
else
    printf '  allow roots: none\n'
fi
