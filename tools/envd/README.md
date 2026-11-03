# mika-envd

A small self-hosted execution server for Mika. It speaks MCP over HTTP and lets a phone run commands and read/write files in named workspaces on another machine.

Status: experimental. It has a workspace backend, runs as a static binary on Linux/macOS/Windows/Termux, and can attach existing directories under explicit `-allow-root` paths. It does **not** replace the Alpine sandbox on the phone, and it does not provide streaming output, large-file transfer or phone control.

The developer contract and threat model are in [`docs/dev/remote-env.md`](../../docs/dev/remote-env.md).

## Quick start

### Desktop: container wrapper (recommended)

```sh
cd tools/envd
openssl rand -base64 48 > envd_token
chmod 600 envd_token
docker compose up -d --build
```

The container runs as a non-root user with a read-only root filesystem, `cap_drop: ALL` and `no-new-privileges`, and listens on `127.0.0.1:8787` only. Put your own TLS reverse proxy in front before exposing it to another network.

### Bare metal

```sh
go build -trimpath -o mika-envd .
./mika-envd -listen 127.0.0.1:8787 -data ./data -token-file ./envd_token
```

There is no isolation: `exec` runs as the envd user. Use a dedicated user/directory, or `-read-only` when only reads are needed.

### Termux / phone

A `GOOS=linux GOARCH=arm64` static binary runs in Termux. After `termux-setup-storage`, you can allow a subdirectory of shared storage:

```sh
./mika-envd -listen 127.0.0.1:18787 -data ~/envd -token-file ~/envd/token \
  -allow-root ~/storage/shared
```

Then connect Mika to `http://127.0.0.1:18787/mcp` (allow local HTTP in MCP settings) and attach only a chosen subdirectory, not all of `/sdcard`. Termux background execution and storage permissions depend on the Android version and the Termux build; battery optimization and wake locks matter.

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
| `exec` | Run the platform shell (`/bin/sh -c` or `cmd.exe /C`) in an environment |
| `read_file`, `write_file`, `upload_file`, `download_file` | Files |
| `list_dir`, `search` | Directory listing and literal search |

Paths are relative to the environment workspace. Absolute paths, `..` and escaping symlinks are rejected. `exec` runs as the envd user; treat output as untrusted.

## Limits

- `exec`: 60 s default, 600 s maximum; `-max-exec` concurrent commands (default 4).
- Output: `-max-output` bytes per stream (default 1 MiB); the rest is dropped and marked `truncated`.
- Text files: 1 MiB; transfers: 1 MiB; search: 5000 files / 32 MiB.
- `-read-only` disables exec, writes, create, clone and delete.

## Security

- Bare-metal execution is not isolated. The container wrapper is the recommended desktop default; it reduces blast radius but is not a complete sandbox.
- Attach only directories you intend the agent to use. Do not expose all of shared storage.
- Windows support is best effort: Unix permissions, symlinks and hard-link protections are weaker.
- Use a long random token, keep it out of shell history and logs, and terminate TLS in a reverse proxy.
- Do not expose the plain HTTP port to the internet.
- Back up the data directory; managed `env_delete` is permanent.

## Build and test

```sh
go test ./...
go vet ./...

CGO_ENABLED=0 GOOS=linux  GOARCH=arm64 go build -trimpath -o mika-envd-linux-arm64 .
CGO_ENABLED=0 GOOS=darwin GOARCH=arm64 go build -trimpath -o mika-envd-darwin-arm64 .
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -o mika-envd-windows-amd64.exe
```

The Docker build runs the tests before producing the binary.

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
