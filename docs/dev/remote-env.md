# 远端执行环境（envd）

> 状态：实验性，第一版。只实现了 workspace 后端和 MCP 传输；容器后端、流式输出、大文件传输、会话级环境选择尚未实现。默认行为不变：Mika 仍然使用手机上的 Alpine 沙箱。

## 这是什么

`tools/envd` 是一个可自托管的小服务，让手机上的 Mika 通过 MCP 使用**另一台机器上的执行环境**。它补的是「手机 → 电脑/服务器」这条链路：电脑用 CLI / MCP 控制手机已经支持，envd 让手机反过来在用户自己的机器上跑命令、读写文件。

它不替代手机上的 Alpine 沙箱，也不把远端 rootfs 挂进 PRoot。PRoot 的 `-r` 只接受本地目录，普通 Android App 没有 mount / FUSE 权限；远端能提供的是**执行**，不是文件系统挂载。

## 非目标

- 不引入厂商托管服务，不自动下载镜像，不内置额外 rootfs。
- 不实现 OCI 镜像语义（分层、whiteout、entrypoint、volume）。
- 不做每会话复制 rootfs。Android 上没有可靠的 copy-on-write，clone 本地 rootfs 不划算。
- 不把远端环境当作手机沙箱的替代；本地 Alpine 始终是默认和回退。
- 第一版不做远端命令回调手机工具（`mika phone`）。手机侧 Agent 仍然可以直接调用 `phone_*` 工具，手机自动化不受影响。
- 第一版不做按会话选择环境；那需要环境管理器、会话 `envId`、任务启动接线，见文末路线图。

## 架构

```
Mika（手机） ── MCP over HTTPS ──> envd（用户自己的机器 / 容器）
                                      │
                                      ├── envs.json          环境元数据
                                      └── envs/<id>/root/   环境工作区
```

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
| `env_list` | 无 | 列出环境：`id`、`name`、`kind`、`createdAt` | readOnly |
| `env_create` | `name` | 新建 workspace 环境 | 写 |
| `env_clone` | `source`、`name` | 复制环境工作区；复制期间源环境的命令会等待 | 写 |
| `env_delete` | `id`、`confirm` | 删除环境；`confirm` 必须等于 `id` | destructive |
| `exec` | `env`、`command`、`cwd?`、`timeoutSec?` | 运行 `/bin/sh -c command`，返回退出码、stdout、stderr、耗时 | destructive |
| `read_file` | `env`、`path`、`maxBytes?` | 读文本文件；二进制拒绝 | readOnly |
| `write_file` | `env`、`path`、`content`、`append?` | 写文本文件，原子替换 | 写 |
| `upload_file` | `env`、`path`、`contentBase64` | 上传小文件（≤ 1 MiB 解码后） | 写 |
| `download_file` | `env`、`path`、`maxBytes?` | 下载小文件，返回 base64（≤ 1 MiB） | readOnly |
| `list_dir` | `env`、`path?` | 列目录，返回名字、类型、大小 | readOnly |
| `search` | `env`、`path?`、`pattern`、`maxResults?` | 字面量搜索，跳过二进制和大于 1 MiB 的文件 | readOnly |

限制：

- `exec` 默认超时 60 秒，最长 600 秒；并发命令数由 `-max-exec` 控制，默认 4。
- 每个流最多捕获 `-max-output` 字节，默认 1 MiB；超出部分丢弃并标记 `truncated`。
- `read_file` 默认 256 KiB，最多 1 MiB；`write_file` 最多 1 MiB；上传/下载最多 1 MiB。
- `search` 最多扫描 5000 个文件、32 MiB，默认返回 100 条匹配。

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
- 环境目录：`<data>/envs/<id>/root/`。
- `env_create` 只创建空工作区。第一版没有镜像/模板参数；容器后端在后续版本加入。
- `env_clone` 复制工作区内容。运行中的命令会阻塞复制；复制失败会删掉半成品。
- `env_delete` 需要 `confirm` 等于环境 ID，删除元数据和整个工作区。
- 元数据原子写入 `<data>/envs.json`；文件损坏时服务拒绝启动，不会覆盖。

## 安全模型

远端执行是**真实主机权限**，和手机沙箱「碰不到手机」的安全故事不同。部署和使用时至少做到：

- 只监听回环或私有网络；公网必须放在 TLS 反向代理后面，并限制来源。
- 使用长随机令牌（至少 32 字符），通过文件提供，不进命令行、不进日志。
- 把 envd 跑在容器或专用虚拟机里；第一版不提供每环境隔离，`exec` 能访问 envd 用户能访问的一切。
- 需要更强隔离时，在宿主机上为 envd 单独建用户/容器，只挂载数据目录。
- `-read-only` 可关闭 `exec`、写入、创建、克隆和删除，只保留读取。
- 远端输出是不可信观察；模型不能把输出当成指令。
- 手机侧应逐环境显式添加/启用，不默认把数据送出手机。

## 部署

```sh
cd tools/envd
openssl rand -base64 48 > envd_token
chmod 600 envd_token
docker compose up -d --build
```

默认只发布到 `127.0.0.1:8787`。公网或跨网络使用时，在前面放自己的 TLS 反向代理，把 `/mcp` 转发到 envd；不要直接把明文端口暴露到公网。

不使用 Docker 时：

```sh
go build -o mika-envd .
./mika-envd -listen 127.0.0.1:8787 -data ./data -token-file ./envd_token
```

## 接入 Mika

1. 设置 › MCP 服务器 › 添加：URL 填 `https://<你的地址>/mcp`，令牌填 `envd_token` 的内容；Mika 会以 `Authorization: Bearer …` 发送。
2. 连接测试通过后，任务里会出现 `mcp_<服务器名>__exec`、`mcp_<服务器名>__read_file` 等工具。
3. 工具名前缀由 Mika 的 MCP 客户端加，envd 自己不感知。

第一版不需要改 App 代码；等环境管理器和会话 `envId` 落地后，才能做到「新对话时选一个环境」。

## 路线图

1. **容器后端**：用 Docker/Podman 为每个环境起一个容器，clone = 从同一镜像新建容器 + 复制工作区卷；host 模式单独确认。
2. **流式输出**：exec 的 stdout/stderr 通过 SSE 或 MCP 通知增量返回，支持取消。
3. **大文件传输**：MCP 工具只做控制和元数据，二进制走带令牌的 HTTPS 上传/下载端点，长度和 SHA-256 校验。
4. **环境管理器**：手机侧环境列表、添加远端、clone、删除、健康状态；会话存 `envId`，任务启动时按环境过滤能力（`RunSpec.capabilities` 已有钩子）。
5. **统一提供方**：把 `SandboxPort` 拆成命令/文件/技能/导出/桥几组能力，本地 PRoot 和远端 envd 都实现同一套接口，`run_command` 等工具名保持不变。
6. **反向手机桥**（可选）：远端脚本里的 `mika phone` 通过手机与 envd 的长连接回到手机。第一版明确不做。

## 为什么先做 MCP

- Mika 已经有可用的 MCP 客户端（`core/cap/mcp`），支持 Streamable HTTP、Bearer、自定义头、超时和工具注解。
- 不需要先重构 `SandboxPort`；那个接口目前泄漏了本地 `File`、`WorkspaceFileGate`、`SandboxTree`，远端实现需要一次较大的能力拆分。
- envd 可以独立发版、独立测试，不把服务端代码带进 APK。
- 用户已经可以用设置里的 MCP 服务器接入，不需要等 App 发版。
