# 画布契约与创作闭环 · 第一批改造记录（2026-10-31）

> 回应 `2026-10-31-infinite-canvas-comparison-and-refactoring.md`。按评审的分阶段建议交付
> 阶段 A（契约与状态边界）+ 阶段 B 最小闭环；C/D 批次见文末。

## 已交付

### A1 节点契约：静态注册表 + 连线即绑定

- 新增 `frontend/src/views/workflow/nodeCatalog.js`：14 种节点类型在一处声明
  `{ label, color, group, hint, help, defaults, outputs[kind], inputs[param+kind+accept], required }`。
  画布节点库（分组+搜索）、添加菜单、参数表单、节点色、连线绑定、运行前预检全部由它驱动。
- 输出 kind：`search_image→image_url`；媒体/海报节点→`asset`；文本类→`text`；`novel_chapter_save→json`。
- **连线即绑定**（WorkflowEditor `onConnect`）：kind 兼容时自动把引用表达式写入目标参数——
  text→`{{源.output}}`、asset→`{{源.output.assetId}}`；`imageAssetIds` 追加去重（上限 3，满员不覆盖）；
  边上显示绑定参数名 label。绑定本质是现有表达式写进 params，**持久化格式不变，旧图零迁移**；
  `applyGraph` 从目标 params 反推 `{{源.output…}}` 引用显示 label（手写表达式同样识别）。
- 类型不兼容 → 拒绝连线并说明（「图片生成（创作）的输出与文件导出的输入类型不匹配」）；
  目标为自由文本节点（llm_text/text_template）或绑定已占用 → 允许作为执行依赖并提示引用写法。

### A2 运行前预检（前后端独立）

- 前端 `validateGraphForRun(nodes)`：逐节点检查必填参数非空白（`{{引用}}` 算已填）、
  引用的 `{{节点.output}}` 指向存在的节点；错误列表在运行对话框顶部展示，
  点击「定位」选中问题节点；有错误禁止发起。
- 后端 `WorkflowService.REQUIRED_PARAMS` + `missingRequired(type, params)`（纯函数，5 项单测
  `WorkflowGraphValidationTest`）：保存版本与发起运行共用校验，独立拒绝非法图——
  语义为「外层约束 AND，组内备选 OR」（media_image_edit 同时要求 prompt 与 imageAssetIds；
  image_understand/poster_render 为 image|imageAssetId 二选一）。

### A3 状态边界：编辑历史与运行事实分离

- 撤销/重做（快照栈，上限 50，连续变更 600ms 合并）：覆盖添加/删除/连线/改连/自动布局/参数编辑/拖动；
  Ctrl+Z / Ctrl+Shift+Z + 顶栏按钮。**只作用画布与定义**——运行记录、素材、已产生费用不受影响
  （inspector 提示文案明示）。
- 顶栏「未保存」徽标（graphDirty 改响应式）；运行面板顶部固定「运行使用发起时的定义快照，
  此后编辑不影响进行中的运行」；运行详情显示「定义快照 vN」。
- 清理死代码（旧 `start()` 直跑路径、未用的 loadedGraphs）。

### B1 创作闭环最小场景：双候选 → 对比 → 选定 → 合成 → 归档

- V14 种子模板：`dual-candidate-poster`（start → 候选A 卖点直击 ∥ 候选B 场景氛围，两个独立分支
  单支失败不阻断，产物自动归档）与 `selected-poster`（选定素材 → 视觉理解 → 文案 JSON → 海报合成，
  零生图计费）。
- 素材库「对比」选择模式（≤2 张，浮条提示）→ `AssetCompare` 组件并排对比 → 点选一张 →
  「用此图合成海报」（卖点关键词可空）→ 绑定当前项目发起 → 海报（DERIVED）归档 → 下载。
  全程不手填 UUID。

### 布局改造（评审 §3.1 建议的骨架）

- WorkflowEditor 重排为：顶栏（含撤销/重做/未保存/节点库开关）→ **左**节点库（可收起，分组+搜索）→
  **中**大画布 → **右**参数面板（选中才出现）→ **底**运行与结果区（可收起，左运行列表+右详情横向布局）。

## 验证记录（2026-10-31）

- 单测：WorkflowGraphValidationTest 5 项全绿；前端 build 通过；后端 package 通过。
- 浏览器 E2E（临时工作流 contract-e2e-temp，验证后已删除）：
  - 旧图兼容：淘宝 v3 / 海报 / 小说模板打开正常，节点完整、边 label 反推显示；
  - 撤销/重做：添加节点 → 撤销消失 → 重做回来 → 再撤销恢复 ✓；
  - 连线绑定：拖媒体输出→图片理解，toast「已绑定 图片理解.imageAssetId」，边 label「imageAssetId」，
    参数写入 `{{media_image_generate_….output.assetId}}` ✓；
  - 类型拒绝：拖媒体输出→文件导出，toast「不能连接：…类型不匹配」，边未添加 ✓；
  - 未保存徽章 + 运行拦截 ✓；预检：清空必填参数后运行对话框显示错误列表（本次以后端校验与代码路径验证为主）。
- 闭环 E2E（项目「秋季新品视觉」，计费 2 张生图）：
  双候选运行 SUCCEEDED（A/B 均归档）→ 素材库对比选定 → 合成运行 SUCCEEDED（理解→文案→海报）→
  海报素材 DERIVED/READY/1200×1600 归档 ✓。

## 与评审建议的取舍说明

- 撤销采用**快照栈**而非完整命令层：第一版行为一致、实现可靠；命令化（重映射复制、分组）随 C 批次演进。
- 预检只做「引用节点存在」，未做「必须为上游」：上游错误运行时会以空串暴露，二期把边关系传入校验。
- 左栏第一版只有节点库+搜索；「本项目素材」列表依赖运行绑定的项目上下文，随 B 批次余项补。
- 双候选用两个独立节点而非 media_job 加 n 参数：零后端改动、提示词可差异化（对比更有意义）；
  单节点多张（配额与计费展示）留待 C 批次。

## 未做（后续批次）

- C 批次：局部重跑/单节点重试、输入快照与结果版本管理、幂等强化（双击提交已在 idempotencyKey 覆盖）、
  慢供应商下的调度公平性压测。
- D 批次：Agent 结构化画布工具（读图摘要/提议修改/校验应用/发起运行）、模板导出导入（脱敏 bundle）、
  修订冲突处理。
- 其他：WAITING_USER 人工确认节点（评审 §5：第一版由用户选定后发起下一次运行，本次即此形态）、
  模型能力 schema 驱动动态表单、引用 role 化（模特/上衣/裤子）、性能基线（100/300 节点）。
