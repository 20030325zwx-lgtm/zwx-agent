package com.zwx.zwxagent.creative.domain;

/** 乐观锁/状态冲突（BR15/BR18）：由统一异常处理映射为 409，不自动覆盖服务器值。 */
public class CreativeConflictException extends RuntimeException {

    public CreativeConflictException(String message) {
        super(message);
    }
}
