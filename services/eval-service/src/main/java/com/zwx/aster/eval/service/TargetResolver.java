package com.zwx.aster.eval.service;

import com.zwx.aster.eval.domain.EvalType;
import com.zwx.aster.eval.domain.TargetSpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把创建实验时的 target 声明解析成冻结快照（eval_experiment.candidate）。
 * Prompt 正文在解析时抄进快照，所以历史实验永远可复现：资产后续改版不影响已跑过的实验。
 */
@Service
public class TargetResolver {

    private final PromptAssetService promptAssets;
    private final String defaultModel;

    public TargetResolver(PromptAssetService promptAssets,
                          @Value("${app.dashscope.model:qwen-plus}") String defaultModel) {
        this.promptAssets = promptAssets;
        this.defaultModel = defaultModel;
    }

    public Map<String, Object> resolve(String tenantId, TargetSpec spec, EvalType evalType, String notes) {
        Map<String, Object> snapshot = new LinkedHashMap<>(spec.toSnapshot());
        snapshot.put("evalType", evalType.lower());
        if (notes != null && !notes.isBlank()) snapshot.put("notes", notes.trim());

        if (evalType == EvalType.PROMPT) {
            String model = spec.model() == null ? defaultModel : spec.model().modelName();
            Map<String, Object> modelNode = new LinkedHashMap<>();
            modelNode.put("provider", spec.model() == null ? "dashscope" : spec.model().provider());
            modelNode.put("modelName", model);
            modelNode.put("source", spec.model() == null ? "eval-service-config" : "experiment-target");
            if (spec.model() != null && spec.model().temperature() != null) {
                modelNode.put("temperature", spec.model().temperature());
            }
            snapshot.put("model", modelNode);

            if (spec.prompt() != null) {
                PromptAssetService.ResolvedPrompt resolved = promptAssets.resolve(tenantId, spec.prompt());
                Map<String, Object> promptNode = new LinkedHashMap<>();
                promptNode.put("assetKey", resolved.assetKey());
                promptNode.put("versionNo", resolved.versionNo());
                promptNode.put("system", resolved.system());
                promptNode.put("systemSha256", sha256(resolved.system()));
                snapshot.put("prompt", promptNode);
            }
        } else {
            // 执行在主站：模型由线上配置决定，这里只固定「测的是哪条链路」
            Map<String, Object> modelNode = new LinkedHashMap<>();
            modelNode.put("provider", "backend-internal");
            modelNode.put("source", "live");
            snapshot.put("model", modelNode);
            Map<String, Object> agentNode = new LinkedHashMap<>();
            agentNode.put("agentKey", spec.agent() == null ? "super" : spec.agent().agentKey());
            snapshot.put("agent", agentNode);
        }
        snapshot.put("resolvedAt", Instant.now().toString());
        return snapshot;
    }

    static String sha256(String value) {
        if (value == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception exception) {
            return null;
        }
    }
}
