package com.zwx.zwxagent.creative.domain;

/** 本地配额超限（排队/在途）。由统一异常处理映射为 429，不创建半成品任务。 */
public class CreativeQuotaException extends RuntimeException {

    public CreativeQuotaException(String message) {
        super(message);
    }
}
