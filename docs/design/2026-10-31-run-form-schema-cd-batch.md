# 运行输入业务化 + C/D 批次改造记录（2026-10-31）

> 回应「让用户填 `{{input.keyword}}` 不合理」：运行者只应看到业务字段，语法只属于模板作者且应降级为点击插入。
> 本批 = 输入声明 schema（R03 完整版落地）+ C 批「从节点重跑」+ D 批「模板导出导入」。

## 1. 声明式运行输入（去语法化）

- **V15**：`workflow_definition_version.inputs_json JSONB`，元素 `{key, label, type: text|asset|assets, required, placeholder}`；
  既有种子模板补声明（双候选/选定海报/小说/商品海报/淘宝）。
- **后端**：`validateInputs`（key 正则允许驼峰、label 1-20 字、type 枚举、唯一）+ `missingDeclaredInputs`
  （required 缺失报「请填写：商品关键词」业务化错误）——均纯函数，4 项单测；`start()` 在 keyword 合并后校验；
  `saveVersion` 随版本持久化声明；`DefinitionVersionView` 携带 inputs 给所有入口。
- **前端 `buildRunForm(definition)`**（workflowInputs.js）：声明优先，未声明回退 `deriveRunInputs` 扫描；
  三个运行入口（编辑器运行对话框 / 创作首页 / 项目页「用工作流创作」）统一渲染中文 label + 必填星号 +
  占位提示，asset 类型渲染 AssetParamField。**运行者全程不接触模板语法。**
- **编辑器**：inspector 新增「运行输入字段」编辑区（增删改 key/label/类型/必填/占位），保存随版本入库；
  参数区上方「插入引用」chips——点击把 `{{input.key}}` 追加到最近聚焦的参数框，手写语法成为高级路径。

## 2. C 批 · 从节点重跑（局部运行）

- `computeRerunSteps(parentSteps, graph, fromNode)` 纯函数（4 项单测）：
  fromNode **祖先闭包**继承父运行 SUCCEEDED 与原 output（媒体产物 assetId 直接复用，**不重复调用模型**；
  祖先未成功时回退 PENDING 重跑）；fromNode 及**下游闭包** PENDING 重执行；**其余平行分支 SKIPPED**（不重复计费）。
- `rerunFrom`（仅终态运行、归属校验）：新运行复用父 input/定义快照/项目绑定，artifacts 全新（旧结果不覆盖新运行）。
- `POST /ai/workflows/runs/{runId}/rerun`；编辑器运行面板每个步骤显示「从此步重跑」（终态运行时）。

## 3. D 批 · 模板导出导入

- `GET /ai/workflows/{key}/export`：`{format:1, key, name, description, graph, inputs, exportedAt}`
  —— 不含租户/用户/密钥/素材（素材依赖以 asset 输入声明表达）。
- `POST /ai/workflows/import`：校验 format/graph（validateGraph）/inputs（validateInputs）；
  **key 冲突自动改名** `{key}-import-{4位}`，响应 `{key, definition}` 供前端提示。
- Workflows 列表页：卡片菜单「导出模板」（下载 JSON）；头部「导入工作流」（选择 .json → 导入 → 提示新 key）。

## 4. 验证记录（2026-10-31）

- 单测：WorkflowRerunTest 4 项 + WorkflowInputsValidationTest 2 项 + WorkflowGraphValidationTest 5 项 = 11 全绿
  （单测抓出两个真 bug：祖先未成功时误标 SKIPPED、key 正则误拒驼峰——均已修复）。
- E2E（浏览器）：双候选运行对话框显示「商品关键词*」+ 必填拦截「请填写：商品关键词」；
  inspector 输入字段编辑器完整渲染声明；点「+ 商品关键词」chip 成功追加引用到聚焦参数；
  **从「文案策划」重跑**：新运行 SUCCEEDED，商品理解继承复用（近 10 分钟 media_job=0，**零生图计费**），
  文案与海报重新执行产出新海报；
  导出双候选 → 两次导入分别得到 `-import-0ab8` / `-import-3a89`（冲突自动改名）→ inputs 随包正确 → 清理。
- 前端 build + 后端 package 通过；V15 迁移核对（各模板 inputs 数量正确）。

## 5. 本批不做（后续）

- **D-2：Agent 结构化画布工具**（读图摘要/提议修改/整批校验应用/修订冲突处理）——需接 Agent 工具体系，单独一批。
- 输入默认值/枚举下拉、引用 role 化（模特/上衣/裤子）、创作项目页运行卡片的重跑入口、
  预检的「必须为上游」引用检查（当前为存在性检查）。
