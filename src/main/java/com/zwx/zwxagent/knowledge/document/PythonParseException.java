package com.zwx.zwxagent.knowledge.document;

/** 统一解析服务（Python sidecar）调用失败。 */
public class PythonParseException extends RuntimeException {

    public PythonParseException(String message) {
        super(message);
    }

    public PythonParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
