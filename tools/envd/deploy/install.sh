#!/bin/sh
# Install mika-envd on Linux or macOS.
#
# Builds the binary (or copies --binary), creates a data directory and a token,
# optionally installs a systemd user unit or launchd agent, and starts it.
# Run with --help for all options.

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ENVD_SRC=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

PREFIX="${HOME}/.local/bin"
DATA="${HOME}/.mika-envd"
LISTEN="127.0.0.1:8787"
SERVICE="auto"
READ_ONLY=0
START=1
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

usage() {
    cat <<'EOF'
Usage: install.sh [options]

Installs mika-envd and optionally a per-user service.

Options:
  --binary PATH        Use an existing binary instead of building from source.
  --prefix DIR         Install the binary here (default: ~/.local/bin).
  --data DIR           Data directory (default: ~/.mika-envd).
  --listen ADDR        Listen address (default: 127.0.0.1:8787).
  --allow-root DIR     Allow env_create to attach directories under DIR.
                       Repeatable.
  --read-only          Disable exec, writes, create, clone and delete.
  --service MODE       auto, systemd, launchd or none (default: auto).
  --no-start           Install and configure, but do not start the service.
  -h, --help           Show this help.

Examples:
  sh deploy/install.sh --allow-root /srv/projects
  sh deploy/install.sh --service none
  sh deploy/install.sh --binary ./mika-envd --data /opt/mika-envd
EOF
}

systemd_quote() {
    printf '"%s"' "$(printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"
}

xml_escape() {
    printf '%s' "$1" | sed \
        -e 's/&/\&amp;/g' \
        -e 's/</\&lt;/g' \
        -e 's/>/\&gt;/g' \
        -e 's/"/\&quot;/g' \
        -e "s/'/\&apos;/g"
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
        --prefix)
            [ $# -ge 2 ] || die "--prefix needs a path"
            PREFIX=$2
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
            [ -n "$ALLOW_FILE" ] || ALLOW_FILE=$(mktemp "${TMPDIR:-/tmp}/mika-envd-allow.XXXXXX")
            printf '%s\n' "$2" >> "$ALLOW_FILE"
            shift 2
            ;;
        --read-only)
            READ_ONLY=1
            shift
            ;;
        --service)
            [ $# -ge 2 ] || die "--service needs a mode"
            SERVICE=$2
            shift 2
            ;;
        --no-start)
            START=0
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

case "$SERVICE" in
    auto|systemd|launchd|none) ;;
    *) die "--service must be auto, systemd, launchd or none" ;;
esac

