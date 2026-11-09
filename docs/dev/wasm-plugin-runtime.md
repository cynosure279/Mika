# 插件 Wasm 运行时：决策记录

状态：阶段一（2026-11-09）
本文记录「给插件系统引入 Wasm 执行路径」的完整决策过程：候选运行时的实测数据、选择理由、生命周期与安全设计、阶段划分和未决问题。实现从阶段一开始，后续 PR 按本文推进。

## 1. 背景

插件工具当前只有一条执行路径：每次调用在 PRoot Alpine 里起一个新进程，跑 `python3` / `node` / `/bin/sh`（`AndroidPluginScriptRunner` → `AndroidSandbox.runPlugin` → `ProcRunner`）。参数经临时 JSON 文件、结果取 stdout 最后一行 JSON。

实测该路径的固定成本与安全面：

- 每次调用约 48–53 ms（proot 10 ms + 解释器启动 + 脚本），且无缓存可利用；
- 环境权限大：完整 Alpine 用户态（122.3 MiB、2,325 个文件、304 个 busybox applet，含 `apk`、python3.12、node）；
- 断网由 seccomp 拦 `AF_INET/AF_INET6`，但资源上限只有 `RLIMIT_FSIZE=2GB`、`RLIMIT_NPROC=256`，**没有内存上限**；
- rootfs 可写且共享，插件可改写 `/usr/bin/python3` 等共享运行时（只有插件脚本副本做了哈希校验）。

Wasm 路径的目标：把「每次调用起进程」变成「进程内两次函数调用」，同时把权限模型从「环境权限 + 沙箱约束」换成「默认零权限 + 显式 import」。

## 2. 候选运行时与实测

同一台主机（Intel i7-12700）上测量。脚本在 Alpine 3.22.7 容器 + 官方 proot 5.5.0 中测（Mika 沙箱原生程序只有 arm64，x86_64 模拟器跑不了真链路）；Wasm 在 Android API 30 x86_64 模拟器上测（与 Mika `minSdk=30` 一致）。

单次工具调用总耗时（中位数，含启动）：

| 场景 | Python | Node | Endive 1.2.0 | Wasmtime 49.0.5 |
| --- | --- | --- | --- | --- |
| 冷启动/首次 | 53 ms | 48 ms | 253 ms 解析 + 53 ms 实例化 | 15–26 ms 编译 |
| 胶水调用（实例化 + 空调用） | 53 ms | 48 ms | 约 2–3 ms | 约 1–2 ms |
| 100 万次循环 | 90 ms | 52.5 ms | 1,500–2,200 ms | 1–2 ms |
| 20,000 行 JSON（1.4 MB） | 77 ms | 66 ms | 未测（字符串 ABI 未定） | 未测（字符串 ABI 未定） |
| 单次调用峰值内存 | 8.5 MB | 47.9 MB | 未测 | 未测 |
| 运行时体积 | 0（含在 rootfs） | 0（含在 rootfs） | +0.57 MB | +6.7 MB（arm64 瘦身）/+10.1 MB（x86_64） |

其他候选：

- **Wasmoon**：Lua 5.4 VM 编译成 Wasm + JS 胶水（272 KB），只能寄生在 JS 引擎（WebView/Node），不是 Android 宿主运行时，不能承担插件工具执行。
- **Chicory**：已改名 **Endive** 并迁入 Bytecode Alliance（`run.endive:*`，2026-10 发布 1.2.0，仍在维护），上表即 Endive 数据。

关键事实：Endive 胶水调用快（2–3 ms），但解释器做计算比脚本慢一个数量级；Wasmtime JIT 在计算上快约三个数量级，代价是每 ABI 6.7–10.1 MB（瘦身版）。Wasmtime 的 trap 经 JNI 返回为异常、进程存活（除零实测）。

## 3. 决策

| 编号 | 决策 | 理由 |
| --- | --- | --- |
| D1 | 引入 Wasm 作为插件工具的**新执行路径**，现有脚本路径保留 | 存量插件与工具链不动；Wasm 是增量 |
| D2 | Wasm 引擎**只选 Wasmtime**，不引入 Endive 作为第二后端 | 用户决策：纯 Wasmtime。Endive 的价值（零原生、0.57MB）不足以维护双后端与两套限额语义；其解释器在计算上反而更慢。Wasmoon 不做宿主运行时 |
| D3 | manifest 用 `wasm: "tools/*.wasm"` 声明工具，和 `script` 二选一；引擎选择不写进 manifest | 插件产物是引擎无关的 `.wasm`，换引擎不改插件 |
| D4 | **池化编译产物，不池化执行状态**：模块按内容哈希缓存（内存 + 可选磁盘 `.cwasm`），实例每次调用新建 | 实例化仅 1–2 ms；复用实例会带 trap 后的脏状态、状态泄漏和并发复杂度 |
| D5 | 进程懒启动、空闲回收；Wasmtime 放独立进程，连续失败熔断 | 原生崩溃不能带走 App；无 Endive 可回退，熔断后降级到脚本或明确报错 |
| D6 | 每实例内存上限 + 每次调用执行限额（Wasmtime fuel/epoch）；沿用超时与输出上限 | 脚本路径当前没有内存上限，这是 Wasm 的增量价值 |
| D7 | ABI v1：参数/结果走 JSON，传输用**线性内存 `ptr+len`**（模块导出 `memory`、`mika_buffer`、`mika_buffer_size`、`mika_tool_call`）；WASI stdio 作为以后可选的兼容层 | 与现有脚本协议同构（JSON 进、JSON 出），不引入 WASI 的权限面和额外体积；Rust/C 实现简单，MoonBit 可后续补绑定 |
| D8 | 脚本路径立即加固：rootfs 只读、`RLIMIT_AS` | 两项小改动，直接消掉「改共享运行时」与「内存 DoS」；不依赖 Wasm |
| D9 | Wasmtime 体积策略：瘦身特性 + 单 ABI（arm64），按分发渠道选 AAB 动态功能或签名下载 | 全特性 9.7–13.3 MB/ABI，瘦身 6.7–10.1 MB；用户只应下载自己那份 |
| D10 | 分三阶段：决策与 manifest → 运行时与 ABI → 插件迁移与脚本降级 | 每阶段可独立 review、可回退 |

