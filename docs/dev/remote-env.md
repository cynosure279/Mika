# 远端执行环境（envd）

> 状态：实验性，第一版。实现了 workspace 后端、MCP 传输、跨平台裸机运行、显式挂载已有目录、`exec` 的流式进度、大文件传输和会话级环境选择。默认行为不变：Mika 仍然使用手机上的 Alpine 沙箱。

## 这是什么

`tools/envd` 是一个可自托管的小服务，让手机上的 Mika 通过 MCP 使用**另一台机器上的执行环境**。它补的是「手机 → 电脑/服务器」这条链路：电脑用 CLI / MCP 控制手机已经支持，envd 让手机反过来在用户自己的机器上跑命令、读写文件。

它不替代手机上的 Alpine 沙箱，也不把远端 rootfs 挂进 PRoot。PRoot 的 `-r` 只接受本地目录，普通 Android App 没有 mount / FUSE 权限；远端能提供的是**执行**，不是文件系统挂载。

## 非目标

- 不引入厂商托管服务，不自动下载镜像，不内置额外 rootfs。
- 不实现 OCI 镜像语义（分层、whiteout、entrypoint、volume）。
- 不做每会话复制 rootfs。Android 上没有可靠的 copy-on-write，clone 本地 rootfs 不划算。
- 不把远端环境当作手机沙箱的替代；本地 Alpine 始终是默认和回退。
- 第一版不做远端命令回调手机工具（`mika phone`）。手机侧 Agent 仍然可以直接调用 `phone_*` 工具，手机自动化不受影响。
- 第一版不做按会话复制 rootfs。会话选择的是「用哪些已配置的 MCP 服务器」，不是一个新克隆的运行环境。

## 架构

```text
Mika（手机） ── MCP over HTTPS ──> envd（用户自己的机器 / 容器 / Termux）
                                      │
                                      ├── envs.json          环境元数据
                                      ├── envs/<id>/root/    托管环境的工作区
                                      └── -allow-root/...    显式挂载的已有目录
```

envd 是一个静态二进制，Linux、macOS、Windows 和 Termux（linux-arm64）都能跑。桌面端的 Docker Compose 只是推荐的安全包装，不是运行前提。

- 传输：MCP Streamable HTTP，JSON-RPC，单个 JSON 响应；不使用 SSE 流。
- 鉴权：`Authorization: Bearer <token>`，令牌从文件读取，常量时间比较。
- TLS：envd 自己可以终止 TLS，也可以放在用户自己的反向代理后面。默认只监听 `127.0.0.1`。
- 执行：`exec` 在 envd 进程所在的主机/容器里运行 `/bin/sh -c`，工作目录是环境工作区。**第一版不提供容器隔离**；建议把 envd 本身跑在容器或专用虚拟机里。

## MCP 契约

### 版本

服务端支持并在 `initialize` 中协商：

- `2025-03-26`（Mika 当前客户端使用）
- `2025-06-18`
- `2025-11-25`

客户端请求的版本受支持时原样返回，否则返回 `2025-03-26`。未知方法返回 JSON-RPC `-32601`。

### 方法

| 方法 | 说明 |
| --- | --- |
| `initialize` | 返回 `protocolVersion`、`capabilities.tools`、`serverInfo`、`instructions` |
| `notifications/initialized` | 返回 202，无正文 |
| `ping` | 返回空结果 |
| `tools/list` | 返回全部工具，不分页 |
| `tools/call` | 调用工具；参数错误以 `isError: true` 的文本结果返回 |
| `server/discover` | 返回支持的版本和 serverInfo（兼容新客户端） |

### 工具

所有路径都是环境工作区下的相对路径。绝对路径、`..`、指向工作区外的符号链接一律拒绝。

