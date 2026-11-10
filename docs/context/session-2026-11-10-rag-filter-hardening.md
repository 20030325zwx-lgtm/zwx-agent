# 会话摘要：知识库路线定盘 + 阶段 0 落地——RAG 过滤表达式注入面收口（2026-11-10）

## 需求与背景

项目方向收敛为「把知识库做明白」。基于对检索链路的代码级分析（修正过两处错误结论：
ILIKE 无法走 pg_trgm 的真实原因是 CASE 在 SELECT 里无法下推 + 中文二字词不足 3 字符；
过滤注入面已实际核实），制定六阶段计划并落地阶段 0。

## 改动内容

- `AgentKnowledgeRagService`：三处字符串拼接 filterExpression（searchRequest:445、
  verifyDocument:82、expandSiblings:259）统一迁移到 `FilterExpressionBuilder`
  （值恒为字面量 EQ 节点）。新增 `FILTER` 常量 + `andAll()` 助手；删除 `escape()`。
  lexicalRecall 的 SQL 本就参数化，未动。
- 新增 `AgentKnowledgeRagServiceFilterSafetyTest`：恶意 agentKey/documentId
  （含引号与 `||` 语法）必须作为字面量进入 EQ 节点、表达式树只含 AND/EQ。
- bug 记录：`docs/bugs/bug-2026-11-10-rag-filter-injection-hardening.txt`
  （定性为防御纵深，非可直接利用漏洞——admin 路径有存在性校验、internal 路径
  token 即边界、store 层转绑定参数）。

## 验证结果

- 定向 4 测试类 9/9 绿：FilterSafety 2（新）+ VersionFilter 3 + SiblingExpansion 2
  （迁移后兄弟扩展过滤串 toString 仍含 sectionPath，兼容其断言）+ LexicalTerms 2。
- `mvn -DskipTests package` 全量编译通过，jar 已重建（未重启本地服务）。

## 遗留事项（知识库六阶段计划，已与用户对齐）

- 阶段 1（下一步）：golden set 基线。30-50 题分四类（文本/表格行/图纸含无图注/精确术语），
  块级 recall@k + MRR + 各通道贡献（RagTraceCandidate 已带全部字段可直接算）；
  沿用 evaluation/eval-sets/run_baseline.py 惯例；加「期望块是否存在」入库/检索失败归因、
  负例题（库中无答案）、一条命令复跑基线；语料选择是第一步（生产库现约 0 文档）。
- 阶段 2：词法通道重做。先决策「是否愿为扩展动生产 Postgres 镜像」——pg_bigm 与现有
  2-gram 词项方案（lexicalTerms:337）零改动对齐但需自定义镜像；替代为 Java 分词写
  tsvector('simple')。零成本前置：ILIKE 挪进内层 WHERE + metadata 表达式索引
  （V42 只给 creative 表建了 trgm，knowledge 一个索引都没有，现状是全扫）。
- 阶段 3：expandSiblings 改 JdbcTemplate 按 documentId+sectionPath 查（现状用命中块
  文本当 query 再做向量检索：长节丢块 + 乱序 + 多一次 embedding），需补
  (documentId, sectionPath) 表达式索引。
- 阶段 4（可并行）：toReference:456 现在丢掉 figureImages，引用回图前端展示。
- 阶段 5/6：contextual retrieval 离线实验；视觉向量轨/PDFBox 重裁按数据决定。
- 另发现：计划缺回答侧指标（引用是否支撑答案），阶段 1 应补「答案引用 ⊇ 期望文档」布尔。

## 环境注意事项

- 改动尚未重启本地 backend；下次 `launchctl kickstart -k` 前无需再构建（jar 已是最新）。
- 生产库走 SSH 隧道（127.0.0.1:15432），阶段 2 的扩展安装决策会影响 deploy 脚本与镜像。
