package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.creative.domain.MediaJobEventRecord;
import com.zwx.zwxagent.creative.domain.MediaJobOutputRecord;
import com.zwx.zwxagent.creative.domain.MediaJobRecord;
import com.zwx.zwxagent.creative.repository.MediaJobOutputRepository;
import com.zwx.zwxagent.creative.service.MediaJobService;
import com.zwx.zwxagent.creative.support.CreativeJson;
import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 媒体任务 API：POST /jobs 幂等受理（新建 202 / 复用 200）；列表、详情（含 allowedActions）、
 * 取消/重试/恢复入库、事件游标与 SSE 推送（C08）。
 */
@RestController
@RequestMapping("/ai/creative/jobs")
public class CreativeJobController {

    private final MediaJobService jobService;
    private final MediaJobOutputRepository outputRepository;
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public CreativeJobController(MediaJobService jobService,
                                 MediaJobOutputRepository outputRepository) {
        this.jobService = jobService;
        this.outputRepository = outputRepository;
    }

    public record CreateJobBody(UUID projectId, String operation, Long modelRowId,
                                String originalPrompt, String effectivePrompt,
                                Map<String, Object> parameters, UUID draftId,
                                UUID parentJobId, Boolean acknowledgePossibleDuplicateCharge) {
    }

    public record RetryBody(Boolean acknowledgePossibleDuplicateCharge) {
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            CurrentActor actor,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateJobBody body) {
        if (body == null || body.projectId() == null) {
            throw new IllegalArgumentException("缺少 projectId");
        }
        var result = jobService.create(actor, idempotencyKey, body.projectId(),
                new MediaJobService.CreateJobRequest(body.projectId(), body.operation(), body.modelRowId(),
                        body.originalPrompt(), body.effectivePrompt(), body.parameters(),
                        null, body.draftId(), body.parentJobId(),
                        body.acknowledgePossibleDuplicateCharge()));
        return ResponseEntity
                .status(result.reused() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(Map.of("jobId", result.jobId().toString(), "status", result.status(),
                        "reused", result.reused(), "version", result.version()));
    }

    @GetMapping
    public Map<String, Object> list(CurrentActor actor,
                                    @RequestParam(required = false) UUID projectId,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(defaultValue = "20") int limit) {
        List<MediaJobRecord> jobs = jobService.list(actor, projectId, cursor, limit);
        List<Map<String, Object>> items = jobs.stream().map(this::summary).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("nextCursor", jobs.size() < Math.max(1, Math.min(limit, 100)) || jobs.isEmpty()
                ? null : MediaJobService.cursorOf(jobs.get(jobs.size() - 1)));
        return body;
    }

    @GetMapping("/{jobId}")
    public Map<String, Object> get(CurrentActor actor, @PathVariable UUID jobId) {
        MediaJobRecord job = jobService.getForOwner(actor, jobId);
        Map<String, Object> body = new LinkedHashMap<>(summary(job));
        body.put("parameters", job.inputSnapshot().parameters());
        body.put("modelRowId", job.modelSnapshot().modelRowId());
        body.put("modelName", job.modelSnapshot().modelName());
        body.put("parentJobId", job.parentJobId() == null ? null : job.parentJobId().toString());
        body.put("attentionRequired", job.attentionRequired());
        body.put("blockedReason", job.blockedReason());
        body.put("errorCode", job.errorCode());
        body.put("error", job.errorSummary());
        body.put("providerTaskId", job.providerTaskId());
        body.put("outputs", outputsOf(job.id()));
        return body;
    }

    @PostMapping("/{jobId}/cancel")
    public Map<String, Object> cancel(CurrentActor actor, @PathVariable UUID jobId) {
        return summary(jobService.cancel(actor, jobId));
    }

    @PostMapping("/{jobId}/retry")
    public ResponseEntity<Map<String, Object>> retry(
            CurrentActor actor, @PathVariable UUID jobId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) RetryBody body) {
        var result = jobService.retry(actor, jobId, idempotencyKey,
                body != null && Boolean.TRUE.equals(body.acknowledgePossibleDuplicateCharge()));
        return ResponseEntity.status(result.reused() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(Map.of("jobId", result.jobId().toString(), "status", result.status(),
                        "reused", result.reused(), "version", result.version()));
    }

    @PostMapping("/{jobId}/resume-ingest")
    public Map<String, Object> resumeIngest(CurrentActor actor, @PathVariable UUID jobId) {
        return summary(jobService.resumeIngest(actor, jobId));
    }

    @GetMapping("/{jobId}/events")
    public Map<String, Object> events(CurrentActor actor, @PathVariable UUID jobId,
                                      @RequestParam(defaultValue = "0") long afterSeq,
                                      @RequestParam(defaultValue = "200") int limit) {
        var page = jobService.eventPage(actor, jobId, afterSeq, limit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("events", page.events().stream().map(this::eventView).toList());
        body.put("nextSeq", page.nextSeq());
        body.put("reset", page.reset());
        if (page.reset() && page.snapshot() != null) {
            body.put("snapshot", summary(page.snapshot()));
        }
        return body;
    }

    /** SSE：按 seq 增量推送事件与状态快照；终态或超时后关闭，客户端可凭 afterSeq 重连续传。 */
    @GetMapping(value = "/{jobId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(CurrentActor actor, @PathVariable UUID jobId,
                             @RequestParam(defaultValue = "0") long afterSeq) {
        jobService.getForOwner(actor, jobId);
        SseEmitter emitter = new SseEmitter(120_000L);
        streamExecutor.submit(() -> {
            long cursor = afterSeq;
            try {
                for (int i = 0; i < 120; i++) {
                    var page = jobService.eventPage(actor, jobId, cursor, 200);
                    if (page.reset()) {
                        // 游标越界：先让客户端重置，再以快照的 nextSeq 继续，避免漏事件
                        emitter.send(SseEmitter.event().name("job-reset")
                                .data(summary(page.snapshot())));
                        cursor = page.nextSeq();
                        continue;
                    }
                    var events = page.events();
                    for (var event : events) {
                        emitter.send(SseEmitter.event().id(Long.toString(event.seq()))
                                .name("job-event").data(eventView(event)));
                        cursor = event.seq();
                    }
                    var job = jobService.getForOwner(actor, jobId);
                    emitter.send(SseEmitter.event().name("job-status").data(summary(job)));
                    if (job.status().name().equals("SUCCEEDED") || job.status().name().equals("FAILED")
                            || job.status().name().equals("CANCELLED")) {
                        break;
                    }
                    Thread.sleep(1000L);
                }
                emitter.complete();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                emitter.complete();
            } catch (Exception exception) {
                emitter.completeWithError(exception);
            }
        });
        return emitter;
    }

    private Map<String, Object> summary(MediaJobRecord job) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jobId", job.id().toString());
        map.put("projectId", job.projectId().toString());
        map.put("operation", job.operation());
        map.put("sourceType", job.sourceType().name());
        map.put("sourceRunId", job.sourceRunId());
        map.put("sourceNodeId", job.sourceNodeId());
        map.put("prompt", job.inputSnapshot().prompt());
        map.put("status", job.status().name());
        map.put("version", job.version());
        map.put("allowedActions", MediaJobService.allowedActions(job.status()));
        map.put("createdAt", job.createdAt().toString());
        map.put("updatedAt", job.updatedAt().toString());
        return map;
    }

    private List<Map<String, Object>> outputsOf(UUID jobId) {
        List<MediaJobOutputRecord> outputs = outputRepository.findByJob(jobId);
        List<Map<String, Object>> views = new ArrayList<>();
        for (var output : outputs) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("assetId", output.assetId().toString());
            view.put("index", output.outputIndex());
            view.put("state", output.ingestState().name());
            view.put("sha256", output.sha256());
            view.put("byteSize", output.byteSize());
            view.put("error", output.lastError());
            views.add(view);
        }
        return views;
    }

    private Map<String, Object> eventView(MediaJobEventRecord event) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("seq", event.seq());
        view.put("type", event.eventType());
        view.put("payload", CreativeJson.parse(event.payloadJson()));
        view.put("createdAt", event.createdAt().toString());
        return view;
    }
}
