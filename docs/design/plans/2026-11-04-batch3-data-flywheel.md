# 第三批：数据飞轮闭起来（2026-11-04 规划）

上一批（E2/D2）解决的是「测得准、看得懂」：被测对象可选、Prompt 可版本化、成本可计量、
报告可聚合、BadCase 可归类。这一批解决**数据从哪来、怎么自动回流**——飞轮的入口还差最后一公里。

现状缺口（实测确认）：

| 缺口 | 现状 | 后果 |
| --- | --- | --- |
| 点踩无理由 | 一次点击落库，`tags` 后端已支持但前端没入口 | BadCase 要靠管理员事后回忆归类 |
| 隐式信号无上报 | `platform.user_signal` 与 `/signals` 已就绪，前端没有任何调用点 | 采纳率/重生成率/编辑占比为空，质量只能靠 judge |
| Trace 不能浏览 | 主站有轨迹 API，但数据中台没接 | 想从线上 run 建用例要手工复制粘贴 |
| 门禁不能给 CI 用 | `/gate` 只有管理员 JWT | 发布脚本无法自动判定 |
| 线上未发布 | 服务器仍是 0.4.1 | 新能力只在本地可用 |

## 范围

### B3-1 反馈入口升级（最小打断）

点 👎 **仍然立即落库**，随后在消息下方浮出一行快捷标签（不准确 / 不完整 / 跑题 / 格式差 / 太慢），
点标签 = 追加到刚才那条反馈；不点也不会丢反馈。

- 主站前端 `ChatRoom.vue`：👎 后渲染 chips；`sendFeedback` 记录返回的 feedback id；点 chip 调 PATCH。
- data-service 新增 `PATCH /api/ai/feedback/{id}/tags`：仅本人、仅 `status=open` 可改；标签上限 8 个。
- 验收：点踩 → 数据中台出现记录（无标签）→ 再点两个标签 → 列表显示两个标签；刷新页面后仍在。

### B3-2 隐式信号真实上报

在真正发生的动作上埋点，fire-and-forget（失败只 debug 日志，不影响交互）：

| 动作 | 事件 | subject |
| --- | --- | --- |
| 复制回答 / 复制消息 | `copy` | run |
| 重新发送（改问重发） | `regenerate` | run |
| 继续生成 | `regenerate` | run（props.mode=continue） |
| 创作产物采纳 | `adopt` | asset |
| 创作产物编辑保存 | `edit` | asset（props.distanceRatio） |
| 导出（文件/产物） | `export` | asset 或 run |

- 前端新增 `frontend/src/api/signals.js`：`reportSignal(event, subjectType, subjectId, props)`，
  用 `VITE_DATA_API_URL`（与 feedback 同一基址），`keepalive: true` 保证跳转前也能发出去。
- 验收：点一次复制 → `/signals/summary?days=1` 里 `copy` +1；断网时 UI 无任何异常。

### B3-3 Trace 浏览 + 从 trace 建用例

主站新增内部端点（内部令牌保护，只读）：

- `GET /api/internal/eval/runs?limit=50` → 最近 run 概览（runId、conversationId、question、answer 摘要、
  status、token、createdAt）；租户内全量，不限定用户。
- `GET /api/internal/eval/runs/{runId}/trajectory` → 复用 `RunTrajectoryService`（现有实现按 tenantId+userId 校验，
  内部端点改为按 tenantId 校验）。

data-service 新增「轨迹」页签：

- 列表：最近 run（可按状态/时间过滤）；
- 抽屉：时间线（计划步骤 / 工具调用 / 模型调用 / 执行事件 / token）；
- 底部「建回归用例」：选目标数据集（默认「线上回流-单轮直调」）→ 复用 backflow（question/answer +
  轨迹摘要进 notes）→ 成功后给跳转链接。

验收：从打开轨迹列表到用例出现在评测集草稿 ≤ 1 分钟，全程不复制粘贴。

### B3-4 门禁给 CI 用

- 新增 `PLATFORM_CI_TOKEN`（eval-service 配置）；AuthFilter 识别 `X-Aster-Ci-Token`，
  只允许 `GET /experiments/**`（读）与 `GET /experiments/{id}/gate|report|export`。
- 验收：`curl -H 'X-Aster-Ci-Token: …' /gate?minPassRate=0.9` 返回判定；
  同令牌写数据集返回 403。

### B3-5 发布 0.5.0

- 镜像：`aster-backend` / `aster-frontend` / `aster-eval-service` / `aster-data-service`。
- 迁移：V25（prompt 资产/成本/预算/并发）、V26（根因/信号）。
- 部署顺序：backend 先起（Flyway 所有者）→ 两个中台 → frontend → 重启 nginx-proxy。
- 验收：四容器 healthy；`/`、`/eval/`、`/data/` 200；线上跑一次 PROMPT 实验并拿到报告与成本。

## 顺序与理由

1. **B3-1 + B3-2**：入口数据先通，后面所有分析才有原料；改动集中在一个组件，风险低。
2. **B3-3**：依赖 B3-1/B3-2 的数据，但实现独立；主站先加内部端点，再写页面。
3. **B3-4**：独立小改，随时可插。
4. **B3-5**：前三项合并后一次发布，减少线上重建次数。

## 风险

- `ChatRoom.vue` 是与并行会话共用的大文件，改动尽量收敛（只加 chips 与一个 emit），避免再次冲突。
- 内部轨迹端点会暴露跨用户 run，仅限内部令牌；不要顺手把它加到公网可达路径。
- 埋点量大时要防表膨胀：`user_signal` 先不过期，观察两周后再定归档策略。

## 不在本批

- 在线评测（生产 run 抽样 judge）
- 全局实验并发 / 队列优先级
- Prompt 资产与线上 agent 运行时绑定（`agent_run.prompt_ref`）——这是让「PROMPT 实验测的就是线上那段话」
  完全成立的前提，改动涉及 agent 配置与运行时，单独一批做。
