# 插件 Wasm 运行时(v2)决策与设计

日期:2026-11-23
状态:实现中(真机 arm64 验收由使用者完成后再打开发布包开关)

## 背景

main 的插件系统 P1b 采用**进程内 shell 运行时**:插件声明 `.sh` 工具、`promptTransform`
与技能,由 App 进程内的 `PluginScriptRunner` 执行。这条路线刻意把运行时隔离后置:
脚本能读 App 私有目录、对话、记忆与模型密钥,装前只做诚实披露。

Wasm 运行时解决的是**第三方插件的可执行代码隔离**:模块只能通过显式 import 调用宿主能力,
不能直接发系统调用。隔离靠的是 Wasm 沙箱本身,不是进程权限(见下文「隔离边界」)。它不替代 P1b 的 shell 路线,而是一个可选的、更受限的工具载体。

旧的 Wasm PR(#448/#449)叠在已被替代的插件栈上,已由维护者关闭。本设计基于当前 P1b 运行时重做。

## 目标与非目标

### 目标

1. manifest 可以声明 Wasm 工具(`tools[].wasm`),与 shell 工具二选一。
2. 引擎跑在独立进程 `:wasm`;原生崩溃不会带走 App 进程,宿主重连并熔断。这是**崩溃隔离**,不是权限隔离。
3. ABI v2 提供最小宿主能力:`log`、`fs_read`/`fs_write`(插件自己的 workspace)、
   `http_request`(需要 manifest 声明 `network`)。
4. 模块安装时记录 SHA-256,运行前复核;模块被换过就拒绝执行。
5. 原生库有可复现构建脚本、固定 NDK/Rust 工具链、CI 校验;二进制来源写进文档。
6. 调试包默认启用;发布包在真机 arm64 验收通过前保持关闭(`BuildConfig.WASM_PLUGINS`)。

### 非目标

- 不把 Wasm 做成默认工具载体;shell 插件仍是 P1b 的主路径。
- 不引入插件设置/secret/动态页面(P1b 之后的能力;ABI 中的 `settings_*` 当前不授予)。
- 不做跨平台原生库(x86_64/armeabi);只分发 arm64,其他设备安装后调用会干净失败。
- 不做运行时权限确认卡片;沿用 P1b 的「声明 + 装前披露」。

## 架构

```
core/tool/PluginTool.kt        插件工具规格 + 脚本/Wasm 两种执行体;WasmRunner 契约(可测,不依赖 Android)
app/plugin/WasmSupport         App 进程里唯一的运行时入口(开关、ABI 检查、联网客户端)
app/plugin/PluginWasmRuntime   模块读取、SHA-256 复核、LRU 缓存、能力上下文
app/plugin/WasmProcessEngine   Binder 调 :wasm;兜底超时、重连、引擎故障熔断、空闲解绑
app/plugin/WasmService         :wasm 进程内的 Wasmtime JNI 入口
tools/wasm                     Rust JNI 桥 + 可复现构建脚本
```

调用链:

1. `PluginRegistry.toolSpecs()` 把 manifest 的 `wasm`、权限与插件 id 放进 `PluginToolSpec`。
2. `TaskTools` 装配 `PluginTool`;Wasm 工具把参数 JSON 直接交给 `WasmRunner`。
3. `PluginWasmRuntime` 按安装时哈希复核模块,构建上下文 JSON(权限、workspace 路径)。
4. `WasmProcessEngine` 经 AIDL 把模块(共享内存)、上下文、参数、超时与宿主回调送到 `:wasm`。
5. 原生侧编译/缓存模块,新建实例,执行 `mika_tool_call`,返回 `(ptr << 32) | len` 指向的结果 JSON。

## 权限与安全

| manifest 声明 | 作用 |
| --- | --- |
| `run-wasm` | 允许 Wasm 工具;没有它时安装器拒绝 |
| `network` | 允许 `mika.http_request`;没有它时原生侧直接返回 -2,宿主也不提供回调 |

- 文件能力只作用于 `files/plugins/<id>/workspace`,路径必须相对且不含 `..`;写入 1 MB 上限。
- 联网请求在 App 进程执行,沿用 Mika 的代理/证书;本机、局域网与保留地址按网页下载的同一条规则拒绝
  (`DownloadAddressPolicy`,域名解析后的地址也查);方法、头、正文、重定向与超时都有上限。
- 模块字节在安装时写 `wasm.sha256`;运行前复核,和脚本哈希的做法一致。
- 单实例内存上限 64 MiB;超时按 manifest `timeoutSec`(1–600),由 epoch 中断执行。
- 日志只记事件、工具名、耗时与状态码;不记参数、输出全文、密钥或 header/正文。

## 隔离边界

`:wasm` 是同一个 App 的另一个进程,**和 App 同一个 Linux UID,不是 `isolatedProcess`**。所以:

- 能挡住插件的是 Wasm 沙箱:guest 代码只能调 `mika.*` 这几个 import,不能直接开文件、发网络请求或调系统服务;
  文件只到自己的 workspace,联网只经 App 进程里的 `PluginHttp`。
- `:wasm` 进程本身有 App 的全部权限和私有目录的读写权。如果 Wasmtime 或 JNI 桥有漏洞让 guest 逃出沙箱,
  逃出去的代码就有 App 的权限;独立进程在这里只防「原生崩溃带走 App」,不防这种情况。
- 不用 `isolatedProcess` 的原因:workspace 读写(`fs_read`/`fs_write`)目前在原生侧直接对
  `files/plugins/<id>/workspace` 做文件操作,isolated 进程没有 App 私有目录的权限。要换成 isolated,
  需要先把文件能力也改成经 `IWasmHost.aidl` 回调由 App 进程执行(同联网),再重编原生库;这一步留给后续。
  其他部分已经按 isolated 的约束写:模块经共享内存传、联网经反向 Binder、引擎进程里不做 App 初始化。
- `MikaApp.onCreate` 在 `:wasm` 进程里直接返回(`WasmSupport.isEngineProcess`),不跑主进程的恢复、胶囊、微信、定时等初始化。
- 文档与界面不把它叫「沙箱进程」;对用户的披露沿用插件的「声明 + 装前披露」。

## 进程与调用的生命周期

- App 进程里只有一个 `WasmProcessEngine`(`WasmSupport.runner` 共用),连接、熔断、模块缓存都是全局的。
- 第一次调用时 `bindService(BIND_AUTO_CREATE)` 拉起 `:wasm`;最后一个调用结束 60 秒后解绑,系统可以回收进程。
- Binder 调用不可取消:调用放在引擎自己的协程里,宿主最多等「插件超时 + 5 秒」;超时就让 `:wasm` 自己退出
  (`IWasmRuntime.aidl` 的 oneway `exit()`,卡住的那次调用随之返回 DeadObjectException),算一次引擎故障。
  任务被停时不等结果,调用在后台跑到原生侧的 epoch 超时为止。
- 错误分两类:服务报回的 IllegalStateException 是插件错误(trap、超时、缺导出、输出太大),只让这次调用失败;
  RemoteException(进程死了、Binder 不通)和兜底超时是引擎故障,断开重连,连续 3 次熔断到 App 进程重启。
- 反向回调(`IWasmHost.aidl`)里抛出的异常统一换成 IllegalStateException,免得在 App 进程的 Binder 线程上成为未捕获异常。

## 安装包体积

- `libmika_wasm.so` 未压缩 7,346,216 字节(约 7.0 MiB,arm64-v8a)。`useLegacyPackaging = true`,原生库在 APK 里
  压缩存放、安装时解到磁盘:APK 增加约 3.1 MiB(调试包实测压缩后 3,219,139 字节),安装后多占约 7 MiB。
- 只打 arm64-v8a(`jniLibs/arm64-v8a`,和沙箱的原生程序一样),没有其他 ABI 的副本;发布构建另出的 arm64 小包同样带它。
- 发布包目前 `WASM_PLUGINS = false`,原生库照样打进去但不加载。可选做法(未做):发布构建在开关打开前用
  `packaging.jniLibs.excludes` 排除 `libmika_wasm.so`;或把 Wasm 引擎做成按需下载/动态功能模块。

## 原生构建与来源

- 构建: `tools/wasm/build-native.sh`;`--check` 只重编并与仓库里的
  `app/src/main/jniLibs/arm64-v8a/libmika_wasm.so` 比较 SHA-256。
- 固定 NDK `28.2.13676358`、Rust 工具链 `1.99.1`(见 `tools/wasm/rust-toolchain.toml`),
  `RUSTFLAGS` 去掉构建路径,便于跨机器比较。
- CI 的 `wasm-native` job 安装同一版本 NDK/Rust target 后跑 `--check`;
  `cli`/`app` job 继续跑插件 JVM 测试与 `compileDebugAndroidTestKotlin`。
- 当前提交的二进制 SHA-256 与构建命令记录在 `tools/wasm/NATIVE-PROVENANCE.md`。
- 静态链接进去的 Wasmtime 与其他 Rust 库的许可证与署名在 `licenses/THIRD_PARTY_NOTICES.md`(「Wasmtime」「libmika_wasm.so 里的其他 Rust 库」),
  App 的「开源许可」页同步列出;升级 `tools/wasm/Cargo.lock` 时一起核对。

## 发布开关与验收

- `WASM_PLUGINS`:debug 为 true,release 为 false。
- 真机验收(用户执行):安装示例插件(`plugins/examples/com.example.wasm-echo`)、回显、死循环超时、trap 后继续调用、`fs_write`、
  联网成功与未授权 -2。通过后把 release 开关打开。
- arm64 之外的 ABI 不随包分发;`WasmSupport.available` 为 false 时 Wasm 工具返回
  `wasm_unavailable`,不影响其他插件。

## 回滚

- 关闭 `WASM_PLUGINS` 即可停止登记引擎;已安装的 wasm 插件保留目录但工具调用返回
  `wasm_unavailable`,不会影响 shell 插件与技能。
- 如需移除引擎,删除 `:wasm` 服务、AIDL 与 jniLib 即可;manifest 中的 `wasm` 声明保留向前兼容。