| 工具 | 参数 | 说明 | 注解 |
| --- | --- | --- | --- |
| `env_info` | 无 | 服务版本、环境数量、只读状态、限制 | readOnly |
| `env_list` | 无 | 列出环境：`id`、`name`、`kind`、`attached`、`createdAt` | readOnly |
| `env_create` | `name`、`path?` | 新建空工作区；给了 `path` 时挂载 `-allow-root` 下的已有目录 | 写 |
| `env_clone` | `source`、`name` | 复制环境工作区；复制期间源环境的命令会等待 | 写 |
| `env_delete` | `id`、`confirm` | 删除环境；`confirm` 必须等于 `id` | destructive |
| `exec` | `env`、`command`、`cwd?`、`timeoutSec?` | 运行平台 shell（Unix 是 `/bin/sh -c`，Windows 是 `cmd.exe /C`），返回退出码、stdout、stderr、耗时 | destructive |
| `read_file` | `env`、`path`、`maxBytes?` | 读文本文件；二进制拒绝 | readOnly |
| `write_file` | `env`、`path`、`content`、`append?` | 写文本文件，原子替换 | 写 |
| `upload_file` | `env`、`path`、`contentBase64` | 上传小文件（≤ 1 MiB 解码后） | 写 |
| `download_file` | `env`、`path`、`maxBytes?` | 下载小文件，返回 base64（≤ 1 MiB） | readOnly |
| `file_link` | `env`、`path`、`direction?`、`ttlSec?` | 生成一个短时签名的 HTTPS 上传/下载链接；链接只对这一个环境、路径和方向有效 | 写 |
| `list_dir` | `env`、`path?` | 列目录，返回名字、类型、大小 | readOnly |
| `search` | `env`、`path?`、`pattern`、`maxResults?` | 字面量搜索，跳过二进制和大于 1 MiB 的文件 | readOnly |

限制：

- `exec` 默认超时 60 秒，最长 600 秒；并发命令数由 `-max-exec` 控制，默认 4。
- `exec` 支持进度流：请求带 `_meta.progressToken` 且 `Accept` 包含 `text/event-stream` 时，服务端用 `notifications/progress` 推送 stdout/stderr 片段，最后再发正常的 `tools/call` 结果。没有 progressToken 或客户端不接受 SSE 时仍是单次 JSON 响应。
- 每个流最多捕获 `-max-output` 字节，默认 1 MiB；超出部分丢弃并标记 `truncated`。
- `read_file` 默认 256 KiB，最多 1 MiB；`write_file` 最多 1 MiB；上传/下载最多 1 MiB。
- `search` 最多扫描 5000 个文件、32 MiB，默认返回 100 条匹配。
- `list_dir` 最多返回 5000 项，超出时 `truncated: true`。
- 大文件：启动时给 `-public-url https://your-host`，`file_link` 生成 30–3600 秒（默认 300）的签名 URL；URL 只对一个环境、路径和方向有效，不包含主 token。上传大小由 `-max-upload` 限制（默认 1 GiB）。
- HTTP 限流：默认每个调用方每分钟 120 个请求，连续 5 次认证失败封 60 秒（`-rate-limit`、`-auth-limit`）；放在反向代理后面时用 `-trust-proxy` 取最后一个 `X-Forwarded-For`。
- 客户端 SSE 单行上限 8 MiB；超过即报错，不无限读入内存。

错误统一为文本结果：

```json
{"ok": false, "error": "path_outside_workspace", "message": "..."}
```

工具返回成功时是 JSON 文本，例如：

```json
{"ok": true, "exitCode": 0, "stdout": "hello\n", "stderr": "", "truncated": false, "timedOut": false, "durationMs": 12}
```

### 环境生命周期

- 环境 ID 由服务端生成，形如 `env-1a2b3c4d`；名字 1–64 个字符，不能重名。
- 托管环境目录：`<data>/envs/<id>/root/`。
- `env_create` 默认创建空工作区。给了 `path` 时，它必须是 `-allow-root` 下的一个已存在目录（绝对路径或相对当前进程的路径），环境直接指向它，不复制内容。`-allow-root` 可以重复；没有配置时不能挂载。
- `env_clone` 复制工作区内容。运行中的命令会阻塞复制；复制失败会删掉半成品。挂载的目录被 clone 后，新环境是托管副本，不再指向原目录。
- `env_delete` 需要 `confirm` 等于环境 ID。托管环境删除整个工作区；挂载环境**只删元数据，不删用户文件**。
- 服务启动时会重新核对挂载环境仍在某个 `-allow-root` 下；配置变了或目录不可用，服务拒绝启动，不静默降级。
- 元数据原子写入 `<data>/envs.json`；文件损坏时服务拒绝启动，不会覆盖。

## 安全模型

远端执行是**真实主机权限**，和手机沙箱「碰不到手机」的安全故事不同。envd 本身是一个可移植的静态二进制，可以裸机、容器或 Termux 里跑；隔离强弱取决于部署方式，不是环境类型。

