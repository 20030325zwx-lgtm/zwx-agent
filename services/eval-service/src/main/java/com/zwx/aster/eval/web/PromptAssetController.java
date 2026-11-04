package com.zwx.aster.eval.web;

import com.zwx.aster.eval.security.JwtVerifier;
import com.zwx.aster.eval.service.PromptAssetService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Prompt 资产库 API：key@version 寻址，版本发布后不可变。 */
@RestController
@RequestMapping("/api/ai/eval/prompts")
public class PromptAssetController {

    private final PromptAssetService promptAssetService;

    public PromptAssetController(PromptAssetService promptAssetService) {
        this.promptAssetService = promptAssetService;
    }

    @PostMapping
    public Map<String, Object> create(JwtVerifier.Actor actor, @RequestBody Map<String, Object> body) {
        actor.requireAdmin();
        UUID id = promptAssetService.create(actor.tenantId(), stringOf(body.get("assetKey")),
                stringOf(body.get("name")), stringOf(body.get("description")));
        return Map.of("id", id.toString());
    }

    @GetMapping
    public List<Map<String, Object>> list(JwtVerifier.Actor actor) {
        actor.requireAdmin();
        return promptAssetService.list(actor.tenantId());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(JwtVerifier.Actor actor, @PathVariable UUID id) {
        actor.requireAdmin();
        return promptAssetService.get(actor.tenantId(), id);
    }

    @PostMapping("/{id}/versions")
    public Map<String, Object> publishVersion(JwtVerifier.Actor actor, @PathVariable UUID id,
                                              @RequestBody Map<String, Object> body) {
        actor.requireAdmin();
        List<String> params = body.get("params") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        int versionNo = promptAssetService.publishVersion(actor.tenantId(), id,
                stringOf(body.get("system")), stringOf(body.get("userTemplate")), params,
                stringOf(body.get("notes")));
        return Map.of("versionNo", versionNo);
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