### ABI v1（D7 的具体形式）

模块必须导出：

- `memory`：线性内存；
- `mika_buffer() -> i32`：参数/结果共用的缓冲区指针；
- `mika_buffer_size() -> i32`：缓冲区容量（参数 JSON 不得超过）；
- `mika_tool_call(ptr, len) -> i64`：工具入口，返回值打包为 `(结果指针 << 32) | 结果长度`，指向同一块缓冲区里的结果 JSON。

宿主行为：每次调用新建 Store/Instance；参数按 UTF-8 写入缓冲区；`StoreLimits` 限制内存（默认 64 MiB）；epoch 每 10 ms 递增，每次调用按 `timeoutSec` 设 deadline，死循环被 trap 并转成 `wasm_error`。模块默认 0 个 import；文件、网络等能力以后由宿主显式注入的 import 提供。

参考 guest：`tools/wasm/test-guest`（回显参数、可选死循环用于验证超时）。

## 4. 阶段与 PR 拆分

- **阶段一（本 PR）**：本决策记录；manifest 识别 `wasm` 工具声明并**明确拒绝安装**（`wasm_not_supported`），避免装出「调用必报错」的插件；补校验与测试。不改任何现有行为。
- **阶段二**：`PluginRuntime` 接口 + `WasmtimeRuntime` 实现；原生库构建进 CI（按 ABI）；模块缓存、ABI（JSON + 线性内存）、内存/执行限额；独立进程 + watchdog + 熔断。
  - 已完成：`WasmEngine` 契约、`WasmModuleCache`、`PluginWasmRuntime` 与工具分派（PR #449）；Wasmtime JNI 引擎（`tools/wasm`，arm64 库随 APK，模块缓存/epoch 超时/64 MiB 内存上限/ABI v1）；独立进程 `:wasm`（`WasmService` + AIDL + `WasmProcessEngine`，宿主超时、崩溃重连、连续 3 次失败熔断）；模块安装时哈希清单与运行前复核；ABI 文档与可安装示例（`docs/dev/plugin-wasm-abi.md`、`examples/plugins/com.example.wasm-echo`）。调试包启用（`BuildConfig.WASM_PLUGINS`），发布包等真机 arm64 验收后再打开。
  - 待完成：真机 arm64 验收。宿主能力 import 已含日志、插件设置读写（`settings_get`/`settings_set`）、workspace 读写与网络（`http_request`，反向 Binder 由 App 进程执行，见 ABI 文档）。
- **阶段三**：新插件默认 Wasm；把示例插件（飞书）迁移为 Wasm；脚本通道降级为兼容层，按实际使用情况决定下线时机。

## 5. 未决问题

1. **原生库来源**：CI 构建后入库 / 预编译随版本分发 / 运行时按需下载（签名与 SHA-256 校验、落盘只读）。取决于 Mika 的分发渠道（GitHub APK vs 应用商店）。
2. **ABI 传输方式**：线性内存 `ptr+len` vs WASI stdio；host import 白名单（log、args、fs、http、settings）。
3. **崩溃隔离形态**：先普通独立进程（`android:process=":wasm"`），验证后升级 `isolatedProcess`；两者的 Binder 协议需要单独设计。
4. **平台验证**：Android 15 的 16 KB 页、JIT 可执行内存、定制 ROM（小米/华为）的信号处理；目前只验证了除零 trap。
5. **端到端 JSON 性能**：ABI 定稿后补测（当前表内留空，不臆造数字）。

## 6. 证据

测量工程与原始数据在本地 `wasm-spike/`（未随仓库分发，体积较大）：

- `wasm-spike/PERF-REPORT.md`、`COMPARISON.md`、`SPIKE-REPORT.md`；
- `wasm-spike/evidence/bench/container-run-final.log`、`summary.csv`（脚本逐次计时）；
- `wasm-spike/evidence/rootfs-surface.txt`、`assets/runtime/packages.json`（攻击面与包清单，rootfs SHA-256 `74404757…26f2a2`）；
- `wasm-spike/evidence/wasm-android-measurements.txt`（Endive/Wasmtime 原始 logcat）；
- `wasm-spike/android-test/`、`wasm-spike/wasmtime-jni/`（可复现工程）。
