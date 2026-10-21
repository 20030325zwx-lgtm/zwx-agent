# 会话摘要：问答中断、返回主页与知识库 UI（2026-10-20）

## 改动内容

### 生成中断

- 后端新增超级智能体主动取消：
  - `RunStream.cancel()` 立即执行停止回调，不等待 30 秒断线宽限期。
  - `ManusGraphOrchestrator.cancel(runId)`。
  - `POST /api/ai/manus/conversations/{conversationId}/runs/{runId}/cancel`，按租户/用户/会话校验 run 归属。
  - 处理了“任务尚未开始就被取消”的资源释放：释放 MCP/会话租约并安排 RunStream 移除。
- 前端 `ChatRoom` 在 `connectionStatus=connecting` 时用停止按钮替换发送按钮，并禁止 Enter 重复发送；新增 `stop-generation` 事件。
- 四个问答页面接入停止：
  - 超级智能体：关闭 SSE + 调用 cancel 接口 + 重载历史，显示已保留的部分回答。
  - 情感分析、旅游规划、功能测试：关闭 SSE 触发服务端取消/持久化，再重载历史。
- 新增 `RunStreamTest.cancelRunsStopTaskImmediatelyAndOnlyOnceAfterCompletion`。

### 返回主页

- 新增 `components/HomeButton.vue`：带房屋图标和“返回主页”文字的胶囊按钮。
- 替换/补充到超级智能体、情感分析大师、旅游规划专家、功能测试助手和知识库管理页。

### 知识库 UI

- 知识库管理页重构：
  - 顶部概览卡：文档总数、生效中、待发布、向量切片。
  - 文档列表增加文件类型图标、状态圆点、版本/生命周期标签。
  - 增加搜索、生命周期筛选（全部/生效中/待发布/已归档）和刷新按钮。
  - 切片列表与原文预览增加计数、版本/状态徽章、对象路径展示和新的空状态。
  - 上传支持扩展为 md/txt/pdf/doc/docx/xls/xlsx/ppt/pptx。
  - 刷新列表时尽量保留当前选中文档。

### 知识库分类（追加）

- 新增 V14：`agent_knowledge_category` 表；`agent_knowledge_document.category_id` 非空外键。
- 迁移为已有文档的租户/智能体创建“默认分类”，并把历史文档全部转入；当前本地库 38 篇文档、0 篇无分类。
- 新增接口：
  - `GET /api/ai/agent-knowledge/categories?agentKey=...`
  - `POST /api/ai/agent-knowledge/categories`（`{agentKey, name}`）
  - `POST /api/ai/agent-knowledge/documents/{documentId}/category?agentKey=...&categoryId=...`
  - 上传接口新增可选 `categoryId`，缺省进入默认分类。
- 前端知识库页新增分类筛选、分类计数、新建分类、上传目标分类，以及预览区“移动到分类”下拉。

### 长文档解析与重新解析（追加）

- 真实扫描件暴露两个问题：
  - Docling 同步接口 120 秒上限导致 504，回退本地后只有页码占位，产生 0 切片却仍被标记 `READY/ACTIVE`；
  - 大文档生成 108 chunks 时，`vectorStore.add` 按 100 条分批，超过 DashScope embedding 的 25 条输入上限，连续重试后索引失败。
- 修复：
  - `DOCLING_SERVE_MAX_SYNC_WAIT=600`；后端 Docling 读取超时默认 660 秒。
  - 索引结果为 0 切片时抛出异常并标记失败，不再发布空版本。
  - embedding 写入批次从 100 调整为 25。
  - 知识库预览区新增“重新解析”按钮，调用已有 reindex 接口；失败且 0 切片的文档显示“解析失败”。
- 三篇 0 切片文档已通过修复后的索引链路重新解析并验证：
  - 南京先维员工手册：86 chunks，`READY/ACTIVE`；
  - 生产安全事故调查工作规范：263 chunks，`READY/ACTIVE`；
  - 铝工业职业病危害预防控制规范：109 chunks，`READY/ACTIVE`。
- 本地库 `chunk_count = 0` 文档数已为 0；知识库页仍保留“重新解析”按钮供后续使用。

### 原始文档预览组件（追加）

