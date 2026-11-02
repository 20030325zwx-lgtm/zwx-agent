# UI1 + UI3 素材与任务闭环：实施记录

日期：2026-11-02
基线：`73d1a48`（M1 项目工作区 + 素材卡修正之后）
上游：[UI 规范](./2026-11-02-ai-creative-ui-visual-design.md) UI1/UI3、[核心规划](./2026-11-02-ai-creative-core-product-plan.md) §8.2/§8.6/§8.7

## 目标

M1 只改了项目内工作区与令牌，首页/作品/素材/任务仍是旧形态，用户反馈"整体看不出来变了"。本批把 UI1（入口体验）与 UI3（资产与任务闭环，去掉需要新领域的部分）补齐，让素材真正成为"可管理、可复用、可追溯"的资产。

## 一、UI1 入口体验

- 首页主输入按 §8.1 收窄到 `min(760px, 100%)`，输入区高度 104px（含工具栏 120+）。
- 作品列表新增列表视图：`components/creative/ProjectRow.vue`（88px 16:9 封面、名称/描述、类型、更新时间、打开），
  网格/列表切换按钮位于工具栏右侧，选择写入 `localStorage`（`aster.creative.works.view`）。

## 二、UI3 素材中心（后端 + 前端）

### 数据（V20）

`creative_asset.business_type VARCHAR(16)`，CHECK 限定 `CHARACTER/LOCATION/PROP/SHOT/OTHER`，可空 = 未归类；
新增 `(tenant_id, user_id, business_type, created_at DESC, id DESC)` 索引。
回填顺序：角色参考图 → 地点参考图 → 镜头候选，其余保持 NULL（前端显示"未归类"）。

### 接口

| 接口 | 能力 |
| --- | --- |
| `GET /ai/creative/assets` | 新增 `businessType`、`trashed` 参数；`trashed=true` 只返回回收站 |
| `PATCH /ai/creative/assets/{id}` | 新增 `businessType`（传空清除分类） |
| `POST /ai/creative/assets/{id}/restore` | 回收站恢复：`DELETED → READY`，非回收站返回 400 |
| `GET /ai/creative/assets/{id}/usage` | 跨项目反查：镜头候选（集/场/镜 + 候选状态）、角色参考、地点参考 |
| `GET /ai/creative/jobs/summary` | 跨项目待处理：进行中/失败/保存失败/结果待确认/待选片 + 最近失败条目（含项目名） |

### 前端

- 素材库：顶部"全部素材 / 回收站"分段；左侧栏新增业务分类（全部/角色/地点/道具/镜头/其他）；
  回收站卡片只保留"恢复"，媒体区显示"已删除"占位而不是无限加载。
- 素材预览浮层（`AssetPreview.vue`）：新增"使用位置"区块（镜头/角色/地点，可跳转），
  底部新增分类下拉（PATCH 即时更新，不改动媒体本身）。
- 项目素材同样复用 `AssetCard` 与分类编辑。
- 首页待处理改用 `jobs/summary`：计数含"待选片"，失败条目直接显示所属作品名。

## 三、取舍与未做

- **不做彻底删除**：回收站只恢复，不提供物理清理入口；对象清理仍按原设计另做，避免误删不可逆。
- **不做按业务分类的批量操作**：需要多选与批量 PATCH，本批只做单张分类。
- **素材删除保护仍未覆盖镜头/角色引用**：现在能看到引用，但删除只拦截"进行中任务引用"（BR14）；
  下一步应把引用数接入删除确认。
- **任务中心仍是全局列表**：本批只加了"跨项目待处理"聚合与首页使用；按"生成中/待选片/失败"分组的专用页面（UI3 其余部分）未做。
- 素材库没有全文搜索：后端无 displayName 检索参数，未伪造前端搜索。

## 四、验证要点

见 [验收证据](../test/results/2026-11-02-ui1-ui3-assets-tasks.md)。
