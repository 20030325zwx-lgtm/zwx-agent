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
        for (CreativeSkillRecord skill : repository.listVisibleFor(actor.userId())) {
            items.add(itemView(actor, skill, null));
        }
        return items;
    }

    public Map<String, Object> detail(CurrentActor actor, String slug) {
        CreativeSkillRecord skill = repository.findVisibleBySlug(slug, actor.userId())
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
        view.put("mine", "PRIVATE".equals(skill.visibility()) && actor.userId().equals(skill.ownerUserId()));
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
            return runView(run, skill, null, null);
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
            return runView(ownedRun(actor, runId), skillOf(actor, ownedRun(actor, runId)), null, null);
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
        CreativeSkillRecord skill = skillOf(actor, run);
        String operation = skill.mediaOperation();
        if (operation == null) {
            throw new CreativeConflictException("该技能只产出方案，不直接生成媒体；请将方案文本带入创作台使用");
        }
        if (planShotCount(run) >= 2) {
            throw new CreativeConflictException("该方案包含多个镜头，请使用逐镜生成（每个镜头独立任务）");
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
        CreativeSkillRunRecord latest = ownedRun(actor, runId);
        return runView(latest, skill, jobProjection(actor, latest), shotJobsView(actor, latest));
    }

    public Map<String, Object> getRun(CurrentActor actor, UUID runId) {
        CreativeSkillRunRecord run = ownedRun(actor, runId);
        return runView(run, skillOf(actor, run), jobProjection(actor, run), shotJobsView(actor, run));
    }

    // ---------------------------------------------------------------- 逐镜确认（P1 MVP）

    /**
     * 逐镜生成（skill 设计 §6.2）：方案 shots[] 每镜一个独立 MediaJob。
     * 幂等键 skill-run:{runId}:shot:{i}：重复确认复用同任务，成功镜头绝不重复计费；
     * 失败镜头复用现有 job retry 原位重试。批次上限 20 镜，防一键失控批量。
     */
    public Map<String, Object> confirmShots(CurrentActor actor, UUID runId) {
        CreativeSkillRunRecord run = ownedRun(actor, runId);
        if (!CreativeSkillRunRecord.STATUS_PENDING_CONFIRM.equals(run.planStatus())) {
            throw new CreativeConflictException("该运行已确认或失败，请刷新后查看");
        }
        CreativeSkillRecord skill = skillOf(actor, run);
        if (!skill.requiredCapabilities().contains("TEXT_TO_VIDEO")) {
            throw new CreativeConflictException("该技能不支持逐镜视频生成");
        }
        int shotCount = planShotCount(run);
        if (shotCount == 0) {
            throw new CreativeConflictException("方案中没有可生成的镜头（shots）");
        }
        if (shotCount > MAX_SHOTS) {
            throw CreativeException.unsupportedParameter("单次逐镜生成最多 " + MAX_SHOTS + " 个镜头");
        }
        var shots = (List<Map<String, Object>>) run.plan().get("shots");
        String originalPrompt = String.valueOf(run.inputSnapshot().getOrDefault("prompt", ""));
        Map<Integer, Map<String, Object>> created = new LinkedHashMap<>();
        for (int i = 0; i < shots.size(); i++) {
            Map<String, Object> shot = shots.get(i);
            String shotPrompt = String.valueOf(shot.getOrDefault("prompt", "")).trim();
            if (shotPrompt.isBlank()) continue;
            String title = String.valueOf(shot.getOrDefault("title", "镜头 " + (i + 1)));
            var request = new MediaJobService.CreateJobRequest(run.projectId(), "text_to_video", null,
                    originalPrompt, shotPrompt, java.util.Map.of(),
                    java.util.List.of(), null, null, null);
            var result = jobService.create(actor, "skill-run:" + run.id() + ":shot:" + i,
                    run.projectId(), request, JobSourceType.UI, run.id().toString(), null);
            repository.insertRunJob(run.id(), result.jobId(), i, title);
            created.put(i, java.util.Map.of("jobId", result.jobId().toString(),
                    "reused", result.reused()));
        }
        if (!repository.markRunConfirmed(run.id(), run.version())) {
            // 并发确认：幂等键保证每镜只创建一次；回读现状返回
            run = ownedRun(actor, runId);
        }
        CreativeSkillRunRecord latest = ownedRun(actor, runId);
        return runView(latest, skill, jobProjection(actor, latest), shotJobsView(actor, latest));
    }

    @SuppressWarnings("unchecked")
    private int planShotCount(CreativeSkillRunRecord run) {
        Object shots = run.plan() == null ? null : run.plan().get("shots");
        return shots instanceof List<?> list ? list.size() : 0;
    }

    private Map<String, Object> shotJobsView(CurrentActor actor, CreativeSkillRunRecord run) {
        List<Map<String, Object>> rows = repository.listRunJobs(run.id());
        if (rows.isEmpty()) return null;
        List<Map<String, Object>> jobs = rows.stream().map(row -> {
            Map<String, Object> view = new LinkedHashMap<>(row);
            Optional<MediaJobRecord> job = jobRepository.findByIdForOwner(
                    actor.tenantId(), actor.userId(), UUID.fromString((String) row.get("jobId")));
            view.put("status", job.map(value -> value.status().name()).orElse("UNKNOWN"));
            view.put("allowedActions", job.map(value -> MediaJobService.allowedActions(value.status()))
                    .orElse(List.of()));
            return view;
        }).toList();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("items", jobs);
        return view;
    }

    // ---------------------------------------------------------------- 导入（P0-C MVP，Markdown）

    public static final int IMPORT_BODY_MAX = 60_000;
    private static final int MAX_SHOTS = 20;

    /** 导入预检：只解析产生预览与草稿，不进入系统目录（skill 设计 §5.4 先解析后保存）。 */
    public Map<String, Object> previewImport(CurrentActor actor, String markdown, String sourceRef) {
        ParsedImport parsed = parseImport(markdown);
        String hash = CreativeSkillContent.hashOf(markdown == null ? "" : markdown);
        var existing = repository.findImportByHashForOwner(actor.tenantId(), actor.userId(), hash);
        if (existing.isPresent()) return importPreview(actor, existing.get(), parsed);
        UUID importId = UUID.randomUUID();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("name", parsed.name());
        record.put("description", parsed.description());
        record.put("category", parsed.category());
        record.put("stage", parsed.stage());
        record.put("bodyLength", parsed.body().length());
        record.put("body", parsed.body()); // 正文随预检存入（≤60KB），确认时取用，避免双份存储
        repository.insertImport(importId, actor.tenantId(), actor.userId(), "MARKDOWN",
                sourceRef == null ? "" : sourceRef, hash, record);
        var row = repository.findImportByIdForOwner(actor.tenantId(), actor.userId(), importId)
                .orElseThrow(() -> new IllegalStateException("导入记录写入失败"));
        return importPreview(actor, row, parsed);
    }

    private Map<String, Object> importPreview(CurrentActor actor, Map<String, Object> row, ParsedImport parsed) {
        List<String> issues = new ArrayList<>();
        if (parsed.name().isBlank()) issues.add("缺少 name（front-matter）");
        if (parsed.description().isBlank()) issues.add("缺少 description（front-matter）");
        if (parsed.body().length() > IMPORT_BODY_MAX) {
            issues.add("正文超过 " + IMPORT_BODY_MAX + " 字上限");
        }
        if (!issues.isEmpty() && parsed.name().isBlank()) {
            issues.add("修复 front-matter 后重新导入");
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("importId", row.get("id"));
        view.put("status", row.get("status"));
        view.put("sourceType", row.get("sourceType"));
        view.put("rawHash", row.get("rawHash"));
        view.put("name", parsed.name());
        view.put("description", parsed.description());
        view.put("category", parsed.category());
        view.put("stage", parsed.stage());
        view.put("bodyPreview", parsed.body().isBlank() ? "" : parsed.body().substring(0, Math.min(400, parsed.body().length())));
        view.put("bodyLength", parsed.body().length());
        // 兼容报告（skill 设计 §5.4）：外部内容授权未核实，保存后默认私有（license PENDING）
        view.put("issues", issues);
        view.put("compat", issues.stream().anyMatch(item -> item.contains("缺少")) ? "PARSE_ISSUES"
                : issues.isEmpty() ? "READY" : "LIMITED");
        view.put("licenseStatus", "PENDING");
        return view;
    }

    /** 确认导入：保存为本人私有草稿技能并发布给本人；同 hash 重复导入幂等返回已有记录。 */
    public Map<String, Object> confirmImport(CurrentActor actor, UUID importId, String requestedSlug) {
        var row = repository.findImportByIdForOwner(actor.tenantId(), actor.userId(), importId)
                .orElseThrow(() -> new IllegalArgumentException("导入记录不存在"));
        if (!"PARSED".equals(row.get("status"))) {
            throw new CreativeConflictException("该导入已确认，请刷新查看技能");
        }
        Map<String, Object> parsed = (Map<String, Object>) row.get("parsed");
        String name = String.valueOf(parsed.getOrDefault("name", "")).trim();
        if (name.isBlank()) throw new IllegalArgumentException("导入内容缺少 name，无法保存");
        String slug = normalizeSlug(requestedSlug != null && !requestedSlug.isBlank()
                ? requestedSlug : name);
        if (repository.slugTaken(slug)) {
            throw new CreativeConflictException("技能标识 " + slug + " 已存在，请换一个标识");
        }
        // 正文取自预检记录（≤60KB）；导入后原文以确认时的快照为准
        String body = String.valueOf(parsed.getOrDefault("body", ""));
        if (body.isBlank()) throw new IllegalArgumentException("导入内容正文为空，无法保存");
        UUID skillId = UUID.randomUUID();
        repository.insertSkill(new CreativeSkillRecord(skillId, slug, name,
                String.valueOf(parsed.getOrDefault("description", "")),
                String.valueOf(parsed.getOrDefault("category", "GENERAL")),
                String.valueOf(parsed.getOrDefault("stage", "PROMPT")),
                "TEXT", "TEXT", List.of("CHAT"), "PRIVATE", actor.userId(), "PUBLISHED", 1));
        repository.insertVersion(new CreativeSkillVersionRecord(UUID.randomUUID(), skillId, 1, body,
                java.util.Map.of(), java.util.Map.of(),
                String.valueOf(row.getOrDefault("sourceType", "MARKDOWN")),
                String.valueOf(row.getOrDefault("sourceRef", "")),
                String.valueOf(row.getOrDefault("rawHash", "")),
                "IMPORTED", "PENDING", java.time.Instant.now()));
        if (!repository.confirmImport(importId, actor.tenantId(), actor.userId(), skillId)) {
            throw new CreativeConflictException("导入状态已变化，请刷新后重试");
        }
        var skill = repository.findVisibleBySlug(slug, actor.userId()).orElseThrow();
        return itemView(actor, skill, null);
    }

    private ParsedImport parseImport(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("请提供 Markdown 技能内容");
        }
        List<String> lines = markdown.lines().map(String::trim).toList();
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isEmpty()) { start = i; break; }
        }
        if (start < 0 || !"---".equals(lines.get(start))) {
            throw new IllegalArgumentException("缺少 front-matter 起始标记 ---");
        }
        Map<String, String> meta = new LinkedHashMap<>();
        int index = start + 1;
        while (index < lines.size() && !"---".equals(lines.get(index))) {
            String line = lines.get(index);
            int sep = line.indexOf(':');
            if (sep > 0) meta.put(line.substring(0, sep).trim().toLowerCase(java.util.Locale.ROOT),
                    stripQuotes(line.substring(sep + 1).trim()));
            index++;
        }
        if (index >= lines.size()) throw new IllegalArgumentException("缺少 front-matter 结束标记 ---");
        StringBuilder body = new StringBuilder();
        for (index++; index < lines.size(); index++) {
            if (body.length() > 0) body.append('\n');
            body.append(lines.get(index));
        }
        String bodyText = body.toString().trim();
        if (bodyText.length() > IMPORT_BODY_MAX) {
            throw CreativeException.unsupportedParameter("正文超过 " + IMPORT_BODY_MAX + " 字上限");
        }
        return new ParsedImport(meta.getOrDefault("name", ""), meta.getOrDefault("description", ""),
                meta.getOrDefault("category", "GENERAL").toUpperCase(java.util.Locale.ROOT),
                meta.getOrDefault("stage", "PROMPT").toUpperCase(java.util.Locale.ROOT), bodyText);
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private static String normalizeSlug(String value) {
        String slug = value.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isBlank()) throw new IllegalArgumentException("技能标识无效");
        return slug.length() > 60 ? slug.substring(0, 60) : slug;
    }

    record ParsedImport(String name, String description, String category, String stage, String body) {
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

    private CreativeSkillRecord skillOf(CurrentActor actor, CreativeSkillRunRecord run) {
        return repository.listVisibleFor(actor.userId()).stream()
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
                                        Map<String, Object> job, Map<String, Object> shotJobs) {
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
        view.put("shotJobs", shotJobs);
        return view;
    }

    private static String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
