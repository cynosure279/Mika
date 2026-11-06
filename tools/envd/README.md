# mika-envd

A small self-hosted execution server for Mika. It speaks MCP over HTTP and lets a phone run commands and read/write files in named workspaces on another machine.

Status: experimental. It has a workspace backend, runs as a static binary on Linux/macOS/Windows/Termux, can attach existing directories under explicit `-allow-root` paths, streams `exec` progress over MCP, and supports large-file transfer over signed HTTPS links. It does **not** replace the Alpine sandbox on the phone, and it does not provide phone control.

The developer contract and threat model are in [`docs/dev/remote-env.md`](../../docs/dev/remote-env.md). Convenience installers for Linux/macOS, Windows, Termux and a single-layer Termux + proot-distro setup are in [`deploy/`](deploy/).

## Quick start

### One-shot installers

```sh
# Linux or macOS: build, token, systemd user unit or launchd agent
sh tools/envd/deploy/install.sh --allow-root /srv/projects

# Termux: plain Termux, narrow default allow-root, background + wake lock
sh tools/envd/deploy/termux-install.sh

# Termux + one proot-distro layer (full Linux userland)
sh tools/envd/deploy/termux-proot-setup.sh --distro ubuntu
```

```powershell
# Windows: install and optionally register a per-user scheduled task
pwsh -File tools\envd\deploy\install.ps1 -Service
```

See [`deploy/README.md`](deploy/README.md) for flags and the proot boundaries.

### Desktop: container wrapper (recommended)

```sh
cd tools/envd
cp .env.example .env
printf 'ENVD_TOKEN=%s\n' "$(openssl rand -base64 48)" > .env
chmod 600 .env
docker compose up -d --build
```

The container runs envd as root with only `SETUID`/`SETGID`, a read-only root filesystem, `cap_drop: ALL` and `no-new-privileges`, and listens on `127.0.0.1:8787` only. Commands run as uid 10002 in group 10001, so they cannot read the token (it is passed in `ENVD_TOKEN`, not a file). Put your own TLS reverse proxy in front before exposing it to another network.

The token is visible to the Docker daemon/admin through `docker inspect`, so keep `.env` private and rotate the token if it leaks.

### Bare metal

```sh
go build -trimpath -o mika-envd .
./mika-envd -listen 127.0.0.1:8787 -data ./data -token-file ./envd_token
```

`-token-env` is also supported. To isolate commands, start envd as root with `-exec-user 10002 -exec-group 10001`; the data directory must be writable by that group. Without `-exec-user`, commands run as the envd user and can read the token file.

There is no isolation beyond that: `exec` runs as the selected user. Use a dedicated user/directory, or `-read-only` when only reads are needed.

### Termux / phone

Plain Termux works, but its `exec` is only partially useful: envd uses
`/bin/sh` and a fixed Linux `PATH`, while Termux tools live in `$PREFIX/bin`.
File tools are unaffected. Use the installer to build and start it:

```sh
sh tools/envd/deploy/termux-install.sh
```

For a full Linux userland, run a single proot-distro layer and let the setup
script bind the binary, data and allowed directories into it:

```sh
sh tools/envd/deploy/termux-proot-setup.sh --distro ubuntu
```

That script keeps the binary/data under `~/.mika-envd`, binds it to
`/opt/mika`, binds each `--allow-root` source to `/mnt/mika/N`, and starts
envd with `-allow-root /mnt/mika/N`. Do not nest another proot inside the
distro. See [`deploy/README.md`](deploy/README.md) for the security boundaries:
`-allow-root` does not constrain `exec`, and PRoot is not a sandbox.

Then connect Mika to `http://127.0.0.1:18787/mcp` (allow local HTTP in MCP
settings). Termux background execution and storage permissions depend on the
Android version and the Termux build; battery optimization, wake locks and the
Android 12+ phantom-process killer matter.

## Attach an existing directory

`-allow-root` can be repeated. Without it, `env_create` only creates managed workspaces.

```sh
./mika-envd ... -allow-root /srv/projects -allow-root /srv/data
```

Then call `env_create` with `path: "/srv/projects/my-app"`. The environment points at that directory:

