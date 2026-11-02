# 飞书消息（能力包示例）

```
manifest.json         P1a：只带技能，可以直接安装
manifest.full.json    P1b/P2：加 send 工具（webhook）、设置页和 sandbox.run/network/page 权限
skills/feishu/SKILL.md
scripts/send.py       工具脚本：POST 到飞书自定义机器人 webhook
pages/                动态设置页：填 webhook/secret，走白名单 mika 桥
build.py              打 zip（只用 Python 标准库）
```

## 构建

```bash
python examples/plugins/com.example.feishu/build.py
# 只打技能版:
python examples/plugins/com.example.feishu/build.py --only skill
```

zip 条目统一用 `/`，只包含 manifest、`skills/` 和（工具版的）`scripts/`。

## 安装后的行为

### P1a（技能）

- 插件技能名是 `com.example.feishu/feishu`，`skill_read` 可写 `plugin:com.example.feishu/feishu`。
- 停用插件后，下一次任务里搜不到；卸载会删技能。

### P1b（工具）

- manifest 里的 `send` 变成 `mcp_com_example_feishu__send`，风险按 `external` 走现有审批。
- 脚本约定：参数 JSON 由 P1b 写到 `MIKA_PLUGIN_ARGS` 指向的临时文件，脚本最后一行输出 JSON。
- 没有 `network` 权限时不能联网；有权限时走 Mika 的全局代理。

### P3（secret、联网、日志）

- 页面写的 `secret` 类型设置进 `secrets.bin`（Keystore 加密），页面只能看到「是否已设置」；脚本通过 `MIKA_PLUGIN_SECRETS` 指向的临时 JSON 文件读取，退出前删除。
- 插件详情的「联网」开关默认关；manifest 里的 `network` 只是申请。只有用户打开、且全局网络允许时，脚本和页面才联网。
- 每个插件有 `logs/plugin.log`（上限 1 MB），详情里可以清空；日志只记工具名/退出码/耗时，不记参数和 secret。

### P2（页面）

- 完整版安装后，插件详情里有「打开页面」；页面通过 `window.mika` 白名单桥读写字设置（`settings.get/set`）、调用 `tools.list/call`。
- 页面只从包内 `pages/` 加载，固定 origin，默认断网；没有 `network` 权限或全局不允许联网时 `connect-src 'none'`。
- `pages/app.js` 是独立脚本，页面没有内联脚本；宿主会再加一层 CSP。

## 手动验证工具脚本

```bash
echo '{"webhook":"https://open.feishu.cn/open-apis/bot/v2/hook/xxx","text":"来自 Mika 的测试"}' \
  | python examples/plugins/com.example.feishu/scripts/send.py
```

真实 webhook 会真的发消息；先确认目标和内容。脚本不打印 webhook/secret，失败时最后一行是 `{"ok": false, "error": ...}`。
