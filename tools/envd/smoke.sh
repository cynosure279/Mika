#!/usr/bin/env sh
# Builds the real binary, starts it on loopback and drives an MCP session.
# Requires go, curl and python3. Used by CI and usable locally on Linux/macOS.
set -eu

cd "$(dirname "$0")"

tmp=$(mktemp -d)
pid=""
cleanup() {
    if [ -n "$pid" ]; then kill "$pid" 2>/dev/null || true; fi
    rm -rf "$tmp"
}
trap cleanup EXIT INT TERM

go build -trimpath -o "$tmp/mika-envd" .
token=$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')
printf '%s' "$token" >"$tmp/token"

"$tmp/mika-envd" -listen 127.0.0.1:18787 -data "$tmp/data" -token-file "$tmp/token" >"$tmp/log" 2>&1 &
pid=$!

rpc() {
    method=$1
    params=$2
    if [ -n "$params" ]; then
        body=$(printf '{"jsonrpc":"2.0","id":1,"method":"%s","params":%s}' "$method" "$params")
    else
        body=$(printf '{"jsonrpc":"2.0","id":1,"method":"%s"}' "$method")
    fi
    curl -sS -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
        -d "$body" "http://127.0.0.1:18787/mcp"
}

tries=0
while [ "$tries" -lt 50 ]; do
    if rpc ping "" >/dev/null 2>&1; then break; fi
    tries=$((tries + 1))
    sleep 0.1
done
if [ "$tries" -ge 50 ]; then
    echo "envd did not start" >&2
    cat "$tmp/log" >&2
    exit 1
fi

init=$(rpc initialize '{"protocolVersion":"2025-03-26"}')
printf '%s' "$init" | grep -q '"serverInfo"'
printf '%s' "$init" | grep -q '"protocolVersion":"2025-03-26"'

created=$(rpc tools/call '{"name":"env_create","arguments":{"name":"smoke"}}')
env_id=$(printf '%s' "$created" | python3 -c 'import json,sys; r=json.load(sys.stdin); print(json.loads(r["result"]["content"][0]["text"])["environment"]["id"])')
[ -n "$env_id" ]

exec_out=$(rpc tools/call "{\"name\":\"exec\",\"arguments\":{\"env\":\"$env_id\",\"command\":\"printf smoke\"}}")
printf '%s' "$exec_out" | python3 -c 'import json,sys; r=json.load(sys.stdin); t=json.loads(r["result"]["content"][0]["text"]); assert t["ok"] and t["exitCode"] == 0 and t["stdout"] == "smoke", t'

rpc tools/call "{\"name\":\"write_file\",\"arguments\":{\"env\":\"$env_id\",\"path\":\"hello.txt\",\"content\":\"hello\"}}" >/dev/null
read_out=$(rpc tools/call "{\"name\":\"read_file\",\"arguments\":{\"env\":\"$env_id\",\"path\":\"hello.txt\"}}")
printf '%s' "$read_out" | python3 -c 'import json,sys; r=json.load(sys.stdin); t=json.loads(r["result"]["content"][0]["text"]); assert t["content"] == "hello", t'

clone_out=$(rpc tools/call "{\"name\":\"env_clone\",\"arguments\":{\"source\":\"$env_id\",\"name\":\"smoke-copy\"}}")
printf '%s' "$clone_out" | python3 -c 'import json,sys; r=json.load(sys.stdin); t=json.loads(r["result"]["content"][0]["text"]); assert t["environment"]["attached"] is False, t'

stream=$(curl -sS -N -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"exec\",\"arguments\":{\"env\":\"$env_id\",\"command\":\"echo stream1; sleep 0.2; echo stream2\"},\"_meta\":{\"progressToken\":\"p\"}}}" \
    "http://127.0.0.1:18787/mcp")
printf '%s' "$stream" | grep -q 'notifications/progress'
printf '%s' "$stream" | grep -q '"id":9'

echo "envd smoke ok"