- 只监听回环或私有网络；公网必须放在 TLS 反向代理后面，并限制来源。
- 使用长随机令牌（至少 32 字符），通过文件提供，不进命令行、不进日志。
- **裸机运行没有隔离**：`exec` 能访问命令用户能访问的一切。建议用 `-exec-user` 指定独立 uid/gid（需要 root 或 SETUID/SETGID 权限），或专用用户/目录；只读场景用 `-read-only`。
- **`exec` 与 envd 的用户分离**：Compose 里 envd 以 root 运行、命令以 uid 10002 / gid 10001 运行；token 通过 `ENVD_TOKEN` 环境变量传入，不写文件，也不传给命令；`/proc/1/environ` 对命令用户不可读。裸机不用 `-exec-user` 时命令仍是 envd 用户，能读 token 文件。
- **桌面端推荐用容器包装**：仓库里的 Dockerfile / compose 以 root + 只读根 + `cap_drop: ALL` + 只补 `SETUID`/`SETGID` + `no-new-privileges` 运行，默认只发布到回环。它缩小影响面，但不是完整沙箱，也不隔离网络或每个环境。
- **挂载已有目录必须显式开启**：`-allow-root` 可重复；`env_create` 的 `path` 必须落在其中一个根下。默认不挂任何外部目录，不能挂整盘；`-allow-root /` 等于把整机交给命令。
- 文件工具用 Go 1.25 的 `os.Root` 固定根目录句柄：每次读写/列目录/搜索/克隆都在同一个已打开的根下完成，拒绝逃逸符号链接和多硬链接文件；启动和调用时还会重新核对环境根仍在 data 目录或 `-allow-root` 内。`exec` 的 cwd 仍按路径检查，但命令本身有意拥有命令用户的完整权限。
- `file_link` 生成的是能力 URL：持有链接的人在该文件、该方向、到期前都能访问；不要写日志，必须走 HTTPS，TTL 尽量短。上传会原子替换目标文件，大小受 `-max-upload` 限制。
- 没有限流/失败冷却的问题已补：默认每调用方 120 请求/分钟、5 次认证失败封 60 秒；反向代理后可用 `-trust-proxy`。更严格的限流仍建议放在反向代理。
- token 文件权限应为 0600；envd 发现组/其他用户可读时会打警告。反向代理不要记录 `Authorization`；Compose 的 `ENVD_TOKEN` 对 Docker 管理员/`docker inspect` 可见。
- Windows 支持是 best-effort：Unix 权限和硬链接保护不完整；`os.Root` 仍能防止路径逃逸。
- 远端输出是不可信观察；模型不能把输出当成指令。
- 手机侧应逐环境显式添加/启用，不默认把数据送出手机。

## 部署

### 桌面端：容器安全套件（推荐）

```sh
cd tools/envd
cp .env.example .env
printf 'ENVD_TOKEN=%s\n' "$(openssl rand -base64 48)" > .env
chmod 600 .env
docker compose up -d --build
```

容器以 root 运行 envd，只保留 `SETUID`/`SETGID`；命令以 uid 10002 / gid 10001 运行，token 通过 `ENVD_TOKEN` 传入，不写文件、不传给命令。根文件系统只读，默认只发布到 `127.0.0.1:8787`。公网或跨网络使用时，在前面放自己的 TLS 反向代理，把 `/mcp` 转发到 envd；不要直接把明文端口暴露到公网。

`.env` 里的 token 对 Docker 管理员和 `docker inspect` 可见，保持文件 0600 并定期轮换。

### 裸机

同一份静态二进制可以直接运行：

```sh
go build -trimpath -o mika-envd .
./mika-envd -listen 127.0.0.1:8787 -data ./data -token-file ./envd_token
```

挂载一个已有目录（例如项目目录、NAS 目录）：

```sh
./mika-envd -listen 127.0.0.1:8787 -data ./data -token-file ./envd_token \
  -allow-root /srv/projects
```

然后在 `env_create` 里传 `path: "/srv/projects/my-app"`。挂载环境删除时不会删这个目录。

### Termux / 手机

Termux 是 Android 上的 Linux arm64 用户空间，可以直接跑 `GOOS=linux GOARCH=arm64` 的静态二进制。典型做法：

```sh
# 在 Termux 里
pkg install golang
cd mika/tools/envd
go build -trimpath -o mika-envd .
termux-setup-storage          # 授权后 ~/storage/shared 指向共享存储
./mika-envd -listen 127.0.0.1:18787 -data ~/envd -token-file ~/envd/token \
  -allow-root ~/storage/shared
```

