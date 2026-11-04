package com.zwx.aster.data.web;

import com.zwx.aster.data.domain.UserFeedbackRecord;
import com.zwx.aster.data.security.JwtVerifier;
import com.zwx.aster.data.service.UserFeedbackService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 数据中台 API：反馈采集（登录用户）与 BadCase 管理/归类/回流（管理员）。 */
@RestController
@RequestMapping("/api/ai/feedback")
public class UserFeedbackController {

    private final UserFeedbackService feedbackService;

    public UserFeedbackController(UserFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    public record CreateFeedbackRequest(String rating, String reason, List<String> tags,
                                        String runId, String conversationId) {
    }

    @PostMapping
    public Map<String, Object> create(JwtVerifier.Actor actor, @RequestBody CreateFeedbackRequest request) {
        UserFeedbackRecord record = feedbackService.create(actor, request.rating(), request.reason(),
                request.tags(), request.runId(), request.conversationId());
        return view(record);
    }

    @GetMapping
    public Map<String, Object> list(JwtVerifier.Actor actor,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String rating,
                                    @RequestParam(required = false) String source,
                                    @RequestParam(required = false) String rootCause,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(defaultValue = "30") int limit) {
        actor.requireAdmin();
        Long cursorMillis = null;
        String cursorId = null;
        if (cursor != null && cursor.contains(":")) {
            String[] parts = cursor.split(":", 2);
            try {
                cursorMillis = Long.parseLong(parts[0]);
                cursorId = parts[1];
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("cursor 格式非法");
            }
        }
        List<UserFeedbackRecord> items = feedbackService.list(actor, status, rating, source, rootCause,
                cursorMillis, cursorId, limit);
        String nextCursor = null;
        if (items.size() == Math.min(Math.max(limit, 1), 100)) {
            var last = items.get(items.size() - 1);
            nextCursor = last.createdAt().toEpochMilli() + ":" + last.id();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items.stream().map(UserFeedbackController::view).toList());
        out.put("nextCursor", nextCursor);
        out.put("counts", feedbackService.counts(actor));
        return out;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(JwtVerifier.Actor actor) {
        actor.requireAdmin();
        return feedbackService.stats(actor);
    }

    public record StatusRequest(String status) {
    }

    @PostMapping("/{id}/status")
    public Map<String, Object> updateStatus(JwtVerifier.Actor actor, @PathVariable UUID id,
                                            @RequestBody StatusRequest request) {
        actor.requireAdmin();
        feedbackService.updateStatus(actor, id, request.status());
        return Map.of("updated", true);
    }

    public record AnalysisRequest(String rootCause, String assignee, String resolution) {
    }

    public record TagsRequest(List<String> tags) {
    }

    /** 点踩后的可选第二步：补快捷标签（本人、未处理）。 */
    @PatchMapping("/{id}/tags")
    public Map<String, Object> updateTags(JwtVerifier.Actor actor, @PathVariable UUID id,
                                          @RequestBody TagsRequest request) {
        return view(feedbackService.updateTags(actor, id, request.tags()));
    }

    /** 根因归类：可反复更新，是 BadCase 从「待办」变成「可统计」的关键一步。 */
    @PutMapping("/{id}/analysis")
    public Map<String, Object> updateAnalysis(JwtVerifier.Actor actor, @PathVariable UUID id,
                                              @RequestBody AnalysisRequest request) {
        actor.requireAdmin();
        return view(feedbackService.updateAnalysis(actor, id, request.rootCause(),
                request.assignee(), request.resolution()));
    }

    @PostMapping("/{id}/convert")
    public Map<String, Object> convert(JwtVerifier.Actor actor, @PathVariable UUID id) {
        actor.requireAdmin();
        return feedbackService.convert(actor, id);
    }

    static Map<String, Object> view(UserFeedbackRecord record) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", record.id().toString());
        view.put("rating", record.rating());
        view.put("source", record.source());
        view.put("status", record.status());
        view.put("reason", record.reason());
        view.put("tags", record.tags());
        view.put("rootCause", record.rootCause());
        view.put("assignee", record.assignee());
        view.put("resolution", record.resolution());
        view.put("refs", record.refs());
        view.put("convertedDatasetId", record.convertedDatasetId() == null ? null : record.convertedDatasetId().toString());
        view.put("convertedCaseId", record.convertedCaseId() == null ? null : record.convertedCaseId().toString());
        view.put("createdAt", record.createdAt());
        return view;
    }
}
