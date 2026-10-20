# 当前项目内的文档预处理与切片策略

> 状态：已实施（2026-10-20）。实现：`DoclingDocumentPreprocessor`、`DoclingServeChunkingService`。
> 背景：Docling Serve 的 `/v1/chunk/hybrid/file` 默认只遍历 body，PDF 顶部被识别为 `page_header/furniture` 的手机号、邮箱不会进入 chunks。预处理暂时放在当前 Java 项目中，后续再独立为服务。

## 1. 阶段划分

```text
原始文件
  → Docling Serve /v1/chunk/hybrid/file（include_converted_doc=true）
  → 当前项目 DocumentPreprocessor
       - 从 json_content.texts 提取 furniture/page_header/page_footer
       - 清洗页码、页脚噪音，去重
       - 识别文档类型（resume / general）
  → 当前项目 ChunkingPolicy
       - general：正文 chunks + “文档头部信息” chunk
       - resume：正文 chunks + “基本信息” chunk + resumeSection 分类
  → embedding / pgvector
```

Docling 继续负责解析、版面、表格和正文 chunks；当前项目只补“Docling chunk 接口没有暴露的 furniture 信息”和业务切片策略。

## 2. 预处理输出

```java
PreprocessedDocument(
  documentType,      // resume | general
  furnitureText      // 清洗后的页眉/页脚文本列表
)
```

提取规则：

- 从 `documents[0].content.json_content.texts` 读取。
- 只保留 `content_layer=furniture` 或 `label=page_header/page_footer` 的条目。
- 去掉空行、短页码（≤3 位纯数字）、`第 X 页`、`Page X`、重复行。
- 保留手机号、邮箱、姓名、公司抬头等可能用于检索的信息。
- 最多保留 10 行 / 1000 字符，避免污染。
- 如果某行已经出现在 Docling 正文 chunks 中，则不重复加入。

## 3. 切片策略

### general

- 保留 Docling 返回的正文 chunks。
- 如果存在 furniture 文本，额外生成一个 chunk：
  ```text
  sectionPath = 文档头部信息
  chunking = docling-furniture
  ```
- 该 chunk 不替代正文，只保证联系方式/页眉信息可检索。

### resume

简历识别：文件名包含 `简历`、`resume`、`cv`、`curriculum`。

- furniture chunk 的 `sectionPath` 改为 `基本信息`。
- 为正文 chunks 增加 `resumeSection` metadata：
  - `基本信息`：个人信息/联系方式/求职意向
  - `工作经历`：工作经历/工作经验/任职
  - `项目经历`：项目经历/项目经验
  - `教育经历`：教育/学校/学历
  - `专业技能`：技能/技术栈
- 不强行重切 Docling 已生成的正文 chunks，先通过 metadata 改善检索过滤和排序；后续再评估是否需要简历专用 chunker。

## 4. 配置

```yaml
app:
  document-parser:
    docling:
      include-converted-doc: true
      # 关闭后退化为只用 Docling chunks，不补 furniture
      furniture-chunk-enabled: true
```

- `include-converted-doc=true` 会增加响应体大小，但只有知识入库的异步路径使用。
- `furniture-chunk-enabled=false` 时行为与接入 Docling 前一致。

## 5. 数据结构

Docling chunk response 增加：

```java
DoclingChunkResponse(chunks, processingTime, documents)
DoclingDocumentResult(content)   // content.json_content 为 Map
```

预处理模块：

- `DoclingDocumentPreprocessor`
- `PreprocessedDocument`

切片策略：

- `DoclingServeChunkingService` 内组合 general/resume 逻辑。

## 6. 失败与降级

- Docling 未返回 `json_content`：只使用正文 chunks，不生成 furniture chunk。
- furniture 清洗后为空：跳过。
- 预处理异常：记录 warning，不影响正文入库。
- Docling 整体失败：沿用现有 `fallback-to-local` 逻辑。

## 7. 验证

- 单元测试：
  - `DoclingDocumentPreprocessorTest` 覆盖 furniture 提取、页码过滤、正文重复过滤、resume 识别；
  - `DoclingServeChunkingServiceTest` 覆盖“基本信息”chunk 和 `resumeSection`。
- 真实简历 PDF `朱文轩-Java后端开发工程师-简历.pdf` 重新索引：
  - 索引前 9 chunks，手机号/邮箱不在 chunks 中；
  - 索引后 10 chunks；
  - 第一个 chunk 为 `[基本信息] 17768576135 1796866501@qq.com 朱文轩 Java 后端开发工程师`；
  - `phone_found=true`、`email_found=true`。
- 真实问答验证：
  - 问“朱文轩的手机号是什么”返回手机号；
  - RAG trace 引用文件名为 `朱文轩-Java后端开发工程师-简历.pdf`；
  - 不再出现 `[null#1]`。
- furniture chunk 继承原文档 base metadata（含 filename），因此引用卡片可正常显示和预览；前端对历史 `[null#1]` 也做了兜底匹配。
- 合成 PDF：顶部联系方式同样被保留。
- `mvn -o -DskipTests package` 成功，后端重启后 `/api/health` 返回 `ok`。

## 8. 已知边界

- 当前仍使用 Docling 的正文 chunk 结果，没有完全重写 chunker。
- `include_converted_doc=true` 会让大文档响应变大；后续可改为先 convert 落中间产物，再独立 chunk。
- 简历 section 目前通过 metadata 分类，不改变切分边界；简历专用切分需后续评测。
- 该预处理逻辑是临时方案，后续应迁到独立文档预处理/切片服务。
