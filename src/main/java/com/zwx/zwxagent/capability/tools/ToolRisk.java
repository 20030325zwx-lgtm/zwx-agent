package com.zwx.zwxagent.capability.tools;

/**
 * 工具风险等级：权限模式矩阵按风险等级决定放行方式。
 * 分级对标通用做法 L1~L4：低风险直接执行 / 中风险参数校验 / 高风险人工审批 / 极高风险默认拒绝。
 */
public enum ToolRisk {

    /** L1 只读检索：读文件、搜索、联网抓取、时间、项目只读查询。 */
    READ_ONLY,
    /** L2 交付物写入：写文件、生成 PDF 等，不修改既有源码。 */
    WRITE,
    /** L3 执行/修改类：打补丁、运行项目任务、数据库查询、资源下载、MCP 工具。 */
    EXECUTE,
    /** L3+ 高危：终端命令、Git 提交——各权限模式下逐次人工确认。 */
    DESTRUCTIVE,
    /** L4 极高风险：默认拒绝（含 YOLO），仅用户显式配置 ALLOW 规则可解锁（如外部数据库访问）。 */
    FORBIDDEN
}