- `env_delete` removes only the metadata; the directory and its files stay.
- `env_clone` copies the directory into a new managed workspace.
- At startup, attached environments must still be under a configured `-allow-root`; otherwise envd refuses to start.

## Configure Mika

Settings › MCP servers › Add:

- URL: `https://<your-host>/mcp` (or `http://127.0.0.1:<port>/mcp` for localhost)
- Token: the contents of `envd_token`

Mika sends `Authorization: Bearer <token>`. After a successful test, the task gets tools such as `mcp_<name>__exec`, `mcp_<name>__read_file` and `mcp_<name>__env_list`.

## Tools

| Tool | Purpose |
| --- | --- |
| `env_info`, `env_list` | Server and environment information |
| `env_create` (`name`, optional `path`), `env_clone`, `env_delete` | Workspace lifecycle and attached directories |
| `exec` | Run the platform shell (`/bin/sh -c` or `cmd.exe /C`) in an environment; with a progressToken it streams stdout/stderr over SSE |
| `read_file`, `write_file`, `upload_file`, `download_file` | Small files over MCP |
| `file_link` | Short-lived signed URL to download or upload one file over HTTPS |
| `list_dir`, `search` | Directory listing and literal search |

Paths are relative to the environment workspace. Absolute paths, `..` and escaping symlinks are rejected. `exec` runs as the envd user; treat output as untrusted.

## Limits

- `exec`: 60 s default, 600 s maximum; `-max-exec` concurrent commands (default 4).
- Output: `-max-output` bytes per stream (default 1 MiB); the rest is dropped and marked `truncated`.
- Text files: 1 MiB; MCP transfers: 1 MiB; search: 5000 files / 32 MiB; `list_dir` returns at most 5000 entries and sets `truncated`.
- Large files: start envd with `-public-url https://your-host` and call `file_link`; the returned signed URL is valid for 30–3600 seconds (default 300) and is scoped to one environment, path and direction. Uploads are capped by `-max-upload` (default 1 GiB).
- HTTP: `-rate-limit` requests/minute per caller (default 120), `-auth-limit` failed authentications/minute before a 60 s block (default 5); `-trust-proxy` uses the last `X-Forwarded-For` address.
- `-read-only` disables exec, writes, create, clone and delete.

## Security

- Bare-metal execution is not isolated. The container wrapper is the recommended desktop default; it reduces blast radius but is not a complete sandbox.
- `exec` runs as uid 10002/group 10001 in the container; the token is in `ENVD_TOKEN` and is not inherited by commands. Bare metal without `-exec-user` runs commands as the envd user, which can read the token file.
- Attach only directories you intend the agent to use. Do not expose all of shared storage; `-allow-root /` is the whole machine.
- File tools use `os.Root` on every operation, reject escaping symlinks and files with multiple hard links, and bind the root to a directory handle, so a concurrent process cannot redirect them outside the workspace. `exec` itself intentionally has the command user's full access.
- `file_link` URLs are capability URLs: anyone holding one can read/write that one file until it expires. Don't log them; use HTTPS and short TTLs.
- Windows support is best effort: Unix permissions, symlinks and hard-link protections are weaker.
- Use a long random token (0600), keep it out of shell history and logs, and terminate TLS in a reverse proxy.
- Do not expose the plain HTTP port to the internet.
- Back up the data directory; managed `env_delete` is permanent.

## Build and test

```sh
go test ./...
go vet ./...
sh smoke.sh            # build the real binary, start it on loopback, drive an MCP session

CGO_ENABLED=0 GOOS=linux  GOARCH=arm64 go build -trimpath -o mika-envd-linux-arm64 .
CGO_ENABLED=0 GOOS=darwin GOARCH=arm64 go build -trimpath -o mika-envd-darwin-arm64 .
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -o mika-envd-windows-amd64.exe
```

The Docker build runs the tests before producing the binary. CI (`.github/workflows/envd.yml`) runs `go vet`, `go test -race`, the smoke script, cross-compiles six targets and builds the image; it is path-filtered to `tools/envd/**` and this document so Android PRs do not run it.

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
