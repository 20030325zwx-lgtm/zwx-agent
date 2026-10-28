package com.zwx.zwxagent.capability.tools;

/**
 * 工具风险等级：权限模式矩阵按风险等级决定放行方式。
 */
public enum ToolRisk {

    /** 只读检索：读文件、搜索、联网抓取、时间、项目只读查询。 */
    READ_ONLY,
    /** 交付物写入：写文件、生成 PDF 等，不修改既有源码。 */
    WRITE,
    /** 执行/修改类：打补丁、运行项目任务、数据库查询、资源下载、MCP 工具。 */
    EXECUTE,
    /** 高危：终端命令、Git 提交、外部数据库访问。 */
    DESTRUCTIVE
}
