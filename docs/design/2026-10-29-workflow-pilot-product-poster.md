# 工作流垂直试点：原生引擎与商品海报生成

日期：2026-10-29。状态：已实现并真实运行验证（提交 7a2ab0d）。
承接：[AI 工作台修订](./2026-10-28-ai-workbench-references.md) 第 5、7、8 节的工作流推进项。

## 试点范围与决策

- **走 Aster 原生引擎，不接 n8n**：本机核心功能不依赖外部 n8n 服务（修订方案第 7 节约束）；n8n 适配器仍是后续可选项。
- **定义/运行分离的最小形态**：定义以代码注册表存在（`WorkflowService.definitions()`），运行持久化到 `workflow_run`（V5）；引入原生画布编辑器时再拆定义/版本表。
- **模型调用走模型目录**：文案与翻译步骤经 `ModelCatalogService` 解析（用户>系统>旧配置>环境变量），与任务运行同源。

## 工作流：product-poster v1「商品海报生成」

| 步骤 | 实现 | 降级行为 |
| --- | --- | --- |
| 搜集商品图 | image-search MCP `searchImage` → Commons 回退（无需密钥）→ LLM 译英重搜 | 分类报错 |
| 商品理解 | 图片 MCP `describe_image`（经 `ImageAnalysisService`） | SKIPPED，按关键词直接生成文案 |
| 文案规划 | 模型目录 ChatModel → 严格 JSON（标题/副标题/卖点/配色） | 默认文案兜底 |
| 海报合成 | `PosterComposer` Java2D 纯本地（1200×1600，渐变底+商品图面板+文案+页脚） | 无 |

产物经 OSS 登记（`workflow/{userId}/{runId}/poster.png`），读取时按需签名（2 小时）并做归属校验。

## 已知问题与踩坑记录

1. **Zonky/构建外（本次新坑）**：
   - `List.toList()` 不可变——引擎就地更新步骤状态必须 `new ArrayList<>()` 包装，否则 clear() 抛 UnsupportedOperationException 且运行永久卡 RUNNING。
   - Jackson 默认把 Instant 写成科学计数法秒数——`findAndRegisterModules()` 后必须再 `disable(WRITE_DATES_AS_TIMESTAMPS)`，读取侧也要兼容数字格式。
   - Wikimedia imageinfo 的 URL 带 `?utm_source=...` 查询串——按扩展名判断图片必须先剥查询串。
   - 本机代理/VPN 环境下外网域名可能解析为保留段 IP（如 28.0.0.12）——对检索来源的可信媒体 CDN（wikimedia/pexels 等 https）放行下载，其余维持公网 SSRF 校验。
2. **MCP 容器无 Pexels 密钥**（只有占位符），搜索 401——Commons 回退是当前实际生效路径；配置密钥后 MCP 路径自动优先。
3. **商品理解在 MCP 容器内访问外网图片失败**（容器无代理），当前固定降级为 SKIPPED——文案仅靠关键词也能生成；要让理解步骤生效需给容器配代理或换成后端直连视觉模型。
4. 旧 URL 深链：运行详情面板曾出现旧 run 的陈旧状态——前端轮询已带静默刷新。

## 界面

导航新增「工作流」：定义卡（步骤预览）+ 关键词发起 + 运行列表（2.5s 自动刷新至无 RUNNING）+ 运行详情（步骤时间线、失败原因、海报预览）。全部 `--wb-*` 令牌。

## 后续方向

1. 定义/版本拆表 + 步骤参数化（多工作流、每步可选实现）。
2. 产物扩展：多产物（原图+海报）、产物落工作区文件（桌面本地存储适配）。
3. 工作流 ↔ 任务互通（「整理为工作流草稿」路径）。
4. 画布编辑器（Vue Flow）承载定义编辑，运行模型不变。
