# 整体方向与进度核查（2026-10-31）

## 改动内容
- 用户要求评估整个项目方向与进度。基线 HEAD 2061b7a，开始时工作区干净。
- 新增 `docs/design/2026-10-31-product-direction-progress-assessment.md`，包含全模块成熟度、最新提交进展、规划偏差、下一阶段优先级。
- 仅修改文档，没有修改业务实现或旧验收状态。

## 验证结果
- 5 个测试类共 22 项全通过、0 失败/错误/跳过：NovelRulesTest 4，OpenAiImagesMediaProviderAdapterTest 11，MediaJobServiceRulesTest 2，LocalMediaStorageTest 2，ThumbnailEncoderTest 3。
- 前端 npm run build 成功。
- 确认新小说实现、V13、IMAGE/NOVEL 开放，VIDEO/COMIC 禁用；0.1.2 桌面文件存在，但最新小说仍待下一次打包（依据会话记录）。
- 方向建议：项目保存长期内容，Agent 处理开放任务，工作流负责可复用执行；保留模块化单体，优先一个主场景交付。

## 遗留事项
- ROADMAP 与 P1 验收记录滞后，不能把旧 16/11/12/1 当作当前验收结果。
- 新小说上下文实际包含草稿概要，与确认后纳入的文档语义不符；saveFromRun 的 max+1/查重/插入缺少明显串行保护，编辑缺版本条件；需在批量连写前验证与修复。
- 工作流第一批修复已有进展，但通用崩溃恢复、输入契约、运行幂等、部分成功语义等仍需收尾。
- 创作项目 Vue 1898 行、WorkflowService 1581 行，建议抽出业务编辑器和节点/生命周期服务。

## 环境注意事项
- 未读取/修改 application-local.yml；未重启共享服务、未执行真实计费调用或数据库集成测试。
- 日志 `/tmp/aster-overall-progress-20261031-tests.log`、`/tmp/aster-overall-progress-20261031-frontend.log`。
