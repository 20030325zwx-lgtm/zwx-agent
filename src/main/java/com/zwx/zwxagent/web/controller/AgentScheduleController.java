package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.agent.schedule.AgentScheduleService;
import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 定时任务：到点以指令拉起智能体运行；页面「定时任务」的数据面。 */
@RestController
@RequestMapping("/ai/schedules")
public class AgentScheduleController {

    private final AgentScheduleService service;

    public AgentScheduleController(AgentScheduleService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list(CurrentActor actor) {
        List<AgentScheduleService.TaskView> tasks = service.list(actor);
        return Map.of("tasks", tasks, "schedulerEnabled", service.schedulerEnabled());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(CurrentActor actor,
                                                      @RequestBody AgentScheduleService.SaveBody body) {
        var task = service.create(actor, body);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("task", task));
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(CurrentActor actor, @PathVariable String id,
                                      @RequestBody AgentScheduleService.SaveBody body) {
        return Map.of("task", service.update(actor, id, body));
    }

    @PostMapping("/{id}/enable")
    public Map<String, Object> enable(CurrentActor actor, @PathVariable String id,
                                      @RequestBody Map<String, Boolean> body) {
        boolean enabled = !Boolean.FALSE.equals(body == null ? null : body.get("enabled"));
        service.setEnabled(actor, id, enabled);
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(CurrentActor actor, @PathVariable String id) {
        service.delete(actor, id);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/run-now")
    public Map<String, Object> runNow(CurrentActor actor, @PathVariable String id) {
        return Map.of("fire", service.fireNow(actor, id));
    }

    @GetMapping("/{id}/fires")
    public Map<String, Object> fires(CurrentActor actor, @PathVariable String id,
                                     @RequestParam(defaultValue = "20") int limit) {
        return Map.of("fires", service.fires(actor, id, limit));
    }
}
