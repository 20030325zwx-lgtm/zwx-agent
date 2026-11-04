package com.zwx.aster.data.web;

import com.zwx.aster.data.security.JwtVerifier;
import com.zwx.aster.data.service.RunTraceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 轨迹浏览与「从 trace 建用例」（管理员）。 */
@RestController
@RequestMapping("/api/ai/feedback/runs")
public class RunTraceController {

    private final RunTraceService runTraceService;

    public RunTraceController(RunTraceService runTraceService) {
        this.runTraceService = runTraceService;
    }

    @GetMapping
    public List<Map<String, Object>> recent(JwtVerifier.Actor actor,
                                            @RequestParam(defaultValue = "30") int limit) {
        actor.requireAdmin();
        return runTraceService.recentRuns(actor, limit);
    }

    @GetMapping("/{runId}/trajectory")
    public Map<String, Object> trajectory(JwtVerifier.Actor actor, @PathVariable String runId) {
        actor.requireAdmin();
        return runTraceService.trajectory(actor, runId);
    }

    public record ToCaseRequest(String datasetId, List<String> tags) {
    }

    @PostMapping("/{runId}/to-case")
    public Map<String, Object> toCase(JwtVerifier.Actor actor, @PathVariable String runId,
                                      @RequestBody(required = false) ToCaseRequest request) {
        actor.requireAdmin();
        return runTraceService.toCase(actor, runId,
                request == null ? null : request.datasetId(),
                request == null ? null : request.tags());
    }
}
