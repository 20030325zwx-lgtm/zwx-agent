# UI1 + UI3 素材与任务闭环会话（2026-11-02）

## 改动内容

- **UI1**：首页主输入收窄到 760px；作品列表新增列表视图（`ProjectRow.vue`），网格/列表切换持久化到 localStorage。
- **UI3 素材**：V20 加 `creative_asset.business_type`（可空 = 未归类）并回填角色/地点/镜头；
  `GET /assets` 支持 `businessType`/`trashed`；`PATCH` 支持改分类；新增 `POST /assets/{id}/restore` 与
  `GET /assets/{id}/usage`（镜头候选 + 角色/地点参考，跨项目）。
- **素材库 UI**：全部/回收站分段、左侧业务分类筛选、回收站「已删除」占位与恢复按钮；
  预览浮层新增「使用位置」清单与分类下拉。
- **任务聚合**：`GET /ai/creative/jobs/summary`（进行中/失败/保存失败/待确认/待选片 + 最近失败条目含项目名）；
  首页待处理改用它。
- bug：聚合接口误用 `media_job.original_prompt`，已改为 JSONB 取值并同步 RowMapper。

## 验证结果

- `npm run build`、`mvn -DskipTests package` 通过；V20 迁移应用成功；`CreativeAssetServiceTest` 6/6。
- 浏览器（1440，零计费）：首页 760px 主输入与待处理计数、业务分类筛选与编辑、使用位置 1 处引用、
  回收站 4 条占位与恢复守卫 400、作品列表视图 4 行、summary 接口数据正确。
- 证据：`docs/test/results/2026-11-02-ui1-ui3-assets-tasks.md`。

## 遗留事项

- 回收站未做真实恢复往返（避免动用户历史删除数据）；无彻底删除入口（刻意）。
- 素材删除保护仍只拦进行中任务引用，未覆盖镜头/角色引用。
- 业务分类批量修改、素材搜索、任务中心专用分组页（UI3 剩余）未做。
- 智能体融合设计（`docs/design/2026-11-02-creative-agent-integration.md`）已阅读并给出评估，尚未实施。

## 追加：下拉框统一样式

原生 `<select>` 弹层由系统绘制（黑底大字号），CSS 无法覆盖。新增 `components/ui/DropdownSelect.vue`，
替换创作模块 16 处、设置与工作流编辑器 7 处；知识库表格内 4 处保留。顺带修复快速生成页视频地址
读不到（改为读 Shell 的 `videoUrls` 缓存）。详见 `docs/bugs/bug-2026-11-02-native-select-popup.txt`。

## 环境注意事项

- 云库延迟高：单测按分钟计，不要用短超时判定"挂起"。
- 本轮改动了多个组件并新增目录，验收前重启过前端 dev server（HMR 残留会造成"空白/旧样式"假象）。
- 后端为加载新 JAR 重启过，V20 已应用；`application-local.yml` 未读取、未改写；全程未触发生成计费。
- 主题保持浅色（验收时切换过深色再切回）。
