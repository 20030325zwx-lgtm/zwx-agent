# 会话摘要：Flowable 文档工作流小范围评估（2026-10-23）

## 改动内容

- 新增隔离的 `evaluation/flowable-document-poc`，基于 Spring Boot 3.4.4、Java 21 和 Flowable 7.2.0。
- 使用 H2，不接入主应用 PostgreSQL、Flyway、MinerU、OSS 或 PGVector。
- BPMN 样例覆盖解析、切片、向量入库、检索验证、人工审批，以及通过/拒绝分支；自动步骤是 mock delegate。
- 提供启动流程、查询人工任务、完成人工任务的最小 API，仅监听本机 8137 端口。
- 增加 Flowable UI 6.8 独立 Docker Compose 部署：独立 PostgreSQL 容器/数据卷，UI 端口仅绑定 `127.0.0.1:8181`，本地 `.env` 不纳入 Git。
- 增加 `document-review.bpmn20.xml` 与 `pilot-workflow.sh`，可经官方 Process REST API 部署、启动、查待办及提交审批变量。

## 验证结果

- `mvn test`：1 个流程测试通过，0 failures / 0 errors。
- `mvn spring-boot:run` 启动成功；HTTP 冒烟验证确认流程自动到达人工审批，审批后流程结束且无剩余任务。
- 主应用 `pom.xml`、配置及运行数据库未改动。
- UI/Modeler/Task/Admin/IDM 页面均可访问；Task UI 能显示已分配给管理员的“人工确认检索结果”任务。
- 独立 UI 内的 Process REST API 已验证流程部署、启动、待办查询和审批通过后历史实例结束；最终留有一条待审试点任务供人工查看。
- 试点已通过 PostgreSQL 连接并持久化 Flowable 表；Docker Compose 服务当前保持运行。

## 评估结论

- Flowable 流程引擎可在当前 Spring Boot 3.4 代际独立运行；持久化状态、人工任务暂停/继续和条件分支的基本机制适用于文档入库流程。
- 本原型未验证任务失败重试、跨进程重启恢复、真实文档服务集成、多租户授权或吞吐。
- Flowable 引擎 starter 不自带可视化设计/运维前端；仅提供 BPMN 文件。Flowable UI/Modeler 需要另行部署并评估产品版本、接口兼容性及许可，不能据此认定视觉管理体验已完成。

## 遗留事项

- 可视化 UI 已评估：官方 `flowable/flowable-ui:6.8.0` 包含 BPMN Modeler 与 Task/Admin/IDM 页面；建模器画布和 BPMN 组件面板已在浏览器实际打开核对。
- UI 镜像自带 Flowable 6.8.0 / Spring Boot 2.7.6 / Java 11，与本 POC Flowable 7.2 / Spring Boot 3.4 不同代，不能认作 7.2 的配套 UI。
- 已启动独立 Flowable UI 6.8 + PostgreSQL 试点，通过 REST 部署并操作审批样例；但主应用上传/索引链路尚未接入。
- 后续应验证 HTTP/External Worker 服务步骤、身份与租户映射及认证后，再接 MinerU 与真实索引服务。
- 若要求 UI 与 7.2 同实例，需在 Vue 中嵌入 BPMN 建模器并自行建设任务/运维页；这是定制开发，不是现成 Flowable UI 集成。
- 生产接入前需把 mock delegate 替换成现有解析/索引服务，并设计用户身份映射、tenant 校验、任务幂等和失败补偿。
- 本轮独立 UI 6.8 Compose 已启动并保留运行；REST API 前缀为 `/flowable-ui/process-api`。当前 `tenantId` 只是流程变量，Task UI 示例没有专用审批表单，审核值由 `pilot-workflow.sh` 通过 REST 提交。
- `.env` 是本机试点口令并被根 `.gitignore` 忽略；不得把 UI 端口暴露到公网或用该管理员模式承载多租户数据。
- 当前仍未从主应用上传接口自动发起 Flowable 流程；此处验证的是独立服务部署及 REST 接入边界。
