package com.zwx.zwxagent.creative.skill;

import com.zwx.zwxagent.creative.domain.AssetStatus;
import com.zwx.zwxagent.creative.domain.CreativeConflictException;
import com.zwx.zwxagent.creative.domain.CreativeException;
import com.zwx.zwxagent.creative.domain.CreativeAssetRecord;
import com.zwx.zwxagent.creative.domain.JobSourceType;
import com.zwx.zwxagent.creative.domain.MediaJobRecord;
import com.zwx.zwxagent.creative.repository.CreativeAssetRepository;
import com.zwx.zwxagent.creative.repository.MediaJobRepository;
import com.zwx.zwxagent.creative.service.CreativeProjectService;
import com.zwx.zwxagent.creative.service.MediaJobService;
import com.zwx.zwxagent.creative.support.CreativeJson;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.platform.settings.DynamicChatModelFactory;
import com.zwx.zwxagent.platform.settings.ModelCatalogService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 创作 Skill 服务（skill 设计文档 P0-A/P0-B MVP）：
 * 内置技能种子、按用户绑定计算兼容状态、方案生成（文本，不伪造视觉分析）、
 * 确认后经 MediaJobService.create 幂等提交（UI 来源 + sourceRunId=runId 归属）。
 * MediaJob 仍是生成状态真相；run 只保存方案与任务关联。
 * MVP 边界：一个 run 至多一个 media_job；外部导入与多镜头状态机留待 P0-C/P1。
 */
@Service
public class CreativeSkillService {

    private static final Logger log = LoggerFactory.getLogger(CreativeSkillService.class);

    /** 首帧角色绑定：唯一允许发送给视频模型的素材角色（MVP 仅 image_to_video）。 */
    public static final String ROLE_FIRST_FRAME = "FIRST_FRAME";
    private static final Set<String> KNOWN_ROLES = Set.of(ROLE_FIRST_FRAME);
    private static final int PROMPT_MAX = 8000;
    private static final int MAX_BINDINGS = 3;

    private final CreativeSkillRepository repository;
    private final CreativeProjectService projectService;
    private final CreativeAssetRepository assetRepository;
    private final ModelCatalogService catalogService;
    private final DynamicChatModelFactory chatModelFactory;
    private final MediaJobService jobService;
    private final MediaJobRepository jobRepository;

    public CreativeSkillService(CreativeSkillRepository repository,
                                CreativeProjectService projectService,
                                CreativeAssetRepository assetRepository,
                                ModelCatalogService catalogService,
                                DynamicChatModelFactory chatModelFactory,
                                MediaJobService jobService,
                                MediaJobRepository jobRepository) {
        this.repository = repository;
        this.projectService = projectService;
        this.assetRepository = assetRepository;
        this.catalogService = catalogService;
        this.chatModelFactory = chatModelFactory;
        this.jobService = jobService;
        this.jobRepository = jobRepository;
    }

    // ---------------------------------------------------------------- 种子

    @PostConstruct
    void seed() {
        try {
            for (CreativeSkillContent.Seed seed : CreativeSkillContent.SEEDS) {
                if (repository.skillSlugExists(seed.slug())) continue;
                UUID skillId = UUID.randomUUID();
                repository.insertSkill(CreativeSkillContent.skillRecord(seed, skillId));
                if (!repository.versionExists(skillId, 1)) {
                    repository.insertVersion(CreativeSkillContent.versionRecord(
                            seed, skillId, UUID.randomUUID(), seed.body()));
                }
                log.info("[creative-skills] 已内置技能 {}（{}）", seed.slug(), seed.name());
            }
        } catch (RuntimeException exception) {
            // 种子失败不阻断启动：技能页如实显示空目录（与 SkillRepository 的容错原则一致）
            log.warn("[creative-skills] 内置技能种子失败：{}", exception.getMessage());
        }
    }

    // ---------------------------------------------------------------- 目录与详情

