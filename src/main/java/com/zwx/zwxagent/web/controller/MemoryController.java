package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.chat.memory.MemoryService;
import com.zwx.zwxagent.platform.security.CurrentActor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 长期记忆治理接口：用户可以查看、补充、修正与删除「被记住的自己」。
 * 按 CurrentActor 的租户与用户归属过滤；agentKey 可选过滤。
 */
@Slf4j
@RestController
@RequestMapping("/ai/memory")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping("/facts")
    public List<MemoryService.MemoryFactView> listFacts(CurrentActor actor,
                                                        @RequestParam(required = false) String agentKey,
                                                        @RequestParam(defaultValue = "100") int limit) {
        return memoryService.listFacts(actor.tenantId(), actor.userId(), agentKey, limit);
    }

    @PostMapping("/facts")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addFact(CurrentActor actor, @RequestBody FactRequest request) {
        requireFact(request.fact());
        memoryService.addFact(actor.tenantId(), actor.userId(), agentKeyOf(request.agentKey()), request.fact());
        return Map.of("created", true);
    }

    @PutMapping("/facts/{id}")
    public Map<String, Object> updateFact(CurrentActor actor, @PathVariable long id, @RequestBody FactRequest request) {
        requireFact(request.fact());
        boolean updated = memoryService.updateFact(actor.tenantId(), actor.userId(), id, request.fact());
        if (!updated) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "事实不存在，或与已保存的其他事实重复（系统按内容去重）");
        }
        return Map.of("updated", true);
    }

    @DeleteMapping("/facts/{id}")
    public Map<String, Object> deleteFact(CurrentActor actor, @PathVariable long id) {
        boolean deleted = memoryService.deleteFact(actor.tenantId(), actor.userId(), id);
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "事实不存在或无权删除");
        }
        return Map.of("deleted", true);
    }

    private void requireFact(String fact) {
        if (fact == null || fact.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事实内容不能为空");
        }
    }

    private String agentKeyOf(String agentKey) {
        String normalized = agentKey == null ? "" : agentKey.trim();
        return normalized.isEmpty() ? "super" : normalized;
    }

    public record FactRequest(String agentKey, String fact) {
    }
}
