package com.zwx.zwxagent.creative.domain;

/**
 * 创作模块领域异常：带稳定 error_code（总体设计 §15），由统一异常处理映射响应。
 * retryable 表示调用方是否可以原样重试。
 */
public class CreativeException extends RuntimeException {

    public static final String MODEL_NOT_CONFIGURED = "MODEL_NOT_CONFIGURED";
    public static final String UNSUPPORTED_PARAMETER = "UNSUPPORTED_PARAMETER";
    public static final String ASSET_ACCESS_DENIED = "ASSET_ACCESS_DENIED";

    private final String code;
    private final boolean retryable;

    public CreativeException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public static CreativeException modelNotConfigured(String message) {
        return new CreativeException(MODEL_NOT_CONFIGURED, message, false);
    }

    public static CreativeException unsupportedParameter(String message) {
        return new CreativeException(UNSUPPORTED_PARAMETER, message, false);
    }
}
