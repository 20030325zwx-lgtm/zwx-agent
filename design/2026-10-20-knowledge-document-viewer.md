# 知识库原始文档预览（File Viewer）

## 目标

知识库管理页不再只展示解析后的纯文本，而是直接渲染原始 PDF、Word、Excel、PPT 和 Markdown/TXT。预览在浏览器内完成，不增加服务端转换链路，也不公开 MinIO/OSS 地址。

## 数据流

```text
知识库选择文档
  -> GET /api/ai/agent-knowledge/documents/{documentId}/content?agentKey=...
  -> 后端校验 tenant/agent 归属并从对象存储读取原始字节
  -> 前端构造 File
  -> @file-viewer/vue3 在浏览器内懒加载对应渲染器
```

## 前端集成

- 依赖：
  - `@file-viewer/vue3@3.2.2`
  - `@file-viewer/preset-office@3.2.2`：PDF、Word、Excel、PPT、OFD
  - `@file-viewer/preset-lite@3.2.2`：Markdown、TXT、代码、图片、音视频
  - `@file-viewer/vite-plugin@3.2.2`
- `KnowledgeDocumentViewer.vue` 负责：
  - 通过带 JWT 的 `axios` 请求读取 Blob；
  - 创建 `File` 并交给 `<FileViewer>`；
  - 加载中、失败重试、空状态；
  - 请求序号防止快速切换文档时旧响应覆盖新文档。
- 选项：
  - `preset: [officePreset, litePreset]`
  - `rendererMode: 'replace'`
  - `theme: 'light'`、`locale: 'zh-CN'`
  - Shadow DOM 样式隔离、紧凑工具栏、搜索、PDF 页工具栏
- 运行时 Worker/WASM/字体由 Vite 插件在 dev/build 时复制到 `public/vendor`，属于生成产物，已在 `.gitignore` 中排除。

## 后端接口

`GET /api/ai/agent-knowledge/documents/{documentId}/content?agentKey=...`

- 仅管理员可访问；
- 校验文档 `tenant_id`、`agent_key` 与当前请求一致；
- 按扩展名返回正确 `Content-Type`；
- 返回原始字节，支持浏览器内 `File` 构造。

## 影响

- Vite 从 4.5 升级到 5.4，以满足 `@file-viewer/vite-plugin` 的 peer 依赖。
- 预览包按格式懒加载；首次打开 Office/PDF 会加载对应渲染器，构建产物明显增大，但不会阻塞其他页面。
- 仍保留 AI 解析后的切片列表和原文解析结果用于索引排障，两者用途不同。

## 后续

- 如需在 Electron/`file://` 下使用，需要验证 `base: './'` 时 Worker/WASM 资产路径。
- 如需隐藏下载/打印，可通过 `toolbar` 与 `beforeOperation` 收紧权限。
