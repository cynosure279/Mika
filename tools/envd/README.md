# mika-envd

A small self-hosted execution server for Mika. It speaks MCP over HTTP and lets a phone run commands and read/write files in named workspaces on another machine.

Status: experimental. The first version has a workspace backend only. It does **not** replace the Alpine sandbox on the phone, and it does not provide container isolation, streaming output, large-file transfer or phone control.

The developer contract and threat model are in [`docs/dev/remote-env.md`](../../docs/dev/remote-env.md).

## Quick start

```sh
cd tools/envd
openssl rand -base64 48 > envd_token
chmod 600 envd_token
docker compose up -d --build
```

The container listens on `127.0.0.1:8787` only. Put your own TLS reverse proxy in front before exposing it to another network.

Without Docker:

```sh
go build -o mika-envd .
./mika-envd -listen 127.0.0.1:8787 -data ./data -token-file ./envd_token
```

## Configure Mika

Settings › MCP servers › Add:

- URL: `https://<your-host>/mcp`
- Token: the contents of `envd_token`

Mika sends `Authorization: Bearer <token>`. After a successful test, the task gets tools such as `mcp_<name>__exec`, `mcp_<name>__read_file` and `mcp_<name>__env_list`.

## Tools

| Tool | Purpose |
| --- | --- |
| `env_info`, `env_list` | Server and environment information |
| `env_create`, `env_clone`, `env_delete` | Workspace lifecycle |
| `exec` | Run `/bin/sh -c` in an environment |
| `read_file`, `write_file`, `upload_file`, `download_file` | Files |
| `list_dir`, `search` | Directory listing and literal search |

Paths are relative to the environment workspace. Absolute paths, `..` and escaping symlinks are rejected. `exec` runs as the envd user; treat output as untrusted.

## Limits

- `exec`: 60 s default, 600 s maximum; `-max-exec` concurrent commands (default 4).
- Output: `-max-output` bytes per stream (default 1 MiB); the rest is dropped and marked `truncated`.
- Text files: 1 MiB; transfers: 1 MiB; search: 5000 files / 32 MiB.
- `-read-only` disables exec, writes, create, clone and delete.

## Security

- Run envd in a container or dedicated VM. The workspace backend does not isolate environments from each other or from the host user.
- Use a long random token, keep it out of shell history and logs, and terminate TLS in a reverse proxy.
- Do not expose the plain HTTP port to the internet.
- Back up the data volume; `env_delete` is permanent.

## Build and test

```sh
go test ./...
go vet ./...
```

The Docker build runs the tests before producing the binary.

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
