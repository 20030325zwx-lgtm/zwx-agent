# 接口与页面加载耗时优化：现状评估与方案（2026-11-05）

状态：评估 + 首轮优化已落地（提交 de66f83，随 0.7.10 发布）。其余条目按优先级排期，均为拟议方案。

## 1. 结论摘要

首屏最大的耗时来源不是接口，而是 **file-viewer 渲染器被静态注入入口**：登录页被迫下载 PDF/Word/OFD/韩文/iWork 等全部渲染器（2.1MB JS、14 个分块）。改为按需加载后：

| 指标（公网 /login，强缓存冷启动） | 优化前 | 优化后 | 变化 |
| --- | --- | --- | --- |
| TTFB | 48ms | 41ms | — |
| DOMContentLoaded | 3214ms | **133ms** | **24×** |
| Load | 3241ms | 148ms | 22× |
| 请求数 | 23 | 10 | -57% |
| 传输体积 | 2142KB（JS 2042KB） | **248KB（JS 146KB）** | **8.6×** |
| file-viewer 分块请求 | 14（833–1975ms） | **0** | — |

后端 API 本身在同机 Docker 网络下表现健康（实测：项目列表 45ms、会话列表 20ms、模型目录冷 353ms / 热 74ms）。真正的优化空间集中在：**不必要的首屏资源、页面级串行请求、N+1 查询、重复的目录请求、轮询而非推送、逐事件写库**。

## 2. 已实施（P0，de66f83）

1. `frontend/vite.config.js`：`fileViewerRenderers({ inject: false, chunkStrategy: 'none' })`
   - 插件不再向 `index.html` 注入静态渲染器注册模块；
   - 关闭 renderer 分块策略，避免共享依赖被归入渲染器 chunk 而被入口静态引用（此前入口 `import "./file-viewer-ofd…"`、`import "./file-viewer-word…"` 是根因）。
2. `frontend/src/components/KnowledgeDocumentViewer.vue`：首次打开文档预览时 `import('virtual:file-viewer-renderers')`，与文件下载 `Promise.all` 并行；渲染器只在真正预览文档时加载。
3. 验证：`dist/index.html` 的 modulepreload 从 14 个降为 0 个；入口 chunk 不再出现 file-viewer 静态导入；公网实测见上表。

## 3. 待优化清单（按性价比排序）

### P1-1 前端页面加载并行化（低成本，预计 -100~300ms/页）

- `frontend/src/views/creative/project/ProjectCanvasTab.vue` 的 `loadCanvas` 目前串行：`getCreativeCanvas` → `Promise.all(loadAssets, loadModels)` → 逐个 `await loadAssetPreview` → `listCreativeCanvasRuns`。
- 建议：画布草稿、运行列表、素材、模型目录四路并行；素材预览改为 `Promise.all` + 并发上限（如 6）。
- 其他页面同样检查「先取列表再逐个取详情」的串行模式。

### P1-2 模型目录请求去重与缓存（低成本，预计 -50~350ms/次页面切换）

- `listModelCatalog()` 在 `SuperAgent.vue`、`ModelCatalogSettings.vue`、`ProjectCanvasTab.vue` 等处分页重复调用，冷启动约 350ms、热 74ms，且无共享缓存。
- 建议：前端加 store 级缓存（TTL 60s，保存版本号）+ 后端为 `GET /settings/catalog` 加 `ETag` 与 `Cache-Control: private, max-age=60`，命中返回 304（几 KB）。

### P1-3 画布运行列表 N+1（中等成本，随运行数增长）

- `CreativeCanvasService.listRuns`（`CreativeCanvasService.java:163`）对每个 run 调 `getRun`，每次又查 canvas_run 行、`workflows.view`、candidates —— 10 条历史约 30~40 次查询。
- 建议：一次查询取 run + workflow_run 状态 + 步骤摘要，candidates 仅对「最近一个未终态 run」查询；assignments 同理只在详情接口返回。

### P1-4 轮询改为事件推送（中等成本，长任务感知收益大）

- 画布运行每 3s 轮询（`schedulePoll`）、媒体任务按 jobId 轮询；生成类任务动辄数十秒，轮询带来持续请求与 3s 粒度延迟。
- 建议：复用现有 SSE 通道，增加画布 run 事件流（run/step/tool/selection 事件），前端改为订阅 + 断线重连；轮询仅作兜底（降频至 15s）。

### P2-1 逐事件写库 → 批量/异步（后端）

- `AgentExecutionTraceService.record` 每次事件执行 `UPDATE agent_run … RETURNING` + `INSERT`（2 次往返），run 生命周期内可达数十次；`ManusGraphOrchestrator` 每 750ms 更新一次 partial_answer。
- 建议：事件批量写入（按 run 内存缓冲，100–250ms 或 N 条刷盘），partial_answer 降频至 1.5–2s；保证「先持久化后通知」语义不变。

### P2-2 数据库与线程模型

- `application.yml` 未显式配置 Hikari（默认 pool 10）与 Tomcat 线程；当前单机 4 核 / 8G，LLM 流式 + DB 混合负载。
- 建议：先接入指标（actuator 已引入，compose 已有 Prometheus），按 p95 连接等待时间再调池；评估 `spring.threads.virtual.enabled=true`（注意 ThreadLocal 工具链已在使用，虚拟线程兼容，但需压测验证）。
- 热点查询索引核对：`agent_run`（会话/状态/phase/租约）、`workflow_run`（project/created_at）、`creative_canvas_run`（project/created_at）、`media_job`（project/status）；缺的补复合索引。

### P2-3 模型侧时延（真正的「慢接口」在生成链）

- 聊天/生成耗时以模型为准：可优化的是 prompt 体积（历史 21 条 + 知识上下文）、工具调用并发、以及首字流式（已启用）。
- 建议：历史消息按 token 预算动态裁剪；知识检索先按相关度截断；对支持 prompt caching 的供应商启用缓存；同一轮内无依赖的工具调用并行执行。

### P3（暂不做）

- CDN、独立缓存层（Redis）、读写分离、微服务拆分：当前单机规模收益/复杂度不匹配，留待真实流量增长后再评估。

## 4. 观测建议

- 后端：启用 `/actuator/prometheus`（独立管理端口 8124）并用 compose 内 Prometheus + Grafana 采集；按 endpoint p50/p95、Hikari 等待、DB 查询次数建看板。
- 前端：接入 web-vitals（LCP/INP/CLS）上报到数据中台，持续验证首屏与交互时延。

## 5. 验证方式

- 前端：Playwright 采集 `performance` 资源瀑布（本次评估方法），对比请求数/传输体积/DCL。
- 后端：定向接口压测（JMeter/wrk 小规模），观察 p95 与连接池等待；改动前后同机对比。
- 每项改动单独提交，先在本地验证再随发布上线。