case "$PREFIX" in
    /*) ;;
    *) PREFIX="$PWD/$PREFIX" ;;
esac
case "$DATA" in
    /*) ;;
    *) DATA="$PWD/$DATA" ;;
esac

[ -n "$ALLOW_FILE" ] || ALLOW_FILE=$(mktemp "${TMPDIR:-/tmp}/mika-envd-allow.XXXXXX")

if [ -z "$BIN" ]; then
    have go || die "Go is required to build mika-envd; pass --binary or install Go"
    TMP_BIN=$(mktemp "${TMPDIR:-/tmp}/mika-envd.XXXXXX")
    printf 'Building mika-envd...\n'
    (cd "$ENVD_SRC" && CGO_ENABLED=0 go build -trimpath -o "$TMP_BIN" .)
    BIN=$TMP_BIN
fi

[ -f "$BIN" ] || die "binary not found: $BIN"

mkdir -p "$PREFIX" "$DATA"
chmod 700 "$DATA" 2>/dev/null || true
if [ "$BIN" != "$PREFIX/mika-envd" ]; then
    install -m 0755 "$BIN" "$PREFIX/mika-envd"
fi

TOKEN_FILE="$DATA/token"
if [ ! -s "$TOKEN_FILE" ]; then
    umask 077
    if have openssl; then
        openssl rand -base64 48 | tr -d '\n' > "$TOKEN_FILE"
    elif have base64 && [ -r /dev/urandom ]; then
        head -c 48 /dev/urandom | base64 | tr -d '\n' > "$TOKEN_FILE"
    else
        die "cannot generate a token; install openssl or use --token-file manually"
    fi
    chmod 600 "$TOKEN_FILE"
fi
chmod 600 "$TOKEN_FILE" 2>/dev/null || true
TOKEN_BYTES=$(wc -c < "$TOKEN_FILE" | tr -d ' ')
[ "$TOKEN_BYTES" -ge 32 ] || die "token file is shorter than 32 characters: $TOKEN_FILE"

set -- -listen "$LISTEN" -data "$DATA" -token-file "$TOKEN_FILE"
if [ "$READ_ONLY" = 1 ]; then
    set -- "$@" -read-only
fi

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

case "$LISTEN" in
    127.0.0.1:*|localhost:*|"[::1]":*) ;;
    *)
        printf 'warning: listening on %s is not loopback; put a TLS reverse proxy in front and keep the token private.\n' "$LISTEN" >&2
        ;;
esac

if [ "$SERVICE" = auto ]; then
    if [ "$(uname -s)" = Darwin ] && have launchctl; then
        SERVICE=launchd
    elif have systemctl && systemctl --user show-environment >/dev/null 2>&1; then
        SERVICE=systemd
    else
        SERVICE=none
    fi
fi

if [ "$SERVICE" = systemd ]; then
    UNIT_DIR="$HOME/.config/systemd/user"
    UNIT="$UNIT_DIR/mika-envd.service"
    mkdir -p "$UNIT_DIR"
    EXEC_LINE=$(systemd_quote "$PREFIX/mika-envd")
    for arg in "$@"; do
        EXEC_LINE="$EXEC_LINE $(systemd_quote "$arg")"
    done
    cat > "$UNIT" <<EOF
[Unit]
Description=Mika envd
After=network.target

[Service]
Type=simple
ExecStart=$EXEC_LINE
Restart=on-failure
RestartSec=2

[Install]
WantedBy=default.target
EOF
    systemctl --user daemon-reload
    if [ "$START" = 1 ]; then
        systemctl --user enable --now mika-envd.service
        printf 'systemd user service started: %s\n' "$UNIT"
        printf 'To keep it running after logout: loginctl enable-linger %s\n' "$USER"
    else
        printf 'systemd user unit written: %s\n' "$UNIT"
        printf 'Start it with: systemctl --user enable --now mika-envd.service\n'
    fi
elif [ "$SERVICE" = launchd ]; then
    PLIST="$HOME/Library/LaunchAgents/com.mika.envd.plist"
    mkdir -p "$(dirname "$PLIST")"
    {
        printf '<?xml version="1.0" encoding="UTF-8"?>\n'
        printf '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n'
        printf '<plist version="1.0">\n<dict>\n'
        printf '  <key>Label</key><string>com.mika.envd</string>\n'
        printf '  <key>ProgramArguments</key>\n  <array>\n'
        printf '    <string>%s</string>\n' "$(xml_escape "$PREFIX/mika-envd")"
        for arg in "$@"; do
            printf '    <string>%s</string>\n' "$(xml_escape "$arg")"
        done
        printf '  </array>\n'
        printf '  <key>RunAtLoad</key><true/>\n'
        printf '  <key>KeepAlive</key><true/>\n'
        printf '  <key>StandardOutPath</key><string>%s</string>\n' "$(xml_escape "$DATA/envd.log")"
        printf '  <key>StandardErrorPath</key><string>%s</string>\n' "$(xml_escape "$DATA/envd.log")"
        printf '</dict>\n</plist>\n'
    } > "$PLIST"
    if [ "$START" = 1 ]; then
        launchctl unload "$PLIST" 2>/dev/null || true
        launchctl load -w "$PLIST"
        printf 'launchd agent started: %s\n' "$PLIST"
    else
        printf 'launchd agent written: %s\n' "$PLIST"
        printf 'Load it with: launchctl load -w %s\n' "$PLIST"
    fi
fi

printf '\nMika envd is ready.\n'
printf '  binary:     %s\n' "$PREFIX/mika-envd"
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

if [ "$START" = 1 ] && [ "$SERVICE" = none ]; then
    printf '\nStarting in the foreground; Ctrl-C to stop.\n'
    exec "$PREFIX/mika-envd" "$@"
fi