- 集成 `@file-viewer/vue3@3.2.2` + `@file-viewer/preset-office` + `@file-viewer/preset-lite`，替换知识库右侧纯文本 `<pre>` 预览。
- 新增 `KnowledgeDocumentViewer.vue`：通过带 JWT 的后端 content 接口获取原始字节，构造浏览器 `File` 后交给 File Viewer 解析，避免公开 OSS/MinIO 地址或处理 CORS。
- 新增后端接口 `GET /api/ai/agent-knowledge/documents/{documentId}/content?agentKey=...`，返回原始文件和正确 Content-Type；服务层校验文档归属当前租户/智能体。
- 前端 Vite 从 4.5 升级到 5.4，接入 `@file-viewer/vite-plugin` 的 `fileViewerRenderers({ copyAssets: true })`；dev/build 自动复制 18 类 Worker/WASM/字体资产到 `public/vendor`。
- `public/vendor/` 与 `public/flyfish-viewer-assets.json` 已加入 `.gitignore`，属于生成产物。

### 目录切片后处理（追加）

- 设计文档：`docs/design/2026-10-20-toc-chunk-postprocessing.md`。
- 根因有两层：
  - Docling HybridChunker 默认 tokenizer 上限 256，目录省略号被计为大量 token，导致每 3 行左右切一块并重复“目 次”标题；
  - Docling 把目录误判为表格，triplet 文本会丢失/错序单元格，例如安全标准的“前言”条目在 chunk 文本中消失。
- 实现：`DoclingServeChunkingService` 检测连续 TOC 切片并合并：
  - 优先从 `json_content.tables[].data.table_cells` 按阅读顺序重建条目，再补充 TOC 切片引用的 `#/texts/*`（如“参考文献”）；
  - 表格重建失败时回退到去省略号、去重复标题的 chunk 文本；
  - 合并 `pageNumbers`；
  - metadata 增加 `chunking=docling-toc`、`contentLayer=toc`、`originalChunkCount`、`tocSource`。
- 新配置：`app.document-parser.docling.toc-handling=merge|drop|keep`，默认 `merge`。
- 单元测试新增 TOC 合并、清洗与 `drop` 模式覆盖。
- 三篇真实文档已重新解析：
  - 生产安全 263 → 243 切片（21 个 TOC 碎片合并为 1）；
  - 铝工业 109 → 106（4 → 1）；
  - 员工手册 86 → 84（3 → 1）。

## 验证

- 后端 `mvn -q -DskipTests package` 成功，launchctl 重启后 `/api/health` 返回 `ok`。
- 前端 `npm run build` 成功。
- 定向测试通过：`RunStreamTest`、`ManusGraphOrchestratorTest`、`ManusGraphOrchestratorInterruptionTest`、`AgentKnowledgeDocumentServiceUploadTest`。
- Flyway V14 已在本地 PostgreSQL 成功应用。
- Docling 容器已用新配置重建，`DOCLING_SERVE_MAX_SYNC_WAIT=600`，继续复用 `docling-cache` 模型缓存卷。
- 三篇长文档真实重新解析耗时约 85–110 秒，均在 600 秒窗口内完成；embedding 按 25 条分批后未再出现 `input texts limit 25`。
- 数据库核对：`agent_knowledge_document` 38 条，`category_id IS NULL` 0 条；love/super 已生成默认分类。
- `git diff --check` 通过。
- 后端、前端开发服务分别返回健康状态和 HTTP 200。
- File Viewer 前端构建成功，插件复制 18/18 渲染器资产；dev 服务返回 `/flyfish-viewer-assets.json` 和 `/vendor/pdf/pdf.worker.mjs` 均为 200。
- 后端 content 接口随 `mvn -DskipTests package` 编译通过，服务重启后 `/api/health` 正常。
- `DoclingServeChunkingServiceTest` 通过；三篇真实文档重新解析后均为 `READY/ACTIVE`，铝工业目录切片为单个 `[目录]` 且 metadata 为 `contentLayer=toc`。
- 安全标准目录已从 `table_cells` 重建为完整顺序条目（前言、1–7、附录 A–U、参考文献 40），不再出现 `| - |`、`III =` 等 triplet 串行噪声。

## 遗留事项

- 未执行真实模型问答的停止按钮端到端点击验证；取消接口和状态持久化按代码路径与单测验证。
- 旅游/测试/情感仍通过关闭 SSE 触发服务端取消，没有像超级智能体一样的显式 cancel 接口；如后续需要跨实例可靠取消，应补统一取消端点。
- 主动停止后若在模型调用刚开始的极短时间内操作，部分回答可能为空。
- 分类暂未提供重命名/删除；默认分类不可删除。分类只影响管理端组织，不改变检索过滤条件。
- 本轮改动尚未提交。

## 环境注意事项

- `src/main/resources/application-local.yml` 未读取、编辑、复制或加入 Git。
- 后端由 launchctl 管理；前端 Vite 开发服务运行在 `127.0.0.1:3000`。
- 访问 `/api/v3/api-docs` 会触发既有 springdoc `ControllerAdviceBean` 兼容性异常，与本轮改动无关。
