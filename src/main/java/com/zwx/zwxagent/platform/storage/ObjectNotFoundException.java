package com.zwx.zwxagent.platform.storage;

/** 对象不存在：路由层据此回退到备用存储（历史对象仍在 OSS 的场景）。 */
public class ObjectNotFoundException extends RuntimeException {

    private final String objectKey;

    public ObjectNotFoundException(String objectKey, String message) {
        super(message == null || message.isBlank() ? "对象不存在：" + objectKey : message);
        this.objectKey = objectKey;
    }

    public ObjectNotFoundException(String objectKey) {
        this(objectKey, null);
    }

    public String objectKey() {
        return objectKey;
    }
}