    public List<Map<String, Object>> list(CurrentActor actor) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (CreativeSkillRecord skill : repository.listPublished()) {
            items.add(itemView(actor, skill, null));
        }
        return items;
    }

    public Map<String, Object> detail(CurrentActor actor, String slug) {
        CreativeSkillRecord skill = repository.findPublishedBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("技能不存在或未发布"));
        CreativeSkillVersionRecord version = repository.findVersion(skill.id(), skill.latestVersion())
                .orElseThrow(() -> new IllegalStateException("技能版本缺失：" + slug));
        return itemView(actor, skill, version);
    }

    private Map<String, Object> itemView(CurrentActor actor, CreativeSkillRecord skill,
                                         CreativeSkillVersionRecord version) {
        List<String> missing = missingCapabilities(actor.userId(), skill.requiredCapabilities());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", skill.id().toString());
        view.put("slug", skill.slug());
        view.put("name", skill.name());
        view.put("summary", skill.summary());
        view.put("category", skill.category());
        view.put("stage", skill.stage());
        view.put("inputTypes", skill.inputTypes());
        view.put("outputTypes", skill.outputTypes());
        view.put("requiredCapabilities", skill.requiredCapabilities());
        view.put("version", skill.latestVersion());
        // 兼容状态（skill 设计 §5.1）：READY=全部能力就绪；MISSING=写明缺什么；未知能力按缺能力处理
        view.put("compat", missing.isEmpty() ? "READY" : "MISSING");
        view.put("missing", missing);
        if (version != null) {
            view.put("body", version.body());
            view.put("sourceType", version.sourceType());
            view.put("licenseStatus", version.licenseStatus());
        }
        return view;
    }

    private List<String> missingCapabilities(String userId, List<String> required) {
        List<String> missing = new ArrayList<>();
        if (required.contains("CHAT") && !chatAvailable(userId)) missing.add("方案生成模型未配置");
        if ((required.contains("TEXT_TO_VIDEO") || required.contains("IMAGE_TO_VIDEO"))
                && catalogService.resolveVideoBinding(userId).isEmpty()) {
            missing.add("视频生成模型未配置");
        }
        if (required.contains("IMAGE_GENERATION")
                && catalogService.resolveImageBinding(userId).isEmpty()) {
            missing.add("图片生成模型未配置");
        }
        for (String capability : required) {
            if (!KNOWN_CAPABILITIES.contains(capability)) missing.add("暂不支持的能力：" + capability);
        }
        return missing;
    }

    private static final Set<String> KNOWN_CAPABILITIES = Set.of("CHAT", "TEXT_TO_VIDEO", "IMAGE_TO_VIDEO",
            "IMAGE_GENERATION");

    private boolean chatAvailable(String userId) {
        try {
            catalogService.resolveWithFallback(userId,
                    ModelCatalogService.PURPOSE_OPTIMIZE, ModelCatalogService.PURPOSE_CHAT);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 运行：创建与方案生成

    public Map<String, Object> createRun(CurrentActor actor, UUID projectId, String slug,
                                         String prompt, List<Map<String, Object>> bindings) {
        CreativeSkillRecord skill = repository.findPublishedBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("技能不存在或未发布"));
        CreativeSkillVersionRecord version = repository.findVersion(skill.id(), skill.latestVersion())
                .orElseThrow(() -> new IllegalStateException("技能版本缺失：" + slug));
        String text = prompt == null ? "" : prompt.trim();
        if (text.isBlank()) throw new IllegalArgumentException("请输入创作想法（prompt）");
        if (text.length() > PROMPT_MAX) {
            throw CreativeException.unsupportedParameter("创作想法超过 " + PROMPT_MAX + " 字上限");
        }
        List<Map<String, Object>> normalizedBindings = normalizeBindings(actor, skill, bindings);

        CreativeProjectService.ProjectView project = projectService.get(actor, projectId);
        String operation = skill.mediaOperation();
        if (operation != null && !MediaJobService.operationAllowedForKind(
                com.zwx.zwxagent.creative.domain.CreativeKind.valueOf(project.kind()), operation)) {
            throw CreativeException.unsupportedParameter(
                    "该创作项目类型（" + project.kind() + "）不支持技能的输出类型，请使用视频项目");
        }

        Map<String, Object> inputSnapshot = new LinkedHashMap<>();
        inputSnapshot.put("slug", skill.slug());
        inputSnapshot.put("prompt", text);
        inputSnapshot.put("bindings", normalizedBindings);

        UUID runId = UUID.randomUUID();
        try {
            Map<String, Object> plan = generatePlan(actor.userId(), version, text, normalizedBindings);
            CreativeSkillRunRecord run = new CreativeSkillRunRecord(runId, projectId, actor.tenantId(),
                    actor.userId(), skill.id(), version.id(), inputSnapshot, plan,
                    CreativeSkillRunRecord.STATUS_PENDING_CONFIRM, null, "", 1,
                    java.time.Instant.now(), java.time.Instant.now());
            repository.insertRun(run);
            return runView(run, skill, null);
        } catch (RuntimeException exception) {
            // 方案失败也要留下运行记录：同 run 重试方案，不静默丢弃（skill 设计 §6.3）
            CreativeSkillRunRecord failed = new CreativeSkillRunRecord(runId, projectId, actor.tenantId(),
                    actor.userId(), skill.id(), version.id(), inputSnapshot, null,
                    CreativeSkillRunRecord.STATUS_FAILED, null, rootMessage(exception), 1,
                    java.time.Instant.now(), java.time.Instant.now());
            repository.insertRun(failed);
            throw exception;
        }
    }

    /** 方案重试（skill 设计 §6.3）：同一 run 增加尝试，不新建 run、不创建媒体任务。 */
    public Map<String, Object> retryPlan(CurrentActor actor, UUID runId) {
        CreativeSkillRunRecord run = ownedRun(actor, runId);
        if (!CreativeSkillRunRecord.STATUS_FAILED.equals(run.planStatus())) {
            throw new CreativeConflictException("只有方案失败的运行可以重试方案");
        }
        CreativeSkillVersionRecord version = repository.findVersion(run.skillId(),
                versionNumberOf(run)).orElseThrow(() -> new IllegalStateException("技能版本缺失"));
        String prompt = String.valueOf(run.inputSnapshot().getOrDefault("prompt", ""));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bindings = (List<Map<String, Object>>) run.inputSnapshot()
                .getOrDefault("bindings", List.of());
        try {
            Map<String, Object> plan = generatePlan(actor.userId(), version, prompt, bindings);
            if (!repository.updateRunPlan(run.id(), run.version(), plan,
                    CreativeSkillRunRecord.STATUS_PENDING_CONFIRM, "")) {
                throw new CreativeConflictException("运行状态已变化，请刷新后重试");
            }
            return runView(ownedRun(actor, runId), skillOf(run), null);
        } catch (RuntimeException exception) {
            repository.updateRunPlan(run.id(), run.version(), null,
                    CreativeSkillRunRecord.STATUS_FAILED, rootMessage(exception));
            throw exception;
        }
    }

    /**
     * 确认执行（skill 设计 §6.1 第 4 步）：校验后经 MediaJobService.create 幂等提交。
     * 幂等键由服务端派生（skill-run:{runId}）：双击/重传/页面恢复复用同一任务，绝不重复计费。
     * 提交后 run 置 CONFIRMED 并记录 media_job_id；生成状态以 MediaJob 为准。
     */
    public Map<String, Object> confirm(CurrentActor actor, UUID runId, String editedPrompt,
                                       Map<String, Object> parameters) {
        CreativeSkillRunRecord run = ownedRun(actor, runId);
        if (!CreativeSkillRunRecord.STATUS_PENDING_CONFIRM.equals(run.planStatus())) {
            throw new CreativeConflictException("该运行已确认或失败，请刷新后查看");
        }
        CreativeSkillRecord skill = skillOf(run);
        String operation = skill.mediaOperation();
        if (operation == null) {
            throw new CreativeConflictException("该技能只产出方案，不直接生成媒体；请将方案文本带入创作台使用");
        }
        String planPrompt = String.valueOf(run.plan() == null ? "" : run.plan().getOrDefault("prompt", ""));
        String effectivePrompt = editedPrompt == null || editedPrompt.isBlank()
                ? planPrompt.trim() : editedPrompt.trim();
        if (effectivePrompt.isBlank()) {
            throw new IllegalArgumentException("缺少生成内容（effectivePrompt）");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bindings = (List<Map<String, Object>>) run.inputSnapshot()
                .getOrDefault("bindings", List.of());
        List<UUID> referenceAssetIds = bindings.stream()
                .filter(binding -> ROLE_FIRST_FRAME.equals(binding.get("role")))
                .map(binding -> UUID.fromString(String.valueOf(binding.get("assetId"))))
                .toList();
        String originalPrompt = String.valueOf(run.inputSnapshot().getOrDefault("prompt", ""));

        var request = new MediaJobService.CreateJobRequest(run.projectId(), operation, null,
                originalPrompt, effectivePrompt, parameters == null ? Map.of() : parameters,
                referenceAssetIds, null, null, null);
        var result = jobService.create(actor, "skill-run:" + run.id(), run.projectId(), request,
                JobSourceType.UI, run.id().toString(), null);
        if (!repository.confirmRun(run.id(), run.version(), result.jobId())) {
            // 并发确认：幂等键保证任务只创建一次；回读现状返回
            run = ownedRun(actor, runId);
        }
        return runView(ownedRun(actor, runId), skill, jobProjection(actor, ownedRun(actor, runId)));
    }

    public Map<String, Object> getRun(CurrentActor actor, UUID runId) {
        CreativeSkillRunRecord run = ownedRun(actor, runId);
        return runView(run, skillOf(run), jobProjection(actor, run));
    }

    // ---------------------------------------------------------------- 方案生成（文本，不调用视觉）

    private Map<String, Object> generatePlan(String userId, CreativeSkillVersionRecord version,
                                             String prompt, List<Map<String, Object>> bindings) {
        String system = version.body();
        StringBuilder user = new StringBuilder(prompt);
        if (!bindings.isEmpty()) {
            user.append("\n\n【参考素材】");
            for (Map<String, Object> binding : bindings) {
                user.append("\n- 角色 ").append(binding.get("role"))
                        .append("（素材由系统在提交时单独传给模型；本方案基于文字信息制定）");
            }
        }
        String raw;
        String planModelName;
        try {
            var selection = catalogService.resolveWithFallback(userId,
                    ModelCatalogService.PURPOSE_OPTIMIZE, ModelCatalogService.PURPOSE_CHAT);
            ChatClient client = ChatClient.builder(chatModelFactory.model(selection.config())).build();
            raw = client.prompt().system(system).user(user.toString()).call().content();
            planModelName = selection.config().modelName();
        } catch (Exception exception) {
            throw CreativeException.optimizeFailed("技能方案生成失败：" + rootMessage(exception));
        }
        Map<String, Object> plan = parsePlan(raw);
        plan.put("planModelName", planModelName);
        return plan;
    }

    /** 结构化输出必须合法（skill 设计 §8.1）：解析失败如实报错，不用纯文本兜底冒充方案。 */
    private Map<String, Object> parsePlan(String raw) {
        if (raw == null) throw CreativeException.optimizeFailed("技能方案不是合法的结构化输出");
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw CreativeException.optimizeFailed("技能方案不是合法的结构化输出");
        }
        try {
            var node = CreativeJson.parse(text.substring(start, end + 1));
            String summary = node.path("summary").asText("").trim();
            String planPrompt = node.path("prompt").asText("").trim();
            if (planPrompt.isBlank()) {
                throw CreativeException.optimizeFailed("技能方案缺少提交文本（prompt）");
            }
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("summary", summary);
            plan.put("prompt", planPrompt);
            plan.put("warnings", CreativeJson.readStringList(
                    CreativeJson.toJson(node.path("warnings"))));
            plan.put("missing", CreativeJson.readStringList(
                    CreativeJson.toJson(node.path("missing"))));
            var shots = node.path("shots");
            if (shots.isArray() && !shots.isEmpty()) {
                plan.put("shots", CreativeJson.fromJson(CreativeJson.toJson(shots),
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() { }));
            }
            return plan;
        } catch (CreativeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw CreativeException.optimizeFailed("技能方案不是合法的结构化输出");
        }
    }

    // ---------------------------------------------------------------- 校验与视图

    private List<Map<String, Object>> normalizeBindings(CurrentActor actor, CreativeSkillRecord skill,
                                                        List<Map<String, Object>> bindings) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        if (bindings == null || bindings.isEmpty()) return normalized;
        if (bindings.size() > MAX_BINDINGS) {
            throw CreativeException.unsupportedParameter("参考素材最多 " + MAX_BINDINGS + " 项");
        }
        boolean needsFirstFrame = "image_to_video".equals(skill.mediaOperation());
        for (Map<String, Object> binding : bindings) {
            String role = String.valueOf(binding.getOrDefault("role", ""));
            if (!KNOWN_ROLES.contains(role)) {
                throw CreativeException.unsupportedParameter("暂不支持的素材角色：" + role);
            }
            UUID assetId;
            try {
                assetId = UUID.fromString(String.valueOf(binding.get("assetId")));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("参考素材 ID 无效");
            }
            CreativeAssetRecord asset = assetRepository.findByIdForOwner(actor.tenantId(), actor.userId(), assetId)
                    .orElseThrow(() -> new IllegalArgumentException("参考素材不存在或无权使用"));
            if (asset.status() != AssetStatus.READY) {
                throw new IllegalArgumentException("参考素材不可用（未就绪或已删除）");
            }
            if (!"IMAGE".equals(asset.mediaType())) {
                throw new IllegalArgumentException("首帧素材必须是图片");
            }
            normalized.add(Map.of("assetId", assetId.toString(), "role", role));
        }
        if (needsFirstFrame && normalized.stream().noneMatch(b -> ROLE_FIRST_FRAME.equals(b.get("role")))) {
            throw CreativeException.unsupportedParameter("该技能需要 1 张首帧图片素材（角色 FIRST_FRAME）");
        }
        if (!needsFirstFrame && !normalized.isEmpty()) {
            throw CreativeException.unsupportedParameter("该技能当前不接受参考素材");
        }
        return normalized;
    }

    private CreativeSkillRunRecord ownedRun(CurrentActor actor, UUID runId) {
        return repository.findRunByIdForOwner(actor.tenantId(), actor.userId(), runId)
                .orElseThrow(() -> new IllegalArgumentException("技能运行不存在"));
    }

    private CreativeSkillRecord skillOf(CreativeSkillRunRecord run) {
        return repository.listPublished().stream()
                .filter(skill -> skill.id().equals(run.skillId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("运行对应的技能不存在或已下架"));
    }

    private int versionNumberOf(CreativeSkillRunRecord run) {
        // MVP 单版本种子运行；引入多版本后此处在 run 上持久化 version 号
        return repository.findVersion(run.skillId(), 1).map(CreativeSkillVersionRecord::version).orElse(1);
    }

    private Map<String, Object> jobProjection(CurrentActor actor, CreativeSkillRunRecord run) {
        if (run.mediaJobId() == null) return null;
        Optional<MediaJobRecord> job = jobRepository.findByIdForOwner(
                actor.tenantId(), actor.userId(), run.mediaJobId());
        if (job.isEmpty()) return null;
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("jobId", job.get().id().toString());
        view.put("status", job.get().status().name());
        view.put("allowedActions", MediaJobService.allowedActions(job.get().status()));
        view.put("error", job.get().errorSummary());
        return view;
    }

    private Map<String, Object> runView(CreativeSkillRunRecord run, CreativeSkillRecord skill,
                                        Map<String, Object> job) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runId", run.id().toString());
        view.put("projectId", run.projectId().toString());
        view.put("slug", skill == null ? null : skill.slug());
        view.put("skillName", skill == null ? null : skill.name());
        view.put("planStatus", run.planStatus());
        view.put("plan", run.plan());
        view.put("inputSnapshot", run.inputSnapshot());
        view.put("failReason", run.failReason());
        view.put("mediaJobId", run.mediaJobId() == null ? null : run.mediaJobId().toString());
        view.put("job", job);
        return view;
    }

    private static String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
