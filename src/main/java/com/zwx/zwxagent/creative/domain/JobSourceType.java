package com.zwx.zwxagent.creative.domain;

/** 任务来源。由可信服务入口注入，DTO 不接受该字段（实施手册 §2.1）。 */
public enum JobSourceType {
    UI,
    AGENT,
    WORKFLOW,
    TEST
}
