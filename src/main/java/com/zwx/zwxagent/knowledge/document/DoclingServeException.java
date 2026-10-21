package com.zwx.zwxagent.knowledge.document;

/** Docling Serve call failed; message is safe to persist in document error_message. */
public class DoclingServeException extends RuntimeException {
    public DoclingServeException(String message) {
        super(message);
    }

    public DoclingServeException(String message, Throwable cause) {
        super(message, cause);
    }
}