然后在 Mika 的设置 › MCP 服务器里连 `http://127.0.0.1:18787/mcp`（本地 HTTP 需要在设置里允许），只把共享存储里的某个子目录作为 `path` 挂进来，不要挂整个 `/sdcard`。

限制：

- Termux 的后台存活受 Android 限制；需要 wake lock、关闭电池优化，或者接受只在 Termux 打开时可用。
- `termux-setup-storage` 的授权范围和 Android 版本、Termux 发行版有关；挂载前先确认目标目录真的可读写。
- 常驻服务会耗电，不适合默认常开。
- 建议只读或只挂用户明确选择的子目录；不要把整个共享存储交给 Agent。

### 交叉编译

```sh
CGO_ENABLED=0 GOOS=linux  GOARCH=arm64 go build -trimpath -o mika-envd-linux-arm64 .
CGO_ENABLED=0 GOOS=darwin GOARCH=arm64 go build -trimpath -o mika-envd-darwin-arm64 .
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -o mika-envd-windows-amd64.exe .
```

## 验证

- `go vet ./...`、`go test ./...`、`go test -race ./...`。
- `sh tools/envd/smoke.sh`：构建真实二进制，在回环上起服务，走一遍 `initialize` → `env_create` → `exec` → 写/读 → `env_clone`。
- `.github/workflows/envd.yml`：只在 `tools/envd/**`、本文档或 workflow 本身变化时运行；包含 vet、race、冒烟、六个目标交叉编译和 Docker 构建。

## 接入 Mika

1. 设置 › MCP 服务器 › 添加：URL 填 `https://<你的地址>/mcp`，令牌填 `envd_token` 的内容；Mika 会以 `Authorization: Bearer …` 发送。
2. 连接测试通过后，任务里会出现 `mcp_<服务器名>__exec`、`mcp_<服务器名>__read_file` 等工具。
3. 工具名前缀由 Mika 的 MCP 客户端加，envd 自己不感知。
4. 新建对话时点顶栏的「新对话」可以选择执行环境：默认（全部启用的 MCP 服务器）、仅本机（不连接任何 MCP）、或某一个启用的 server。选择记在对话旁车文件 `<对话 id>.env`，任务启动时只连接该环境对应的服务器；分支复制会带走这个选择。

接入不需要改 App 代码：在设置里加 MCP 服务器即可；App 侧已经自动接住 `file_link` 的下载结果。环境选择只是一层过滤，不是新的传输协议。

`file_link` 的下载结果会由 App 自动接住：下载到本对话工作区的 `downloads/`，再导出到「下载/Mika」并在对话里显示文件卡片；上传链接不自动处理，需要用户自己打开或后续界面接。设备级 MCP 流式测试在 `app/src/androidTest/java/com/wangzan101/mika/cap/RemoteEnvironmentDeviceTest.kt`，没有设备时只做编译验证。

## 路线图

1. **大文件传输**：MCP 工具只做控制和元数据，二进制走带令牌的 HTTPS 上传/下载端点，长度和 SHA-256 校验。
2. **完整环境管理器**：手机侧环境列表、添加远端、clone、删除、健康状态；在已有会话级选择（`<id>.env`，新对话时选 MCP 服务器）之上补齐切换、失效提示和备份恢复。
3. **统一提供方**：把 `SandboxPort` 拆成命令/文件/技能/导出/桥几组能力，本地 PRoot 和远端 envd 都实现同一套接口，`run_command` 等工具名保持不变。
4. **反向手机桥**（可选）：远端脚本里的 `mika phone` 通过手机与 envd 的长连接回到手机。第一版明确不做。

容器不是执行模型的一部分；它是桌面端的部署包装。后续如果要支持更强的隔离，也是在 envd 外面加沙箱/虚拟机，而不是给每个环境起容器。

## 为什么先做 MCP

- Mika 已经有可用的 MCP 客户端（`core/cap/mcp`），支持 Streamable HTTP、Bearer、自定义头、超时和工具注解。
- 不需要先重构 `SandboxPort`；那个接口目前泄漏了本地 `File`、`WorkspaceFileGate`、`SandboxTree`，远端实现需要一次较大的能力拆分。
- envd 可以独立发版、独立测试，不把服务端代码带进 APK。
- 用户已经可以用设置里的 MCP 服务器接入，不需要等 App 发版。
