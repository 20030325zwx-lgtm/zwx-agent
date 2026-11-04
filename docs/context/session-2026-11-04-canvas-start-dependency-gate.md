# 默认开始节点与全部上级完成约束（2026-11-04）

## 改动
- 基于已提交推送的 6de8426 继续修改；本轮未提交、未推送。
- 默认唯一 start 节点，无模型/提示词配置，不调用模型、不产生素材，无输入端口且不可删除。旧草稿 ensureStart 一次性补节点并连接既有根节点；后续新增游离节点必须连接流程才能运行。
- 新增蓝色 flow-out/flow-in，控制执行依赖而不传递内容；文字与图片边仍然同时构成执行依赖。
- 输入/素材节点以无副作用 canvas_input 步骤保留在运行图中，保证经过它们的依赖不被丢失。start 与输入步骤不计入 executableNodes 或费用提示数量。
- 整图运行要求待执行生成节点可从 start 到达。node/from_here 必须先验证范围外所有生成祖先已成功完成；从数据库查询最新实际媒体执行，不信任客户端 status、selectedAssetId，也不会自动重跑范围外祖先。
- WorkflowService 对 canvasRunId 运行显式要求全部直接父节点 SUCCEEDED，等待中不放行，父节点失败/取消/跳过则跳过子节点；继续复用提交后调度和现有后台媒体轮询。
- 前端同步显示阻止原因，父节点成功状态刷新后解除限制。仍使用拓扑顺序串行执行，未扩展为并行调度。

## 验证
- CreativeCanvasServiceTest 11、WorkflowCanvasDependencyTest 1、WorkflowReferenceResolutionTest 2、WorkflowGraphValidationTest 5、WorkflowInputsValidationTest 2、WorkflowRerunTest 4：共 25 项通过。
- node --test frontend/tests/canvasGraph.test.mjs：6 项通过，覆盖多父汇合、from_here 外部分支、不可达、start 升级幂等与非法状态。
- frontend/tests/canvas-browser-smoke.cjs：隔离 Chrome + 全 API mock，通过蓝色/紫色/绿色拖线、start 唯一和不可删除、运行中阻止下游、成功后放行、保存恢复、拖动、断开撤销和两种视口布局。
- npm run build 通过（本次公共资源复制耗时约 4 分钟，既有大 chunk/stream 警告）；mvn -DskipTests package 通过。
- 前后端 launchctl 已重启；后端 /api/health 返回 200 / ok，前端返回 200，git diff --check 通过。
- 未使用真实模型 API；未读取、修改或重建 application-local.yml。

## 环境与边界
- 工作目录 /Users/zhuwenxuan/Desktop/agent/yu-ai-agent，分支 master。
- 前端 http://127.0.0.1:3000/；后端 http://127.0.0.1:8123/api。
- 浏览器截图 /tmp/aster-canvas-verification/；运行需 PLAYWRIGHT_CHANNEL=chrome 与 bundled Playwright NODE_PATH。
- 参考视频模型输入、并行分支、输入修改后的历史结果过期标记均不在本轮范围；真实供应商整链未做付费验收。
