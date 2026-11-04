# 对话内的模型、思考档位与工作区范围

对应 issue #247。这里只写实现后的存储格式、解析顺序和限制边界，设计决策和历史见仓库外的 `CHAT-MODEL-WORKSPACE-PLAN.md`。

## 存储

### 对话设置 `files/conversations/<id>.settings.json`

和 `.title`、`.pin` 一样是对话文件旁边的旁文件；没有实际设置时不写文件。示例（`app/src/test/resources/samples/conversation-settings.json`）：

```json
{
  "main": {"kind": "group", "id": "group-main"},
  "reasoningEffort": "high",
  "skills": {"kind": "only", "ids": ["documents", "personal-notes"]},
  "mcpServers": {"kind": "all"}
}
```

- `main`：`{"kind":"model","id":…}` 或 `{"kind":"group","id":…}`；没有表示跟随设置里的主模型。
- `reasoningEffort`：目标档位；没有表示跟随模型/用途默认。
- `skills` / `mcpServers`：三态，没有表示 `FollowGlobal`：
  - `{"kind":"all"}`：这个对话完整挂载。
  - `{"kind":"only","ids":[…]}`：只用这些；空集是明确全关。
- 删除对话时一起删；分支时整份复制；`ConversationStore.list` 不会把它当成一个对话。
- 读不懂 / 字段缺失 / 未知 `kind`：按默认（跟随设置）。

### 全局允许列表 `files/settings/workspace-access.json`

示例（`app/src/test/resources/samples/workspace-access.json`）：

```json
{
  "version": 1,
  "skillsAll": false,
  "mcpAll": false,
  "skills": ["documents", "research"],
  "mcpServers": ["mcp-browser"],
  "skillsKnown": ["documents", "research", "writing"],
  "mcpKnown": ["mcp-browser", "mcp-db"]
}
```

- 文件不存在 / 新安装：`skillsAll = true`、`mcpAll = true`，全部可用，包括以后新增的。
- 用户取消任意一项：对应 `all` 改为 `false`，集合里是明确选中的项；以后新增项不自动加入。
- 全不选：`all = false` + 空集，明确表示没有。
- 重新全选：`all = true`，集合清空。
- `skillsKnown` / `mcpKnown`：用户第一次自定义时见过的项；后来新增、还不在快照里的项在设置页标「新」，用户勾选/操作后并入快照。

## 解析顺序

```
对话三态（All / Only / FollowGlobal）> 全局列表
```

- 对话 `All`：完整挂载（含以后新增项）。
- 对话 `Only(ids)`：只用 `ids`，即使全局列表里没有也照用。
- 对话 `FollowGlobal`：用全局 `all` / 集合。

`null` 不表示“全部”；空集就是“没有”。

## 思考档位

- 对话选的是**目标档位**。具体模型只列它支持的档位；目标不在支持列表里时不传（用服务商默认）。
- 组：成员支持目标就原样；成员最高档更低就降到它的最高档；成员最低档更高就升到它的最低档；成员没写档位就不传。
- UI 对「列表没写档位、由界面推出来的通用档位」有提示；组会注明成员可能按各自最接近的档位执行。
- `RunEvent.ModelReply.reasoningEffort` 记录这次请求实际发出的档位；组里换成员也能看到实际值。

## 技能限制

- 只限制 `skill` 和 `personal` 两类；App/站点知识不受影响。
- `GuideCatalog.withAllowedSkills(names)` 同时过滤 `items` / `find` / `view`，直接写 `skill:name` / `personal:name` 不能绕过。
- 脚本：
  - 默认任务（全部可用）继续用全局 `files/sandbox/skills`。
  - 受限任务把允许技能的脚本子集同步到 `files/sandbox/skill-sets/<conversation>`，`ProcRunner` 绑到 `/opt/mika/skills`；`run_command` 只能看到子集。
  - `run_bundled_script` 的清单核对也只看子集。
  - 删除对话或清理工作区时一起删。

## MCP 限制

- `McpServerStore.connectionsFor(..., allowedServerIds)`：只连接**选中且启用**的 server；空集一个都不连。
- 未选中的 server 不连接、不暴露工具，也不记这次的连接失败。
- 任务开始时固定一份；运行中改设置只影响下一次任务。

## 边界

- 不是安全隔离：受限任务仍能执行允许的脚本、访问允许的 MCP；限制只缩小挂载面。
- 不做运行中热切换；不做按消息切模型/档位；不做聚合/racing。
- 当前运行记录里记实际档位，不把“目标档位”当成实际值。
