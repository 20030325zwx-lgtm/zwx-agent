# 创作画布交互与布局修复（2026-11-04）

## 用户反馈与范围
用户指出上一轮实现画布偏右、不能连线、下游读不到内容且 UI 杂乱。本轮针对这些问题修复，不宣称原设计方案所有 P1/P2/P3 项目均已完成。

## 改动内容
- 项目导航改顶部横栏，画布铺满中央主内容区，276px 左侧节点编辑器可收起，底部统一添加节点工具栏；窄屏适应画布时收起编辑器。
- 项目画布直接导入 Vue Flow CSS；节点单根布局，修复点击选中和端口位置。
- 紫色 text-out/text-in 传递节点有效提示词，绿色 media-out/image-in 传递图片素材。支持旧无端口连线归一化，循环/重复/错类型连接提示、断开与撤销。
- 下游显示已连接来源、文本或素材、完整有效提示词；节点卡片同步显示递归上游内容。
- CreativeCanvasService 按端口拆分文本与素材输入；单节点运行的文本来源无需先生成图片；运行图依赖去重。
- WorkflowService 支持 UUID 引用键与安全字符串替换；恢复运行复用 submitAfterCommit，防止读取未提交上游结果。
- 上传目标在异步前捕获；生成节点仅接受图片参考，素材节点可保存视频；未实现参考视频生成。

## 验证结果
- 前端 node --test tests/canvasGraph.test.mjs：3 项通过。
- Java：CreativeCanvasServiceTest、WorkflowReferenceResolutionTest、WorkflowGraphValidationTest、WorkflowInputsValidationTest，共 16 项通过。
- frontend/tests/canvas-browser-smoke.cjs：隔离 Chrome context，伪测试身份和全 API mock，无用户数据写入、无付费生成。真实鼠标验证文本/图片端口连线、递归提示词预览、上游独立提示词运行、循环拒绝、保存刷新、断开撤销、节点拖动持久化。
- 1440×960、1024×768 布局截图目视检查。截图在 /tmp/aster-canvas-verification/。
- npm run build 与 mvn -DskipTests package 均通过，既有大 chunk/stream 警告保留。
- 前后端 launchctl 已重启；后端启动日志确认 Started ZwxAgentApplication，/api/health 返回 HTTP 200 / ok；前端 HTTP 200。git diff --check 通过。

## 遗留与边界
- 真实供应商生成和跨进程任务恢复未在本轮做付费 E2E；不得将 mock 浏览器测试描述成真实供应商整链验收。
- 当前画布文本输出表示提示词，不是视频内容识别或图像描述生成。参考视频模型输入尚不支持，界面明确说明。
- 原方案的模板复制、复杂分支隔离、完整运行历史、多镜拆分等仍需单独实现/验收。
- 保留已有脏工作区，不覆盖用户修改，未提交 Git。

## 环境
- 仓库 /Users/zhuwenxuan/Desktop/agent/yu-ai-agent。
- 前端 http://127.0.0.1:3000/；后端 http://127.0.0.1:8123/api。
- launchctl：com.zwx.yu-ai-agent.frontend / backend。后端修改必须先 package 再 restart。
- application-local.yml 未读取、修改或重建。
- 浏览器测试：PLAYWRIGHT_CHANNEL=chrome，NODE_PATH 指向 bundled Node 的 node_modules；不使用真实登录凭据。
