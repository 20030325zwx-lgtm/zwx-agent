package com.zwx.zwxagent.controller;

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

    @ExceptionHandler({com.zwx.zwxagent.conversation.LoveConversationService.DuplicateRequestException.class,
            com.zwx.zwxagent.conversation.AgentConversationService.DuplicateRequestException.class,
            com.zwx.zwxagent.conversation.ConversationBusyException.class})
    public ResponseEntity<Map<String, String>> handleDuplicateRequest(Exception exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(org.springframework.core.task.TaskRejectedException.class)
    public ResponseEntity<Map<String, String>> handleTaskRejected(org.springframework.core.task.TaskRejectedException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", "索引任务排队已满，请稍后重试"));
    }
}
