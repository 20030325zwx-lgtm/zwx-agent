package com.zwx.zwxagent.web.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", exception.getMessage() == null ? "Access denied" : exception.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("error", exception.getReason() == null ? exception.getStatusCode().toString() : exception.getReason()));
    }

    @ExceptionHandler({IllegalArgumentException.class, NoSuchElementException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", exception.getMessage() == null ? "Invalid request" : exception.getMessage()));
    }

    @ExceptionHandler(org.springframework.dao.EmptyResultDataAccessException.class)
    public ResponseEntity<Map<String, String>> handleMissingRecord(
            org.springframework.dao.EmptyResultDataAccessException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Resource not found"));
    }

    @ExceptionHandler({com.zwx.zwxagent.chat.conversation.AgentConversationService.DuplicateRequestException.class,
            com.zwx.zwxagent.chat.conversation.ConversationBusyException.class,
            com.zwx.zwxagent.platform.workspace.ProjectWorkspaceConflictException.class})
    public ResponseEntity<Map<String, String>> handleDuplicateRequest(Exception exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(org.springframework.core.task.TaskRejectedException.class)
    public ResponseEntity<Map<String, String>> handleTaskRejected(org.springframework.core.task.TaskRejectedException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", "索引任务排队已满，请稍后重试"));
    }

    /** 创作模块：乐观锁/状态冲突 → 409（BR18，不自动覆盖服务器值）。 */
    @ExceptionHandler(com.zwx.zwxagent.creative.domain.CreativeConflictException.class)
    public ResponseEntity<Map<String, String>> handleCreativeConflict(
            com.zwx.zwxagent.creative.domain.CreativeConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", exception.getMessage()));
    }

    /** 创作模块：本地配额超限 → 429（不创建半成品任务）。 */
    @ExceptionHandler(com.zwx.zwxagent.creative.domain.CreativeQuotaException.class)
    public ResponseEntity<Map<String, String>> handleCreativeQuota(
            com.zwx.zwxagent.creative.domain.CreativeQuotaException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", exception.getMessage()));
    }

    /** 创作模块领域异常：按 code 区分（模型未配置 400、参数/能力不支持 422）。 */
    @ExceptionHandler(com.zwx.zwxagent.creative.domain.CreativeException.class)
    public ResponseEntity<Map<String, String>> handleCreativeException(
            com.zwx.zwxagent.creative.domain.CreativeException exception) {
        HttpStatus status = com.zwx.zwxagent.creative.domain.CreativeException.UNSUPPORTED_PARAMETER
                .equals(exception.code()) ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("error", exception.getMessage()));
    }
}
