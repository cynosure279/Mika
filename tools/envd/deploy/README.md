# Deployment scripts

These scripts are convenience wrappers around the same envd binary and flags
documented in [`docs/dev/remote-env.md`](../../../docs/dev/remote-env.md). They
do not add a new execution model:

- envd still listens on loopback by default and still needs a bearer token.
- `-allow-root` only lets `env_create` attach existing directories; it does not
  sandbox `exec`.
- PRoot (including proot-distro) is a user-space compatibility layer, not a
  security boundary.

## Scripts

| Script | Where to run it | What it sets up |
| --- | --- | --- |
| `install.sh` | Linux or macOS shell | Builds or copies the binary, creates a data dir and token, optionally installs a systemd user unit or a launchd agent, and can start it. |
| `install.ps1` | Windows PowerShell 7 | Same, with an optional per-user scheduled task at logon. |
| `termux-install.sh` | Termux | Builds the arm64 binary, requests shared-storage access, creates a narrow default `-allow-root`, and runs it in the background with a wake lock. |
| `termux-proot-setup.sh` | Termux | Installs proot-distro, creates one distro, copies the binary and scripts into `$HOME/.mika-envd`, binds each allowed directory into the distro, and starts envd inside that single PRoot layer. |
| `proot-install.sh` | Inside the proot distro | Called by `termux-proot-setup.sh`. Creates the data dir and token, and writes the inner `run.sh`; it does not start a daemon by itself. |

All scripts keep one token per data directory and do not print the token. Read
it from the token file and paste it into Mika's MCP server settings.

## Linux / macOS

```sh
# install, create a token, and start a user service (systemd or launchd)
sh tools/envd/deploy/install.sh

# attach a project directory and listen on a different loopback port
sh tools/envd/deploy/install.sh \
  --listen 127.0.0.1:8787 \
  --allow-root /srv/projects

# install only, do not start
sh tools/envd/deploy/install.sh --no-start

# skip the service manager and run in the foreground
sh tools/envd/deploy/install.sh --service none
```

Defaults:

- binary: `~/.local/bin/mika-envd`
- data: `~/.mika-envd`
- token: `~/.mika-envd/token` (mode 0600)
- listen: `127.0.0.1:8787`
- service: systemd user unit on Linux when `systemctl --user` works, launchd
  agent on macOS, otherwise foreground

The systemd user unit lives at
`~/.config/systemd/user/mika-envd.service`. User services normally stop at
logout; run `loginctl enable-linger "$USER"` if it should survive logout. The
launchd agent lives at `~/Library/LaunchAgents/com.mika.envd.plist`.

To use `-exec-user` on a Linux host, run envd as root with
`-exec-user 10002 -exec-group 10001`, or use the Docker Compose wrapper. The
script does not switch users for you.

## Windows

```powershell
pwsh -File tools\envd\deploy\install.ps1 -Service
```

Defaults:

- binary: `%LOCALAPPDATA%\Mika\envd\mika-envd.exe`
- data: `%LOCALAPPDATA%\Mika\envd\data`
- listen: `127.0.0.1:8787`
- `-Service` registers a per-user scheduled task at logon and starts it

Windows support is best-effort: there is no `-exec-user`, Unix permissions and
hard-link protection are incomplete, and commands run as the signed-in user.

## Termux (one layer, plain Termux)

```sh
sh tools/envd/deploy/termux-install.sh
```

The script builds with Termux's Go, runs `termux-setup-storage` when shared
storage is not available yet, creates `~/storage/shared/Mika` as a narrow
default `-allow-root`, and starts envd with `termux-wake-lock` in the
background. Use `--allow-root` to replace the default, `--no-start` to only
configure, or `--foreground` to run in the terminal.

Plain Termux has a real `/bin/sh` only through Android's `/bin` compatibility
path; envd's fixed Linux `PATH` does not contain Termux's `$PREFIX/bin`. File
tools work, but `exec` may not see tools installed with `pkg`. If you need a
full Linux userland, use the single-layer proot setup below.

## Termux + proot-distro (single layer)

