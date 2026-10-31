# 联合评审回应与第一批修复记录（2026-10-31）

> 回应 `docs/design/2026-10-31-workflow-creative-expert-review.md`。按专家建议的第一批（可靠性）处理，
> R06/R03 完整版/§3 架构拆分/§5 产品流/§6 UI 细化列入后续批次（见文末）。

## 已处理（本轮实现 + 验证证据）

### R02 / P1 恢复器队首阻塞 — 已修复 ✅
- `WorkflowMediaResumeScheduler.tick`：SQL 层 JOIN media_job 且仅取终态（SUCCEEDED/FAILED/CANCELLED），
  按 `j.finished_at` 排序；仍在生成的节点不进入结果集，天然消除队首阻塞。
- 验证：事务内构造 20 个长期 RUNNING 等待节点 + 第 21 个已完成任务，新查询准确选中第 21 个（psql 事务回滚方式，未污染数据）。

### R01 / P1 崩溃窗口 — 已修复（务实版）✅
- `advanceMediaNode`/`failMediaNode`：节点终态、steps 更新与运行唤醒/失败收敛放入**同一事务**（新增 TransactionTemplate 注入）。
- 恢复器新增 `repairStuckRuns`（每轮执行）：
  ① 孤儿任务重链——媒体任务已创建但节点等待关系未落库的（崩溃于创建后、登记前），按 source_run_id/node_id 补建等待关系；
  ② 运行态修复——节点仍在等待而运行停在 RUNNING 的（markWaiting 前崩溃、恢复翻转被中断），翻回 WAITING_EXTERNAL 让 resumeWaiting 认领；
     续跑时媒体节点以 `workflow-runId-nodeKey` 幂等键复用既有任务，不会重复提交/计费。
- 边界说明：远程 HTTP 仍不在事务内（专家建议的原则）；普通 RUNNING 工作流的通用断点恢复（与媒体无关的节点中断）属后续工作（专家 §8 第二批）。

### R04 / P1 继续创作丢语义 — 已修复 ✅
- 任务详情接口补充 `referenceAssetIds`。
- 前端：image_edit 结果卡片的按钮改为「再做一版（同参考）」——以相同 operation/参考图顺序/参数重新提交（parentJobId 关联），带计费确认；
  text_to_image 结果保留「继续创作」。验证：试穿卡显示"再做一版"，文生图卡显示"继续创作"（未点击，避免计费）。

### R05 / P1 海报双存储 — 已修复 ✅
- `renderPoster` 重写：**单次存储**（mediaStorage 适配本地/OSS，此前直写 OSS 导致 LOCAL 模式海报链路必失败）；
  对象键 `workflow/{user}/{run}/{nodeKey}/poster-{rand}.png`（多海报节点互不覆盖）；
- 新增 `CreativeAssetService.registerRendered`：origin=**DERIVED**、metadataJson 记 workflowRunId/nodeKey、完整校验+缩略图；
  登记失败节点置为可重试失败（渲染确定性且零计费，重试不重新调用模型）；
- Artifact/ArtifactView 增加 assetId；产物 URL 优先 `asset:{id}`（前端鉴权 blob 渲染，本地/OSS 都可用），旧产物保持 OSS 签名兼容。
- 验证：免计费 E2E——纯海报工作流运行 SUCCEEDED，产物对象键唯一、素材 origin=DERIVED、编辑器产物图经 blob 正常显示；验证后已清理测试工作流与素材。

### R07 / P2 档案被解析重写 — 已修复 ✅
- `ensureProfile`：档案存在即返回（任何 adapter_key），解析只创建、不覆盖——人工维护的能力开关/验证状态/revision 不再被冲掉。
- 新迁移 `V12__media_profile_image_edit_backfill.sql`：为既有 openai-images 档案一次性回填 image_edit 能力（已应用并核对）。

### R03 / P1 运行契约 — 部分修复（完整版下批）✅
- 新共享模块 `workflowInputs.js`（deriveRunInputs）：创作首页、编辑器共用同一输入推导；
- 创作首页"工作流创作"弹窗渲染与编辑器一致的输入表单（淘宝模板现在能填 3 张图），提交带 input + projectId；
- 未做（下批）：版本化 inputs/outputs schema 声明、后端运行预检、运行级用户意图幂等键。

### 评审材料表述修正（§7）— 已修正 ✅
"零错字"→"确定性渲染器绘制"；"自动降级"限定服务可用性；"可恢复"限定已覆盖状态；海报来源标记随 R05 修正；模板导出导入列入第二批。

## 未处理（已排期，按专家建议顺序）

| 项 | 去向 | 说明 |
| --- | --- | --- |
| R06 引用递归/缺失报错/输出截断 | 第二批 | 依赖 R01 落地后的执行语义稳定 |
| R03 完整版（schema/预检/幂等键） | 第二批 | 与模板导出导入一起做 |
| §3 调度器有界执行池/心跳续租、b64 响应大小上限、WorkflowService 拆分 | 第二/四批 | 单线程调度阻塞确认为真实风险，批量前必须 |
| §5 WAITING_USER 确认节点、局部重跑、三版式 | 第三批 | 依赖第一批可靠性 |
| §6 UI 业务化字段/图片槽位卡/费用展示 | 第三批 | AssetParamField 已做单选回显与控件内样式修复，搜索/分页/防串数据列入第三批 |
| §4 节点 manifest、executor 拆分、空 catch 治理 | 持续 | 随各批次渐进 |

## 测试与验证汇总

- 单测：OpenAiImagesMediaProviderAdapterTest 11、MediaJobServiceRulesTest 2、MediaJobSchedulerTest 12、
  MediaModelResolverTest 5（改为绑定派生断言，不再硬编码具体模型）、LocalMediaStorageTest 2、ThumbnailEncoderTest 3 —— 全绿。
- R02 公平性：SQL 事务级验证（20+1 场景）。
- R05：免计费 E2E（纯海报工作流）。
- R03/R04：浏览器 UI 验证（首页表单字段、按钮语义区分）。
- 前端构建通过；后端 package 通过；服务重启健康。
