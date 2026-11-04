# 会话摘要：创作台画布化整改实施（2026-11-04）

已被 `session-2026-11-04-creative-canvas-interaction-fix.md` 取代。本文是前次实现状态，不能将匿名登录页检查视为画布交互验收；分支隔离等能力未做完整 E2E 验证。

## 改动内容

- 图片/视频项目默认打开项目创作画布；首页交接提示词、模型、技能标识和首帧/参考素材，旧 studio 路由重定向到画布。
- 新增 Vue Flow 项目画布和节点卡片，支持素材、文本、图片生成、图片编辑、视频生成节点；检查器统一编辑模型、提示词优化、素材和参数。
- Flyway V31 新增项目级 creative_canvas 草稿与视口，使用 expectedRevision 乐观锁；服务端限制图大小、节点/边、类型、连线目标、视频输出和循环依赖。
- Flyway V32 新增 creative_canvas_run，画布按 node / from_here / all 冻结到 workflow_run.graph_snapshot，并复用 WorkflowService、MediaJobService 和 WorkflowMediaResumeScheduler。
- 多产物媒体节点进入 WAITING_SELECTION；用户选择本次运行候选 assetId 后恢复后继。支持刷新恢复、分支失败隔离、画布运行幂等和取消。
- 工作流参数解析增加 List 递归，媒体节点接受画布指定 modelRowId；生成前等待草稿保存完成，修复播放 URL 重复拼接和节点 props 读取错误。
- 全局工作流入口降级为“更多”下的“自动化”，旧 URL、定义、运行历史和高级编辑器保留。

## 验证结果

- CreativeCanvasServiceTest、WorkflowInputsValidationTest、WorkflowRerunTest、WorkflowGraphValidationTest、MediaJobServiceRulesTest、MediaJobOperationMatrixTest：通过。
- 前端 npm run build：通过；仍有既有 stream browser externalization 与大 chunk 警告。
- mvn -DskipTests package：通过，JAR 已更新。
- git diff --check：通过；application-local.yml 未修改。
- 后端重启成功，Flyway 从 V30 顺序执行 V31/V32，schema 当前为 V32；健康接口返回 ok。
- 前端重启成功，根页面 HTTP 200；匿名浏览器按预期跳转登录页，控制台无 error/warn。
- 未调用真实供应商生成 API，避免产生费用。

## 遗留事项

- 用户登录后需人工验收拖拽、连线、node/from_here/all、刷新恢复、候选选片和取消；真实供应商只在明确预算下单次验收。
- 短剧剧情/分镜结构节点化、通用工作流模板复制到项目画布属于后续产品扩展，不影响本次图片/视频画布闭环。
- Maven 全量测试的 SpringBootTest 在本机 classpath 扫描阶段长期无输出，已停止；上述相关测试集和正式 package 均通过。

## 环境注意事项

- 后端：http://127.0.0.1:8123/api；前端：http://127.0.0.1:3000/。
- launchctl 服务 com.zwx.yu-ai-agent.backend / frontend 已重启并持续运行。
- 未读取、覆盖或修改 src/main/resources/application-local.yml 的任何密钥配置。