```sh
sh tools/envd/deploy/termux-proot-setup.sh --distro ubuntu
```

This is the recommended shape when you need a full Linux userland on the phone
(`/bin/sh`, coreutils, `/tmp`, etc.) while still attaching phone storage:

- Termux installs `proot-distro` and the chosen distro if needed.
- The envd binary and data stay in `$HOME/.mika-envd` on the Termux side.
- `$HOME/.mika-envd` is bound to `/opt/mika` inside the distro, so upgrades do
  not touch the rootfs.
- Each `--allow-root SOURCE` is bound to `/mnt/mika/N`; envd starts with
  `-allow-root /mnt/mika/N`. The source path is never passed to envd.
- The wrapper runs a single `proot-distro login`, not a nested PRoot.

Defaults:

- distro: `ubuntu` (`--distro alpine` is also supported)
- allow root: `~/storage/shared/Mika`, bound to `/mnt/mika/0`
- data: `~/.mika-envd/proot-data`, bound to `/opt/mika/proot-data`
- listen: `127.0.0.1:18787`

Useful flags:

```sh
# attach two project directories, bind only those two
sh tools/envd/deploy/termux-proot-setup.sh \
  --distro ubuntu \
  --allow-root ~/storage/shared/ProjectA \
  --allow-root ~/storage/shared/ProjectB

# configure but do not start
sh tools/envd/deploy/termux-proot-setup.sh --no-start

# use an already built arm64 binary
sh tools/envd/deploy/termux-proot-setup.sh --binary ~/mika-envd
```

The generated `proot-run.sh` starts the distro and the inner `run.sh`. Stop it
with `kill "$(cat ~/.mika-envd/proot.pid)"`; also run `termux-wake-unlock` if
you no longer need the wake lock.

### Important boundaries

- **Do not nest another PRoot inside the distro.** `proot-distro` is already
  one PRoot layer. Nesting another one adds ptrace/path-translation problems
  and does not add isolation.
- **`-allow-root` does not confine `exec`.** It only constrains `env_create`
  and the file tools. `exec` can see the whole proot rootfs and every
  explicitly bound directory. Bind the narrowest directory you need; use
  `-read-only` when you only need to read.
- **PRoot is not a sandbox.** All layers share the Termux Android uid. Do not
  run untrusted model output here and treat it as safely isolated.
- **Keep data and tokens out of shared storage.** Metadata uses atomic
  renames and expects normal Unix permissions; keep them under
  `$HOME/.mika-envd`, not on `/sdcard`.
- **Use the bind target in `env_create`.** Inside the proot, attach
  `/mnt/mika/0/Project`, not the Termux source path.
- **Keep the host spelling consistent.** If Mika connects to
  `http://127.0.0.1:18787/mcp`, use the same `127.0.0.1` in `-public-url` for
  `file_link`; `localhost` and `127.0.0.1` are different origins to the app's
  signed-link check.
- **Android limits still apply.** Android 11+ scoped storage can block
  `Android/data` and `Android/obb`; shared storage is often `noexec`, so run
  scripts with `sh script.sh` instead of `./script.sh`. Termux background
  execution, battery optimization and the Android 12+ phantom-process killer
  can stop the service.
- **`os.Root` needs a working `openat2` or its fallback.** Verify file
  operations inside the chosen PRoot version with the checklist below.

## Smoke checklist after installing

Run these from Mika or with an MCP client:

1. `env_info`, `env_list`.
2. `env_create` with `path` pointing at an attached directory.
3. `read_file`, `write_file`, `list_dir`, `search` in that environment.
4. Create a symlink inside the environment that points outside it; `read_file`
   must refuse it.
5. `exec` with `pwd`, `id`, `ls`; run a command that sleeps longer than a
   short timeout and confirm no child is left behind.
6. `file_link` a larger file and let Mika download it.
7. Restart envd and confirm attached environments still load.

For the proot setup, repeat the same checks inside the distro. If file tools
fail while `exec` works, suspect the PRoot version's `openat2` handling rather
than the environment root policy.
