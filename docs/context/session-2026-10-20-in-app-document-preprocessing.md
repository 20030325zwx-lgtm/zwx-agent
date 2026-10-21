# 会话摘要：当前项目内文档预处理与简历切片优化（2026-10-20）

## 改动内容

- 设计文档：`docs/design/2026-10-20-in-app-document-preprocessing.md`。
- Docling chunk 请求增加 `include_converted_doc=true`，响应解析 `documents[].content.json_content`。
- 新增：
  - `DoclingDocumentResult`
  - `PreprocessedDocument`
  - `DoclingDocumentPreprocessor`
- `DoclingServeChunkingService`：
  - 提取 `content_layer=furniture` 或 `label=page_header/page_footer` 的文本；
  - 过滤短页码、`第 X 页`、`Page X`、正文重复内容；
  - 普通文档生成 `文档头部信息` chunk；
  - 简历生成 `基本信息` chunk；
  - 简历正文 chunks 增加 `resumeSection`（基本信息/工作经历/项目经历/教育经历/专业技能/其他）。
- 配置新增：
  - `app.document-parser.docling.include-converted-doc`
  - `furniture-chunk-enabled`
  - `furniture-max-lines`
  - `furniture-max-chars`
- 单元测试：`DoclingDocumentPreprocessorTest`、`DoclingServeChunkingServiceTest`。

## 验证

- 单元测试通过。
- 真实简历重新索引：
  - 索引前 9 chunks，手机号/邮箱缺失；
  - 索引后 10 chunks；
  - 第一个 chunk：`[基本信息] 17768576135 1796866501@qq.com 朱文轩 Java 后端开发工程师`；
  - 手机号、邮箱均可在 chunks 中检索到。
- 合成 PDF 也保留了顶部联系方式。
- `mvn -o -DskipTests package` 成功；后端重启后 `/api/health` 返回 `ok`。
- Docling 容器继续运行，模型缓存约 507MB。

## 遗留事项

- 简历 section 目前只做 metadata 分类，没有重新切分正文；简历专用 chunker 后续再评估。
- `include_converted_doc=true` 会增加响应体大小；大文档后续可改为先 convert 落中间产物。
- 预处理仍在当前 Java 项目内，属于临时方案，后续应迁到独立预处理/切片服务。
- 本轮改动尚未提交。

## 追加：引用文件名为 null 的修复

- 家具 chunk 创建时改为继承 `baseMetadata`，保留 `filename/tenantId/agentKey`，避免模型看到 `[null#1]`。
- `toScopedChunk` 增加 `filename` 兜底。
- 前端 `citedChunks` 对 `[null#index]` 做兼容匹配，并回退显示“未知文档”。
- 重新索引简历后，手机号问答的引用为 `朱文轩-Java后端开发工程师-简历.pdf#1`，切片预览接口返回“基本信息”内容。
