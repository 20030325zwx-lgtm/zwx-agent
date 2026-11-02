# UI1 + UI3 验收证据

日期：2026-11-02。基线：`73d1a48` + 本批改动。**真实生成计费：0 次**。

## 构建与迁移

| 项目 | 结果 |
| --- | --- |
| `npm run build` | ✅ `✓ built in 18.60s` |
| `mvn -DskipTests package` | ✅ BUILD SUCCESS |
| V20 迁移 | ✅ `Successfully applied 1 migration … now at version v20`（含 CHECK、索引与三类回填） |
| `CreativeAssetServiceTest` | ✅ 6/6（构造器已适配新依赖） |

## 浏览器验收（1440×900，真实数据）

| 检查点 | 结果 |
| --- | --- |
| 首页主输入宽度 | ✅ 760px（measure `composeWidth=760`），不再占满内容列 |
| 首页待处理 | ✅ 计数 `0 生成中 / 2 最近失败 / 0 结果待确认 / 0 待选片`；失败条显示作品名（视频生成验收、秋季新品视觉） |
| 业务分类筛选 | ✅ 侧栏出现 来源/类型/业务分类/项目 四组；业务分类为 全部/角色/地点/道具/镜头/其他 |
| 回填结果 | ✅ 打开已有候选素材，分类已是「镜头」（V20 回填生效） |
| 分类编辑 | ✅ 预览浮层内改为「道具」→ 徽标与下拉同步；改回「镜头」成功 |
| 使用位置反查 | ✅ 预览显示「使用位置 1 处引用：镜头 第 1 集 · 第 1 场 · 镜 2（已采用）」 |
| 回收站 | ✅ 4 个已删除素材，媒体区显示「已删除」占位，操作只剩「恢复」 |
| 恢复守卫 | ✅ 对未删除素材调用 `POST /restore` 返回 400「素材不在回收站」 |
| 作品列表视图切换 | ✅ 切到列表后 4 行（`pr-row`），封面/名称/类型/更新时间/打开齐全；刷新后保持（localStorage） |
| 跨项目待处理接口 | ✅ `active 0, failed 2, ingestFailed 0, reconciling 0, pendingSelection 0`，条目带 projectName 与 prompt |

截图（会话工件）：
- 首页（1440 浅色）：`.../call_00_23knPbEOWIRDJ6Dui51i6492-tool-result-94625b81-058f-4ec4-9552-deaa067ca48b.png`
- 回收站（1440 浅色）：`.../call_00_245sU4L42DYm9L6k17w34305-tool-result-492bda0c-3433-4b7a-9aa9-0b7ecc39d65d.png`
- 作品列表视图（1440 浅色）：`.../call_00_ET_g79a1sg369yHYbgnSU7L0751-tool-result-d33db34e-d119-4f76-966a-f1c3da0a34d8.png`

## 缺陷修复

`docs/bugs/bug-2026-11-02-summary-json-prompt.txt`：聚合接口误用 `media_job.original_prompt`，改为从
`input_snapshot->>'prompt'` 取值并同步 RowMapper。

## 未测 / 已知限制

- **未做真实恢复操作**：回收站里的 4 个素材是历史删除数据，未替用户恢复；只验证了 UI 与接口守卫。
- 未做删除后再恢复的完整往返（需要真删一张现有素材，避免改动用户数据）。
- 业务分类的批量修改、素材全文搜索、删除保护覆盖镜头/角色引用未做（见设计文档「取舍与未做」）。
- 列表视图只验了 1440；1280/1024 仅按样式适配，未逐像素检查。
- 任务中心专用分组页（UI3 剩余部分）未做。
