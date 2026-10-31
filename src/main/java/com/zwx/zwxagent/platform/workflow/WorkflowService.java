package com.zwx.zwxagent.platform.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwx.zwxagent.knowledge.image.ImageAnalysisService;
import com.zwx.zwxagent.capability.mcp.McpServerConfigurationService;
import com.zwx.zwxagent.capability.mcp.McpTools;
import com.zwx.zwxagent.capability.tools.UrlAccessPolicy;
import com.zwx.zwxagent.capability.tools.WebSearchTool;
import com.zwx.zwxagent.knowledge.storage.OssClientProvider;
import com.zwx.zwxagent.platform.settings.DynamicChatModelFactory;
import com.zwx.zwxagent.platform.settings.ModelCatalogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 原生工作流引擎：定义版本（DB 图结构：节点+连线）+ 按拓扑序执行。
 * 节点类型：search_image / image_understand / llm_text / web_search / poster_render。
 * 节点 params 支持 "{{input.字段}}" 与 "{{节点key.output}}" 引用，执行时替换。
 * 画布编辑保存为新版本；运行绑定最新版本。
 */
@Service
@Slf4j
public class WorkflowService {

    public static final Set<String> NODE_TYPES = Set.of(
            "start", "search_image", "image_understand", "llm_text", "web_search", "poster_render",
            "image_generate", "media_image_generate", "media_image_edit",
            "http_request", "db_query", "text_template", "file_export",
            "novel_context", "novel_chapter_save");

    public record StepDef(String key, String name, String description) {
    }

    public record WorkflowDefinition(String key, String name, String description, int version,
                                     List<StepDef> steps) {
    }

    public record GraphNodeDef(String key, String type, String name, Map<String, Object> params,
                               Double x, Double y) {
        public GraphNodeDef(String key, String type, String name, Map<String, Object> params) {
            this(key, type, name, params, null, null);
        }
    }

    public record GraphEdgeDef(String from, String to) {
    }

    public record WorkflowGraph(List<GraphNodeDef> nodes, List<GraphEdgeDef> edges) {
    }

    public record DefinitionVersionView(int version, String name, String description, WorkflowGraph graph,
                                        Instant createdAt) {
    }

    public record StepState(String key, String name, String status, String message, String output,
                            Instant startedAt, Instant finishedAt) {
    }

    public record Artifact(String type, String name, String objectKey, String assetId) {
        public Artifact(String type, String name, String objectKey) {
            this(type, name, objectKey, null);
        }
    }

    public record WorkflowRunView(String id, String workflowKey, String workflowName, int definitionVersion,
                                  String status, String currentStep, Map<String, Object> input,
                                  List<StepState> steps, List<ArtifactView> artifacts, String error,
                                  Instant createdAt, Instant updatedAt, UUID creativeProjectId) {
    }

    public record ArtifactView(String type, String name, String url, String assetId) {
    }

    public record SaveVersionRequest(String name, String description, WorkflowGraph graph) {
    }

    public record StartRequest(String keyword, Map<String, Object> input, UUID projectId) {

        public StartRequest(String keyword, Map<String, Object> input) {
            this(keyword, input, null);
        }
    }

    private record DefVersionRow(int version, String name, String description, WorkflowGraph graph, Instant createdAt) {
    }

    private record WorkflowRunRow(String id, String tenantId, String userId, String workflowKey, int definitionVersion,
                                  String status, String currentStep, JsonNode input, JsonNode steps,
                                  JsonNode artifacts, String error, Instant createdAt, Instant updatedAt,
                                  JsonNode graphSnapshot, UUID creativeProjectId) {
    }

    private record RunContext(String id, String tenantId, String userId, String workflowKey,
                              int definitionVersion, String name, String description, WorkflowGraph graph,
                              Map<String, Object> input, List<StepState> steps, List<Artifact> artifacts,
                              UUID creativeProjectId) {
    }

    private record NodeResult(String output, String message) {
        static NodeResult waiting(String message) {
            return new NodeResult(null, message);
        }

        boolean isWaiting() {
            return output == null;
        }
    }

    /** 外部等待：节点提交了媒体任务，运行进入 WAITING_EXTERNAL，由恢复器继续。 */
    static class WorkflowWaitException extends RuntimeException {
        WorkflowWaitException(String message) {
            super(message);
        }
    }

    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    // steps JSONB 持久化上限：放宽到 100k 以承载长文本节点（小说章节正文约 2000-8000 字），
    // 续跑时输出从 steps 重载，截断过小会让下游引用拿到残缺文本（评审 R06 的输出截断项）。
    // 运行时间线 UI 只展示 message，不渲染全文。
    private static final int MAX_OUTPUT_CHARS = 100_000;
    private static final Pattern REF_PATTERN = Pattern.compile("\\{\\{([a-zA-Z0-9_.]+)}}");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final McpServerConfigurationService mcpServerConfigurationService;
    private final ImageAnalysisService imageAnalysisService;
    private final ModelCatalogService modelCatalogService;
    private final DynamicChatModelFactory chatModelFactory;
    private final UrlAccessPolicy urlAccessPolicy;
    private final OssClientProvider ossClientProvider;
    private final String ossBucket;
    private final String searchProvider;
    private final String searchApiKey;
    private final boolean allowLocalEndpoints;
    private final com.zwx.zwxagent.creative.service.MediaJobService mediaJobService;
    private final com.zwx.zwxagent.creative.service.CreativeProjectService creativeProjectService;
    private final com.zwx.zwxagent.creative.asset.CreativeAssetService creativeAssetService;
    private final com.zwx.zwxagent.creative.repository.CreativeAssetRepository creativeAssetRepository;
    private final com.zwx.zwxagent.creative.storage.MediaStorage mediaStorage;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final com.zwx.zwxagent.creative.novel.NovelChapterService novelChapterService;
    private final com.zwx.zwxagent.creative.repository.NovelChapterRepository novelChapterRepository;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "workflow-runner");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public WorkflowService(JdbcTemplate jdbcTemplate,
                           McpServerConfigurationService mcpServerConfigurationService,
                           ImageAnalysisService imageAnalysisService,
                           ModelCatalogService modelCatalogService,
                           DynamicChatModelFactory chatModelFactory,
                           UrlAccessPolicy urlAccessPolicy,
                           OssClientProvider ossClientProvider,
                           @Value("${app.oss.bucket}") String ossBucket,
                           @Value("${search-api.provider:baidu}") String searchProvider,
                           @Value("${search-api.api-key:}") String searchApiKey,
                           @Value("${app.config.allow-local-model-endpoints:false}") boolean allowLocalEndpoints,
                           com.zwx.zwxagent.creative.service.MediaJobService mediaJobService,
                           com.zwx.zwxagent.creative.service.CreativeProjectService creativeProjectService,
                           com.zwx.zwxagent.creative.asset.CreativeAssetService creativeAssetService,
                           com.zwx.zwxagent.creative.repository.CreativeAssetRepository creativeAssetRepository,
                           com.zwx.zwxagent.creative.storage.MediaStorage mediaStorage,
                           org.springframework.transaction.support.TransactionTemplate transactionTemplate,
                           com.zwx.zwxagent.creative.novel.NovelChapterService novelChapterService,
                           com.zwx.zwxagent.creative.repository.NovelChapterRepository novelChapterRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.mcpServerConfigurationService = mcpServerConfigurationService;
        this.imageAnalysisService = imageAnalysisService;
        this.modelCatalogService = modelCatalogService;
        this.chatModelFactory = chatModelFactory;
        this.urlAccessPolicy = urlAccessPolicy;
        this.ossClientProvider = ossClientProvider;
        this.ossBucket = ossBucket;
        this.searchProvider = searchProvider;
        this.searchApiKey = searchApiKey;
        this.allowLocalEndpoints = allowLocalEndpoints;
        this.mediaJobService = mediaJobService;
        this.creativeProjectService = creativeProjectService;
        this.creativeAssetService = creativeAssetService;
        this.creativeAssetRepository = creativeAssetRepository;
        this.mediaStorage = mediaStorage;
        this.transactionTemplate = transactionTemplate;
        this.novelChapterService = novelChapterService;
        this.novelChapterRepository = novelChapterRepository;
    }

    // ---------------------------------------------------------------- 定义版本

    public List<WorkflowDefinition> definitions(String tenantId) {
        List<WorkflowDefinition> result = new ArrayList<>();
        jdbcTemplate.query("""
                        SELECT DISTINCT ON (workflow_key) workflow_key, version, name, description, graph
                        FROM workflow_definition_version WHERE tenant_id = ?
                        ORDER BY workflow_key, version DESC
                        """, rs -> {
                    WorkflowGraph graph = parseGraph(rs.getString("graph"));
                    List<StepDef> steps = graph.nodes().stream()
                            .map(node -> new StepDef(node.key(), node.name(), node.type())).toList();
                    result.add(new WorkflowDefinition(rs.getString("workflow_key"), rs.getString("name"),
                            rs.getString("description"), rs.getInt("version"), steps));
                }, tenantId);
        return result;
    }

    public DefVersionRow latestVersion(String tenantId, String workflowKey) {
        List<DefVersionRow> rows = jdbcTemplate.query("""
                        SELECT version, name, description, graph, created_at
                        FROM workflow_definition_version
                        WHERE tenant_id = ? AND workflow_key = ?
                        ORDER BY version DESC LIMIT 1
                        """,
                (rs, rowNum) -> new DefVersionRow(rs.getInt("version"), rs.getString("name"),
                        rs.getString("description"), parseGraph(rs.getString("graph")),
                        rs.getTimestamp("created_at").toInstant()),
                tenantId, workflowKey);
        if (rows.isEmpty()) throw new IllegalArgumentException("未知的工作流：" + workflowKey);
        return rows.getFirst();
    }

    @SuppressWarnings("unchecked")
    private WorkflowGraph parseGraph(String json) {
        try {
            JsonNode node = objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
            List<GraphNodeDef> nodes = new ArrayList<>();
            node.path("nodes").forEach(n -> nodes.add(new GraphNodeDef(
                    n.path("key").asText(), n.path("type").asText(), n.path("name").asText(),
                    objectMapper.convertValue(n.path("params"), Map.class),
                    n.hasNonNull("x") ? n.path("x").asDouble() : null,
                    n.hasNonNull("y") ? n.path("y").asDouble() : null)));
            List<GraphEdgeDef> edges = new ArrayList<>();
            node.path("edges").forEach(e -> edges.add(new GraphEdgeDef(
                    e.path("from").asText(), e.path("to").asText())));
            return new WorkflowGraph(nodes, edges);
        } catch (Exception exception) {
            return new WorkflowGraph(List.of(), List.of());
        }
    }

    public record CreateWorkflowRequest(String key, String name, String description, WorkflowGraph graph) {
    }

    /** 新建工作流：写入 v1 定义；未提供图时给一个单节点起始模板。 */
    public DefinitionVersionView createWorkflow(String tenantId, String userId, CreateWorkflowRequest request) {
        String key = request.key() == null ? "" : request.key().trim().toLowerCase(Locale.ROOT);
        if (!key.matches("[a-z0-9][a-z0-9-]{1,48}")) {
            throw new IllegalArgumentException("工作流标识只能使用小写字母、数字与连字符（2-49 位）");
        }
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > 64) throw new IllegalArgumentException("请填写工作流名称（不超过 64 字）");
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM workflow_definition_version WHERE tenant_id = ? AND workflow_key = ?",
                Integer.class, tenantId, key);
        if (existing != null && existing > 0) throw new IllegalArgumentException("工作流标识已存在：" + key);
        WorkflowGraph graph = request.graph();
        if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
            graph = new WorkflowGraph(List.of(new GraphNodeDef("start", "start", "开始", Map.of())), List.of());
        }
        validateGraph(graph);
        String description = request.description() == null ? "" : request.description().trim();
        jdbcTemplate.update("""
                        INSERT INTO workflow_definition_version (tenant_id, workflow_key, version, name, description, graph, created_by)
                        VALUES (?, ?, 1, ?, ?, ?::jsonb, ?)
                        """,
                tenantId, key, name, description, toJson(graph), userId);
        return new DefinitionVersionView(1, name, description, graph, Instant.now());
    }

    /** 删除工作流：连带删除其定义版本与全部运行记录（前端需二次确认）。 */
    public void deleteWorkflow(String tenantId, String workflowKey) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM workflow_definition_version WHERE tenant_id = ? AND workflow_key = ?",
                Integer.class, tenantId, workflowKey);
        if (count == null || count == 0) throw new IllegalArgumentException("工作流不存在：" + workflowKey);
        jdbcTemplate.update("DELETE FROM workflow_run WHERE tenant_id = ? AND workflow_key = ?",
                tenantId, workflowKey);
        jdbcTemplate.update("DELETE FROM workflow_definition_version WHERE tenant_id = ? AND workflow_key = ?",
                tenantId, workflowKey);
    }

    public DefinitionVersionView latestVersionView(String tenantId, String workflowKey) {
        DefVersionRow row = latestVersion(tenantId, workflowKey);
        return new DefinitionVersionView(row.version(), row.name(), row.description(), row.graph(), row.createdAt());
    }

    public DefinitionVersionView saveVersion(String tenantId, String userId, String workflowKey,
                                             SaveVersionRequest request) {
        WorkflowGraph graph = request.graph();
        validateGraph(graph);
        int nextVersion;
        String lastName;
        String lastDescription;
        try {
            DefVersionRow latest = latestVersion(tenantId, workflowKey);
            nextVersion = latest.version() + 1;
            lastName = latest.name();
            lastDescription = latest.description();
        } catch (IllegalArgumentException exception) {
            nextVersion = 1;
            lastName = workflowKey;
            lastDescription = "";
        }
        String name = request.name() == null || request.name().isBlank() ? lastName : request.name().trim();
        String description = request.description() == null ? lastDescription : request.description().trim();
        jdbcTemplate.update("""
                        INSERT INTO workflow_definition_version (tenant_id, workflow_key, version, name, description, graph, created_by)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                        """,
                tenantId, workflowKey, nextVersion, name, description, toJson(graph), userId);
        return new DefinitionVersionView(nextVersion, name, description, graph, Instant.now());
    }

    /**
     * 节点必填参数契约（Infinite Canvas 评审第一批 A2）：
     * 外层列表各约束须同时满足（AND）；内层列表为备选参数（OR，任一非空白即满足）。
     * {{引用}} 表达式算已填——解析正确性交给运行时；这里拦截"缺参数的图跑到一半才失败"。
     */
    private static final Map<String, List<List<String>>> REQUIRED_PARAMS = Map.ofEntries(
            Map.entry("search_image", List.of(List.of("keyword"))),
            Map.entry("image_understand", List.of(List.of("image", "imageAssetId"))),
            Map.entry("llm_text", List.of(List.of("prompt"))),
            Map.entry("web_search", List.of(List.of("query"))),
            Map.entry("image_generate", List.of(List.of("prompt"))),
            Map.entry("media_image_generate", List.of(List.of("prompt"))),
            Map.entry("media_image_edit", List.of(List.of("prompt"), List.of("imageAssetIds"))),
            Map.entry("http_request", List.of(List.of("url"))),
            Map.entry("db_query", List.of(List.of("sql"))),
            Map.entry("text_template", List.of(List.of("template"))),
            Map.entry("file_export", List.of(List.of("content"))),
            Map.entry("poster_render", List.of(List.of("image", "imageAssetId"))),
            Map.entry("novel_chapter_save", List.of(List.of("content"))));

    /** 纯函数（便于单测）：返回缺失说明，null = 满足契约。 */
    static String missingRequired(String type, Map<String, Object> params) {
        List<List<String>> constraints = REQUIRED_PARAMS.get(type);
        if (constraints == null) return null;
        for (List<String> alternatives : constraints) {
            boolean satisfied = false;
            for (String key : alternatives) {
                Object value = params == null ? null : params.get(key);
                if (value != null && !(value instanceof String text && text.isBlank())) {
                    satisfied = true;
                    break;
                }
            }
            if (!satisfied) {
                return String.join("；", constraints.stream()
                        .map(group -> String.join(" 或 ", group)).toList())
                        + "（缺 " + String.join(" 或 ", alternatives) + "）";
            }
        }
        return null;
    }

    private void validateGraph(WorkflowGraph graph) {
        if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
            throw new IllegalArgumentException("画布至少需要一个节点");
        }
        Set<String> keys = new HashSet<>();
        for (GraphNodeDef node : graph.nodes()) {
            if (node.key() == null || node.key().isBlank()) throw new IllegalArgumentException("节点 key 不能为空");
            if (!keys.add(node.key())) throw new IllegalArgumentException("节点 key 重复：" + node.key());
            if (!NODE_TYPES.contains(node.type())) throw new IllegalArgumentException("未知节点类型：" + node.type());
            if (node.name() == null || node.name().isBlank()) throw new IllegalArgumentException("节点名称不能为空");
            String missing = missingRequired(node.type(), node.params());
            if (missing != null) {
                throw new IllegalArgumentException(
                        "节点「" + node.name() + "(" + node.key() + ")」缺少必填参数：" + missing);
            }
        }
        if (graph.edges() != null) {
            for (GraphEdgeDef edge : graph.edges()) {
                if (!keys.contains(edge.from()) || !keys.contains(edge.to())) {
                    throw new IllegalArgumentException("连线引用了不存在的节点");
                }
            }
        }
        // 开始节点：最多一个，且不能有入边
        long startCount = graph.nodes().stream().filter(node -> "start".equals(node.type())).count();
        if (startCount > 1) throw new IllegalArgumentException("只能有一个开始节点");
        if (startCount == 1 && graph.edges() != null) {
            String startKey = graph.nodes().stream().filter(node -> "start".equals(node.type()))
                    .findFirst().get().key();
            boolean hasIncoming = graph.edges().stream().anyMatch(edge -> edge.to().equals(startKey));
            if (hasIncoming) throw new IllegalArgumentException("开始节点不能有上游连线");
        }
        topologicalOrder(graph); // 有环时抛错
    }

    private List<GraphNodeDef> topologicalOrder(WorkflowGraph graph) {
        Map<String, GraphNodeDef> byKey = new LinkedHashMap<>();
        graph.nodes().forEach(node -> byKey.put(node.key(), node));
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        graph.nodes().forEach(node -> indegree.putIfAbsent(node.key(), 0));
        if (graph.edges() != null) {
            for (GraphEdgeDef edge : graph.edges()) {
                outgoing.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge.to());
                indegree.merge(edge.to(), 1, Integer::sum);
            }
        }
        List<GraphNodeDef> order = new ArrayList<>();
        List<String> ready = graph.nodes().stream()
                .filter(node -> indegree.get(node.key()) == 0)
                .map(GraphNodeDef::key).sorted().toList();
        while (!ready.isEmpty()) {
            List<String> next = new ArrayList<>();
            for (String key : ready) {
                order.add(byKey.get(key));
                for (String to : outgoing.getOrDefault(key, List.of())) {
                    if (indegree.merge(to, -1, Integer::sum) == 0) next.add(to);
                }
            }
            ready = next;
        }
        if (order.size() != graph.nodes().size()) throw new IllegalArgumentException("画布存在循环依赖");
        return order;
    }

    // ---------------------------------------------------------------- 运行

    public WorkflowRunView start(String tenantId, String userId, String workflowKey, StartRequest request) {
        DefVersionRow definition = latestVersion(tenantId, workflowKey);
        Map<String, Object> input = new LinkedHashMap<>();
        if (request != null && request.input() != null) input.putAll(request.input());
        String keyword = request == null ? "" : (request.keyword() == null ? "" : request.keyword().trim());
        if (!keyword.isEmpty()) input.put("keyword", keyword);
        if (input.isEmpty()) throw new IllegalArgumentException("请填写运行输入");
        if (definition.graph().nodes().isEmpty()) throw new IllegalArgumentException("该工作流没有可执行的节点");
        validateGraph(definition.graph()); // 运行提交前独立拒绝非法图（含必填参数预检）
        // 运行可绑定创作项目（工作流作为 AI 创作的一部分）：校验归属后落库，
        // 媒体节点产物归入该项目素材库，项目详情页可按项目筛选运行
        UUID creativeProjectId = request == null ? null : request.projectId();
        if (creativeProjectId != null) {
            var actor = new com.zwx.zwxagent.platform.security.CurrentActor(
                    userId, tenantId, userId, com.zwx.zwxagent.platform.security.Role.USER);
            creativeProjectService.get(actor, creativeProjectId);
        }

        String id = UUID.randomUUID().toString();
        List<StepState> steps = new ArrayList<>(definition.graph().nodes().stream()
                .map(node -> new StepState(node.key(), node.name(), "PENDING", "", "", null, null))
                .toList());
        jdbcTemplate.update("""
                        INSERT INTO workflow_run (id, tenant_id, user_id, workflow_key, definition_version,
                                                  status, current_step, input, steps, artifacts, graph_snapshot,
                                                  creative_project_id)
                        VALUES (?, ?, ?, ?, ?, 'RUNNING', ?, ?::jsonb, ?::jsonb, '[]'::jsonb, ?::jsonb, ?)
                        """,
                id, tenantId, userId, workflowKey, definition.version(),
                definition.graph().nodes().getFirst().key(),
                toJson(input), toJson(steps), toJson(definition.graph()), creativeProjectId);
        RunContext context = new RunContext(id, tenantId, userId, workflowKey, definition.version(),
                definition.name(), definition.description(), definition.graph(), input, steps, new ArrayList<>(),
                creativeProjectId);
        executor.submit(() -> execute(context));
        return view(id, tenantId, userId);
    }

    private void execute(RunContext context) {
        try {
            // 续跑：已完成节点的输出直接复用，不重复执行有副作用的节点
            Map<String, String> outputs = new LinkedHashMap<>();
            for (StepState step : context.steps()) {
                if ("SUCCEEDED".equals(step.status()) && step.output() != null && !step.output().isBlank()) {
                    outputs.put(step.key(), step.output());
                }
            }
            for (GraphNodeDef node : topologicalOrder(context.graph())) {
                StepState existing = context.steps().stream()
                        .filter(step -> step.key().equals(node.key())).findFirst().orElse(null);
                if (existing != null && ("SUCCEEDED".equals(existing.status())
                        || "SKIPPED".equals(existing.status()) || "FAILED".equals(existing.status()))) {
                    // FAILED：失败范围语义下不阻断运行的分支失败，续跑时不自动重跑（重试走任务级操作）
                    continue;
                }
                StepState running = new StepState(node.key(), node.name(), "RUNNING", "", "",
                        Instant.now(), null);
                updateStep(context, running);
                try {
                    Map<String, Object> params = resolveParams(node.params(), context.input(), outputs);
                    NodeResult result = executeNode(context, node, params);
                    if (result.isWaiting()) {
                        // 外部等待：让出执行线程，由恢复器按媒体任务结果继续
                        updateStep(context, new StepState(node.key(), node.name(), "RUNNING",
                                result.message(), "", running.startedAt(), null));
                        markWaiting(context.id(), node.key());
                        return;
                    }
                    outputs.put(node.key(), result.output());
                    updateStep(context, new StepState(node.key(), node.name(), "SUCCEEDED",
                            result.message() == null ? "" : result.message(),
                            truncate(result.output(), MAX_OUTPUT_CHARS), running.startedAt(), Instant.now()));
                } catch (Exception exception) {
                    log.warn("工作流节点失败：runId={} node={}", context.id(), node.key(), exception);
                    updateStep(context, new StepState(node.key(), node.name(), "FAILED",
                            truncate(exception.getMessage(), 300), "", running.startedAt(), Instant.now()));
                    throw exception;
                }
            }
            finishRun(context.id(), "SUCCEEDED", context.artifacts(), null);
        } catch (Exception exception) {
            log.warn("工作流运行失败：runId={}", context.id(), exception);
            markPendingSkipped(context);
            finishRun(context.id(), "FAILED", context.artifacts(), truncate(exception.getMessage(), 400));
        }
    }

    /** 运行进入外部等待；只有 RUNNING 能写入，避免与恢复器竞争。 */
    private void markWaiting(String runId, String nodeKey) {
        jdbcTemplate.update("""
                UPDATE workflow_run SET status = 'WAITING_EXTERNAL', current_step = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'RUNNING'
                """, nodeKey, runId);
    }

    private NodeResult executeNode(RunContext context, GraphNodeDef node, Map<String, Object> params)
            throws Exception {
        String type = node.type();
        return switch (type) {
            case "search_image" -> searchProductImage(context.tenantId(), text(params.get("keyword")));
            case "image_understand" -> understandImage(context, text(params.get("image")),
                    text(params.get("imageAssetId")), text(params.get("question")));
            case "llm_text" -> {
                var selection = modelCatalogService.resolve(context.userId(), ModelCatalogService.PURPOSE_CHAT);
                ChatClient client = ChatClient.builder(chatModelFactory.model(selection.config())).build();
                String system = textOrDefault(text(params.get("system")), "");
                String answer = system.isEmpty()
                        ? client.prompt().user(text(params.get("prompt"))).call().content()
                        : client.prompt().system(system).user(text(params.get("prompt"))).call().content();
                yield new NodeResult(answer == null ? "" : answer, "文本已生成");
            }
            case "web_search" -> webSearch(text(params.get("query")));
            case "poster_render" -> renderPoster(context, node.key(), text(params.get("image")),
                    text(params.get("imageAssetId")), text(params.get("copy")));
            case "start" -> new NodeResult(toJson(context.input()), "开始");
            case "http_request" -> httpRequest(context, params);
            case "db_query" -> dbQuery(text(params.get("sql")));
            case "media_image_generate" -> startMediaImageTask(context, node, params);
            case "media_image_edit" -> startMediaImageTask(context, node, params);
            case "novel_context" -> novelContext(context, params);
            case "novel_chapter_save" -> novelChapterSave(context, params);
            case "image_generate" -> imageGenerate(context, text(params.get("prompt")),
                    textOrDefault(text(params.get("size")), "1024*1024"),
                    textOrDefault(text(params.get("model")), "wanx2.1-t2i-turbo"));
            case "text_template" -> new NodeResult(text(params.get("template")), "文本已封装");
            case "file_export" -> fileExport(context, textOrDefault(text(params.get("filename")), "output.md"),
                    text(params.get("content")));
            default -> throw new IllegalArgumentException("未知节点类型：" + type);
        };
    }

    private NodeResult searchProductImage(String tenantId, String keyword) throws Exception {
        if (keyword == null || keyword.isBlank()) throw new IllegalArgumentException("搜索关键词为空");
        String mcpFailure = "MCP 搜索不可用";
        try (McpTools tools = mcpServerConfigurationService.toolsFor(tenantId)) {
            if (tools.callbacks().length > 0) {
                ToolCallback callback = Arrays.stream(tools.callbacks())
                        .filter(candidate -> candidate.getToolDefinition().name().endsWith("searchImage"))
                        .findFirst().orElse(null);
                if (callback != null) {
                    String raw = callback.call(objectMapper.writeValueAsString(Map.of("query", keyword)));
                    for (String candidate : extractUrls(raw)) {
                        if (isProbableImage(candidate)) {
                            return new NodeResult(candidate, "MCP 搜索已获取商品图");
                        }
                    }
                    mcpFailure = "MCP 搜索无可用结果（如未配置搜索服务密钥）";
                }
            }
        }
        // 回退：Wikimedia Commons 开放检索（无需密钥，内容以英文为主，中文关键词先译英）
        List<String> commons = searchCommons(keyword);
        if (commons.isEmpty()) {
            String english = translateKeyword(keyword);
            if (!english.isEmpty()) commons = searchCommons(english);
        }
        for (String candidate : commons) {
            if (isProbableImage(candidate)) return new NodeResult(candidate, "Commons 检索已获取商品图");
        }
        throw new IllegalStateException("图片搜索没有返回结果（" + mcpFailure + "，Commons 无结果），请更换关键词重试");
    }

    /**
     * 图片理解：联网图片与 OSS 素材走 MCP URL；本地存储素材改走视觉模型（字节直传），
     * 使桌面端不依赖公网可达地址。两条路径失败都保留原有降级行为。
     */
    private NodeResult understandImage(RunContext context, String image, String imageAssetId, String question) {
        String assetId = resolveAssetId(image, imageAssetId);
        if (assetId == null) {
            return understandImageViaMcp(context.tenantId(), image, question);
        }
        var asset = requireAsset(context, assetId);
        if (asset.storageBackend() == com.zwx.zwxagent.creative.domain.StorageBackend.OSS) {
            return understandImageViaMcp(context.tenantId(), presignedAssetUrl(asset), question);
        }
        return understandLocalAsset(context, asset, question);
    }

    private NodeResult understandImageViaMcp(String tenantId, String imageUrl, String question) {
        String analysis = imageAnalysisService.analyze(tenantId, imageUrl, question).summary();
        if (analysis == null || analysis.isBlank()) {
            return new NodeResult("商品画像不可用，请基于关键词直接生成。", "图片理解不可用，已降级");
        }
        return new NodeResult(analysis, "商品画像已生成");
    }

    private NodeResult understandLocalAsset(RunContext context,
                                            com.zwx.zwxagent.creative.domain.CreativeAssetRecord asset,
                                            String question) {
        var selection = modelCatalogService.resolve(context.userId(), ModelCatalogService.PURPOSE_VISION);
        if (!selection.config().hasApiKey()) {
            throw new IllegalStateException("本地素材的图片理解需要配置视觉模型，请在设置→模型绑定 vision 用途");
        }
        try {
            byte[] bytes;
            try (var in = mediaStorage.open(asset.objectKey())) {
                bytes = in.readAllBytes();
            }
            ChatClient client = ChatClient.builder(chatModelFactory.model(selection.config())).build();
            String prompt = question == null || question.isBlank()
                    ? "描述这张图片的主体、场景、文字与要点。" : question;
            String answer = client.prompt()
                    .user(spec -> spec.text(prompt)
                            .media(new org.springframework.ai.content.Media(
                                    org.springframework.util.MimeTypeUtils.parseMimeType(asset.mimeType()),
                                    new org.springframework.core.io.ByteArrayResource(bytes))))
                    .call()
                    .content();
            if (answer == null || answer.isBlank()) {
                return new NodeResult("商品画像不可用，请基于关键词直接生成。", "图片理解不可用，已降级");
            }
            return new NodeResult(answer, "商品画像已生成（视觉模型）");
        } catch (Exception exception) {
            throw new IllegalStateException("本地素材图片理解失败：" + exception.getMessage(), exception);
        }
    }

    private NodeResult webSearch(String query) throws Exception {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("搜索关键词为空");
        WebSearchTool tool = new WebSearchTool(searchProvider, searchApiKey);
        String result = tool.searchWeb(query);
        if (result == null || result.isBlank()) throw new IllegalStateException("联网搜索没有返回结果");
        return new NodeResult(result, "联网搜索完成");
    }

    private NodeResult renderPoster(RunContext context, String nodeKey, String imageUrl, String imageAssetId,
                                    String copyJson) throws Exception {
        if ((imageUrl == null || imageUrl.isBlank()) && (imageAssetId == null || imageAssetId.isBlank())) {
            throw new IllegalArgumentException("海报缺少商品图输入");
        }
        JsonNode node = parseLooseJson(copyJson);
        PosterComposer.PosterCopy copy = new PosterComposer.PosterCopy(
                textOrDefault(node.path("title").asText(), "精选好物"),
                textOrDefault(node.path("subtitle").asText(), "用心选品 · 品质之选"),
                listOfText(node.path("points"), List.of("品质保障", "匠心工艺", "即刻拥有")),
                textOrDefault(node.path("palette").path("bgFrom").asText(), "#EAF1FF"),
                textOrDefault(node.path("palette").path("bgTo").asText(), "#D6E6FF"),
                textOrDefault(node.path("palette").path("accent").asText(), "#2E6BE6"),
                textOrDefault(node.path("palette").path("text").asText(), "#16224A"));
        byte[] productImage = imageAssetId == null || imageAssetId.isBlank()
                ? downloadImage(imageUrl) : readAssetBytes(context, imageAssetId);
        byte[] poster = PosterComposer.compose(productImage, copy);
        // R05（评审 2026-10-31）：单一存储 + 统一素材登记。对象键带 nodeKey 与随机后缀，
        // 多个海报节点互不覆盖；经 mediaStorage 适配本地/OSS（此前直写 OSS，本地模式海报链路必失败）。
        // 登记失败让节点可重试失败——渲染确定性且零计费，重试不会重新调用模型。
        String objectKey = "workflow/" + context.userId() + "/" + context.id() + "/"
                + nodeKey + "/poster-" + UUID.randomUUID().toString().substring(0, 8) + ".png";
        mediaStorage.put(objectKey, "image/png", new java.io.ByteArrayInputStream(poster), poster.length);
        var actor = new com.zwx.zwxagent.platform.security.CurrentActor(
                context.userId(), context.tenantId(), context.userId(),
                com.zwx.zwxagent.platform.security.Role.USER);
        UUID projectId = context.creativeProjectId() != null
                ? context.creativeProjectId()
                : workflowProject(actor, null);
        var asset = creativeAssetService.registerRendered(actor, projectId,
                "poster-" + context.id() + "-" + nodeKey + ".png", poster, objectKey,
                java.util.Map.of("workflowRunId", context.id(), "workflowNode", nodeKey));
        Artifact artifact = new Artifact("poster", "海报.png", objectKey, asset.id().toString());
        context.artifacts().add(artifact);
        jdbcTemplate.update("UPDATE workflow_run SET artifacts = ?::jsonb, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                toJson(context.artifacts()), context.id());
        return new NodeResult(toJson(java.util.Map.of(
                "assetId", asset.id().toString(),
                "objectKey", objectKey)), "海报已生成并归入素材库");
    }

    private NodeResult httpRequest(RunContext context, Map<String, Object> params) throws Exception {
        String url = text(params.get("url"));
        if (url == null || url.isBlank()) throw new IllegalArgumentException("REST 请求缺少 url");
        String method = textOrDefault(text(params.get("method")).toUpperCase(Locale.ROOT), "GET");
        String body = text(params.get("body"));
        URI target = URI.create(url);
        validateOutboundUrl(target);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(30));
        builder.header("User-Agent", "Aster-Workflow/0.1");
        if (params.get("headers") instanceof Map<?, ?> headers) {
            headers.forEach((key, value) -> builder.header(String.valueOf(key), String.valueOf(value)));
        }
        if (body != null && !body.isBlank()) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String payload = response.body() == null ? "" : response.body();
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("REST 请求失败：HTTP " + response.statusCode() + " "
                    + truncate(payload, 200));
        }
        return new NodeResult(truncate(payload, MAX_OUTPUT_CHARS), "HTTP " + response.statusCode());
    }

    /** 只读数据库查询：仅允许单条 SELECT/WITH，限定 200 行与 10 秒超时。 */
    private NodeResult dbQuery(String sql) {
        String statement = sql == null ? "" : sql.trim();
        if (statement.isEmpty()) throw new IllegalArgumentException("数据库查询缺少 sql");
        String upper = statement.toUpperCase(Locale.ROOT);
        if (!(upper.startsWith("SELECT") || upper.startsWith("WITH"))) {
            throw new IllegalArgumentException("数据库查询仅支持 SELECT/WITH 只读语句");
        }
        if (statement.contains(";") && statement.indexOf(';') < statement.length() - 1) {
            throw new IllegalArgumentException("数据库查询只允许单条语句");
        }
        List<Map<String, Object>> rows = jdbcTemplate.query(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement(statement);
            ps.setMaxRows(200);
            ps.setQueryTimeout(10);
            return ps;
        }, rs -> {
            List<Map<String, Object>> result = new ArrayList<>();
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columns; i++) {
                    row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                }
                result.add(row);
            }
            return result;
        });
        if (rows == null) rows = List.of();
        return new NodeResult(truncate(toJson(rows), MAX_OUTPUT_CHARS), "查询返回 " + rows.size() + " 行");
    }

    /** 文生图：DashScope 异步任务（创建 → 轮询 → 下载），产物登记为图片。 */
    private NodeResult imageGenerate(RunContext context, String prompt, String size, String model) throws Exception {
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("生图提示词为空");
        var imageBinding = modelCatalogService.resolveImageBinding(context.userId());
        var config = imageBinding.map(ModelCatalogService.ImageBinding::config)
                .orElseGet(() -> modelCatalogService.resolve(context.userId(), ModelCatalogService.PURPOSE_CHAT).config());
        if (!"dashscope".equalsIgnoreCase(config.provider()) || !config.hasApiKey()) {
            throw new IllegalStateException("图片生成需要 DashScope 供应商（请在模型设置中绑定带 Key 的 DashScope）");
        }
        // 绑定了「图片生成」用途时以绑定模型为准，否则沿用节点上的 model 参数（旧行为）
        String effectiveModel = imageBinding.isPresent() && config.modelName() != null && !config.modelName().isBlank()
                ? config.modelName() : model;
        String baseUrl = config.baseUrl() == null || config.baseUrl().isBlank()
                ? "https://dashscope.aliyuncs.com" : config.baseUrl().replaceAll("/+$", "");
        String createBody = toJson(Map.of(
                "model", effectiveModel,
                "input", Map.of("prompt", prompt),
                "parameters", Map.of("size", size, "n", 1)));
        HttpRequest create = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/services/aigc/text2image/image-synthesis"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("X-DashScope-Async", "enable")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(createBody)).build();
        HttpResponse<String> created = httpClient.send(create, HttpResponse.BodyHandlers.ofString());
        if (created.statusCode() >= 400) {
            throw new IllegalStateException("创建生图任务失败：HTTP " + created.statusCode() + " " + truncate(created.body(), 200));
        }
        String taskId = parseNode(created.body()).path("output").path("task_id").asText("");
        if (taskId.isBlank()) throw new IllegalStateException("生图任务创建未返回 task_id");
        String imageUrl = null;
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(3000);
            HttpRequest poll = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/tasks/" + taskId))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .GET().build();
            HttpResponse<String> status = httpClient.send(poll, HttpResponse.BodyHandlers.ofString());
            JsonNode output = parseNode(status.body()).path("output");
            String state = output.path("task_status").asText("");
            if ("SUCCEEDED".equals(state)) {
                imageUrl = output.path("results").path(0).path("url").asText("");
                break;
            }
            if ("FAILED".equals(state) || "CANCELED".equals(state)) {
                throw new IllegalStateException("生图任务失败：" + truncate(status.body(), 200));
            }
        }
        if (imageUrl == null || imageUrl.isBlank()) throw new IllegalStateException("生图任务超时未完成");
        byte[] image = downloadImage(imageUrl);
        String name = "生成图片" + (image.length > 0 ? ".png" : "");
        Artifact artifact = storeArtifact(context, "image", name, "image/png", image, "workflow/" + context.userId()
                + "/" + context.id() + "/generated-" + System.currentTimeMillis() + ".png");
        return new NodeResult(artifact.objectKey(), "图片已生成");
    }

    // ---------------------------------------------------------------- C13 媒体节点等待与恢复

    /** 媒体节点：受理创作任务后立即让出线程；结果由 WorkflowMediaResumeScheduler 推进。 */
    private NodeResult startMediaImageTask(RunContext context, GraphNodeDef node,
                                           Map<String, Object> params) {
        boolean isEdit = "media_image_edit".equals(node.type());
        String operation = isEdit ? "image_edit" : "text_to_image";
        String prompt = text(params.get("prompt"));
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException((isEdit ? "图片编辑" : "图片生成") + "提示词为空");
        }
        if (prompt.length() > 500) {
            throw new IllegalArgumentException("提示词超过模型上限 500 个字符");
        }
        // 参考图：imageAssetIds 接受数组或逗号/空白分隔的 assetId 列表（支持 {{节点.output.assetId}} 引用）
        java.util.List<UUID> referenceAssetIds = parseAssetIdList(params.get("imageAssetIds"));
        if (isEdit && referenceAssetIds.isEmpty()) {
            throw new IllegalArgumentException("image_edit 节点需要 imageAssetIds（1–3 张参考素材）");
        }
        String size = textOrDefault(text(params.get("size")), "1024*1024");
        var actor = new com.zwx.zwxagent.platform.security.CurrentActor(
                context.userId(), context.tenantId(), context.userId(),
                com.zwx.zwxagent.platform.security.Role.USER);
        // 项目归属：运行绑定的创作项目优先（用户在发起时的选择），节点参数其次，最后兜底
        UUID projectId = context.creativeProjectId() != null
                ? context.creativeProjectId()
                : workflowProject(actor, text(params.get("projectId")));
        com.zwx.zwxagent.creative.service.MediaJobService.CreateJobResult created = mediaJobService.create(actor,
                "workflow-" + context.id() + "-" + node.key(), projectId,
                new com.zwx.zwxagent.creative.service.MediaJobService.CreateJobRequest(projectId, operation, null,
                        prompt, prompt, Map.of("size", size), referenceAssetIds, null, null, false),
                com.zwx.zwxagent.creative.domain.JobSourceType.WORKFLOW, context.id(), node.key());
        jdbcTemplate.update("""
                        INSERT INTO workflow_node_execution (run_id, node_key, status, media_job_id, attempt)
                        VALUES (?, ?, 'WAITING_EXTERNAL', ?, 1)
                        ON CONFLICT (run_id, node_key) DO UPDATE
                        SET status = 'WAITING_EXTERNAL',
                            media_job_id = COALESCE(workflow_node_execution.media_job_id, EXCLUDED.media_job_id),
                            updated_at = CURRENT_TIMESTAMP
                        """,
                context.id(), node.key(), created.jobId());
        return NodeResult.waiting((isEdit ? "参考图编辑中" : "图片生成中") + "（任务 " + created.jobId() + "）");
    }

    /** imageAssetIds 参数解析：List / 逗号或空白分隔字符串 → UUID 列表（非法值报错）。 */
    private static java.util.List<UUID> parseAssetIdList(Object raw) {
        if (raw == null) return List.of();
        java.util.List<String> tokens = new ArrayList<>();
        if (raw instanceof java.util.List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) tokens.add(String.valueOf(item).trim());
            }
        } else {
            for (String token : String.valueOf(raw).split("[,，\\s]+")) {
                if (!token.isBlank()) tokens.add(token.trim());
            }
        }
        try {
            return tokens.stream().map(UUID::fromString).toList();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("imageAssetIds 格式不正确（应为素材 ID 列表）");
        }
    }

    private UUID workflowProject(com.zwx.zwxagent.platform.security.CurrentActor actor, String requested) {
        if (requested != null && !requested.isBlank()) {
            try {
                return UUID.fromString(requested.trim());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("projectId 格式不正确");
            }
        }
        var items = creativeProjectService.list(actor, null, 50).items();
        return items.stream().filter(project -> "ACTIVE".equals(project.status()))
                .findFirst().map(com.zwx.zwxagent.creative.service.CreativeProjectService.ProjectView::id)
                .orElseGet(() -> creativeProjectService.create(actor,
                        new com.zwx.zwxagent.creative.service.CreativeProjectService.CreateProjectRequest(
                                "工作流创作", "工作流图片节点自动创建")).id());
    }

    /** 媒体任务成功：节点写入结构化输出并尝试一次性推进后续节点。 */
    public void advanceMediaNode(String runId, String nodeKey, String outputJson, String message) {
        // R01（评审 2026-10-31）：节点终态、steps 更新与运行唤醒放同一事务——
        // 此前为三条独立写入，节点写完 SUCCEEDED 后崩溃会让运行永久等待。
        transactionTemplate.executeWithoutResult(status -> {
            int updated = jdbcTemplate.update("""
                    UPDATE workflow_node_execution SET status = 'SUCCEEDED', output_json = ?::jsonb,
                        error = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE run_id = ? AND node_key = ? AND status = 'WAITING_EXTERNAL'
                    """, outputJson, runId, nodeKey);
            if (updated > 0) {
                updateWaitingStep(runId, nodeKey, "SUCCEEDED", message, outputJson);
                resumeWaiting(runId);
            }
        });
    }

    /**
     * 媒体任务失败：按依赖图界定失败范围（联合评审 §3"失败范围"）。
     * 失败节点存在下游依赖 → 依赖跳过、运行失败（原语义）；
     * 无下游依赖（如三机位中的侧面机位）→ 仅标记该节点失败，其余分支继续，运行仍可成功。
     */
    public void failMediaNode(String runId, String nodeKey, String error) {
        transactionTemplate.executeWithoutResult(status -> {
            int updated = jdbcTemplate.update("""
                    UPDATE workflow_node_execution SET status = 'FAILED', error = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE run_id = ? AND node_key = ? AND status = 'WAITING_EXTERNAL'
                    """, truncate(error, 400), runId, nodeKey);
            if (updated == 0) return;
            WorkflowRunRow row = findAny(runId);
            if (row == null) return;
            WorkflowGraph graph = row.graphSnapshot() != null
                    ? parseGraph(row.graphSnapshot().toString())
                    : latestVersion(row.tenantId(), row.workflowKey()).graph();
            var dependents = transitiveDependents(graph, nodeKey);
            List<StepState> steps = parseSteps(row.steps());
            List<StepState> updatedSteps = steps.stream().map(step -> {
                if (step.key().equals(nodeKey)) {
                    return new StepState(step.key(), step.name(), "FAILED", truncate(error, 300),
                            step.output(), step.startedAt(), Instant.now());
                }
                if (dependents.contains(step.key())
                        && ("PENDING".equals(step.status()) || "RUNNING".equals(step.status()))) {
                    return new StepState(step.key(), step.name(), "SKIPPED", "前序步骤失败",
                            step.output(), step.startedAt(), Instant.now());
                }
                return step;
            }).toList();
            if (dependents.isEmpty()) {
                // 失败不阻断其余分支：保持 WAITING_EXTERNAL 让恢复器/续跑推进剩余节点
                jdbcTemplate.update("""
                        UPDATE workflow_run SET steps = ?::jsonb, error = NULL,
                            current_step = NULL, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND status = 'WAITING_EXTERNAL'
                        """, toJson(updatedSteps), runId);
                resumeWaiting(runId);
            } else {
                jdbcTemplate.update("""
                        UPDATE workflow_run SET steps = ?::jsonb, status = 'FAILED', error = ?,
                            current_step = NULL, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND status = 'WAITING_EXTERNAL'
                        """, toJson(updatedSteps), truncate(error, 400), runId);
            }
        });
    }

    /** 有向依赖的传递闭包：failedKey 的全部直接/间接下游。 */
    private static java.util.Set<String> transitiveDependents(WorkflowGraph graph, String failedKey) {
        var adjacency = new java.util.HashMap<String, List<String>>();
        for (GraphEdgeDef edge : graph.edges()) {
            adjacency.computeIfAbsent(edge.from(), key -> new ArrayList<>()).add(edge.to());
        }
        var result = new java.util.HashSet<String>();
        var stack = new java.util.ArrayDeque<String>();
        stack.push(failedKey);
        while (!stack.isEmpty()) {
            String current = stack.pop();
            for (String next : adjacency.getOrDefault(current, List.of())) {
                if (result.add(next)) stack.push(next);
            }
        }
        return result;
    }

    private void updateWaitingStep(String runId, String nodeKey, String status, String message, String output) {
        WorkflowRunRow row = findAny(runId);
        if (row == null) return;
        List<StepState> updated = parseSteps(row.steps()).stream().map(step -> step.key().equals(nodeKey)
                ? new StepState(step.key(), step.name(), status, message == null ? "" : message,
                truncate(output, MAX_OUTPUT_CHARS), step.startedAt(), Instant.now())
                : step).toList();
        jdbcTemplate.update("""
                UPDATE workflow_run SET steps = ?::jsonb, current_step = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'WAITING_EXTERNAL'
                """, toJson(updated), runId);
    }

    /** 认领并继续等待中的运行；仅一个恢复者能把 WAITING_EXTERNAL 切到 RUNNING。 */
    public boolean resumeWaiting(String runId) {
        int claimed = jdbcTemplate.update("""
                UPDATE workflow_run SET status = 'RUNNING', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'WAITING_EXTERNAL'
                """, runId);
        if (claimed == 0) return false;
        WorkflowRunRow row = findAny(runId);
        if (row == null) return false;
        WorkflowGraph graph = row.graphSnapshot() == null
                ? latestVersion(row.tenantId(), row.workflowKey()).graph()
                : parseGraph(row.graphSnapshot().toString());
        Map<String, Object> input = objectMapper.convertValue(row.input(), new TypeReference<>() {
        });
        List<Artifact> artifacts = new ArrayList<>();
        row.artifacts().forEach(node -> artifacts.add(new Artifact(
                node.path("type").asText(), node.path("name").asText(), node.path("objectKey").asText(),
                node.path("assetId").asText().isBlank() ? null : node.path("assetId").asText())));
        RunContext context = new RunContext(row.id(), row.tenantId(), row.userId(), row.workflowKey(),
                row.definitionVersion(), row.workflowKey(), "", graph, input, parseSteps(row.steps()), artifacts,
                row.creativeProjectId());
        executor.submit(() -> execute(context));
        return true;
    }

    private List<StepState> parseSteps(JsonNode stepsNode) {
        List<StepState> steps = new ArrayList<>();
        if (stepsNode != null) {
            stepsNode.forEach(node -> steps.add(new StepState(
                    node.path("key").asText(), node.path("name").asText(), node.path("status").asText(),
                    node.path("message").asText(), node.path("output").asText(""),
                    instantOrNull(node, "startedAt"), instantOrNull(node, "finishedAt"))));
        }
        return steps;
    }

    private WorkflowRunRow findAny(String runId) {
        List<WorkflowRunRow> rows = jdbcTemplate.query("SELECT * FROM workflow_run WHERE id = ?",
                (rs, rowNum) -> mapRow(rs), runId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 文件导出：把上游文本保存为可下载文件产物。 */
    private NodeResult fileExport(RunContext context, String filename, String content) {
        if (filename == null || filename.isBlank()) throw new IllegalArgumentException("文件导出缺少文件名");
        String safeName = filename.replaceAll("[\\/:*?\"<>|]", "_");
        String text = content == null ? "" : content;
        String lower = safeName.toLowerCase(Locale.ROOT);
        String contentType = lower.endsWith(".json") ? "application/json"
                : lower.endsWith(".md") ? "text/markdown"
                : lower.endsWith(".csv") ? "text/csv" : "text/plain";
        String objectKey = "workflow/" + context.userId() + "/" + context.id() + "/" + safeName;
        Artifact artifact = storeArtifact(context, "file", safeName, contentType,
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8), objectKey);
        return new NodeResult(artifact.objectKey(), "文件已导出：" + safeName);
    }

    /**
     * 出站地址策略（本地单用户工具）：域名放行（本机 HTTPS 代理/VPN 常把公网域名解析为保留段地址，
     * 按解析结果拦截会误伤正常调用）；字面 IP 一律做内网/回环校验；保留云元数据地址拦截。
     * 桌面模式另允许 localhost（本机服务）。
     */
    private void validateOutboundUrl(URI target) {
        String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme))) {
            throw new IllegalArgumentException("REST 请求仅支持 HTTP(S)");
        }
        String host = target.getHost() == null ? "" : target.getHost().toLowerCase(Locale.ROOT);
        if (host.isBlank()) throw new IllegalArgumentException("REST 请求地址无效");
        if ("169.254.169.254".equals(host)) throw new IllegalArgumentException("禁止访问云元数据地址");
        boolean localAllowed = allowLocalEndpoints && ("localhost".equals(host) || host.endsWith(".local"));
        if (localAllowed) return;
        if (isIpLiteral(host)) {
            try {
                java.net.InetAddress address = java.net.InetAddress.getByName(host);
                if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                        || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
                    throw new IllegalArgumentException("禁止访问内网或回环地址：" + host);
                }
            } catch (java.net.UnknownHostException exception) {
                throw new IllegalArgumentException("REST 请求地址无效：" + host);
            }
            return;
        }
        if ("localhost".equals(host) || host.endsWith(".local") || host.endsWith(".internal")) {
            throw new IllegalArgumentException("禁止访问本机或内网域名：" + host);
        }
    }

    private static boolean isIpLiteral(String host) {
        return host.contains(":") || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /** OSS 素材 → 10 分钟预签名 URL（不暴露对象键给工作流输出）。 */
    private String presignedAssetUrl(com.zwx.zwxagent.creative.domain.CreativeAssetRecord asset) {
        return ossClientProvider.getClient().generatePresignedUrl(ossBucket, asset.objectKey(),
                java.util.Date.from(Instant.now().plusSeconds(600))).toString();
    }

    private byte[] readAssetBytes(RunContext context, String imageAssetId) throws Exception {
        String assetId = resolveAssetId(null, imageAssetId);
        if (assetId == null) throw new IllegalArgumentException("素材 ID 为空");
        var asset = requireAsset(context, assetId);
        try (var in = mediaStorage.open(asset.objectKey())) {
            return in.readAllBytes();
        }
    }

    private static String resolveAssetId(String image, String imageAssetId) {
        if (imageAssetId != null && !imageAssetId.isBlank()) return imageAssetId.trim();
        if (image != null && image.startsWith("asset:")) return image.substring("asset:".length()).trim();
        return null;
    }

    private com.zwx.zwxagent.creative.domain.CreativeAssetRecord requireAsset(RunContext context, String assetId) {
        UUID id;
        try {
            id = UUID.fromString(assetId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("素材 ID 格式不正确");
        }
        var asset = creativeAssetRepository.findByIdForOwner(context.tenantId(), context.userId(), id)
                .orElseThrow(() -> new IllegalArgumentException("素材不存在或无权访问"));
        if (asset.status() != com.zwx.zwxagent.creative.domain.AssetStatus.READY) {
            throw new IllegalArgumentException("素材尚未就绪，不能引用");
        }
        return asset;
    }

    // ---------------------------------------------------------------- 参数引用与工具

    /** 替换 params 中的 "{{input.字段}}" 与 "{{节点key.output}}" 引用；整体引用还原原值，嵌套结构递归处理。 */
    // ---------------------------------------------------------------- 小说节点（AI 创作 v1）

    /**
     * 章节上下文（novel_context）：读绑定项目的作品设定 + 章节目录 + 近 N 章概要，
     * 输出纯文本块供 llm_text 组装 prompt（{{节点key.output}} 整体引用）。
     * 这是「创作层持有跨运行状态」的读入口：状态随章节确认累积。
     */
    private NodeResult novelContext(RunContext context, Map<String, Object> params) {
        if (context.creativeProjectId() == null) {
            throw new IllegalArgumentException("章节上下文节点需要运行绑定创作项目（请从创作台发起运行）");
        }
        int summaryCount;
        try {
            summaryCount = Integer.parseInt(textOrDefault(text(params.get("summaryCount")), "3").trim());
        } catch (NumberFormatException exception) {
            summaryCount = 3;
        }
        summaryCount = Math.max(1, Math.min(10, summaryCount));
        var actor = workflowActor(context);
        Map<String, Object> settings = creativeProjectService.settingsOf(actor, context.creativeProjectId());
        List<com.zwx.zwxagent.creative.domain.NovelChapterRecord> chapters = novelChapterRepository
                .listByProject(context.tenantId(), context.userId(), context.creativeProjectId());
        StringBuilder output = new StringBuilder();
        output.append("【作品设定】\n").append(novelSettingsBlock(settings)).append("\n\n");
        output.append("【章节目录】\n");
        if (chapters.isEmpty()) {
            output.append("（尚无章节，本章为第 1 章）\n");
        } else {
            for (var chapter : chapters) {
                output.append("第").append(chapter.chapterNumber()).append("章 ").append(chapter.title())
                        .append(chapter.status() == com.zwx.zwxagent.creative.domain.ChapterStatus.CONFIRMED
                                ? "（已确认）" : "（草稿）")
                        .append("\n");
            }
            int recent = Math.min(summaryCount, chapters.size());
            output.append("\n【近").append(recent).append("章概要】\n");
            chapters.stream().skip(chapters.size() - recent).forEach(chapter ->
                    output.append("第").append(chapter.chapterNumber()).append("章：")
                            .append(chapter.summary() == null || chapter.summary().isBlank()
                                    ? "（无概要）" : chapter.summary())
                            .append("\n"));
        }
        int nextNumber = chapters.isEmpty() ? 1 : chapters.getLast().chapterNumber() + 1;
        output.append("\n【本章序号】第").append(nextNumber).append("章");
        return new NodeResult(output.toString(),
                "章节上下文已就绪（既往 " + chapters.size() + " 章，本章为第 " + nextNumber + " 章）");
    }

    /**
     * 章节入库（novel_chapter_save）：把上游正文/概要写回绑定项目的章节库（DRAFT）。
     * 同一运行幂等（崩溃重跑更新既有草稿，不产生重复章节）；章节号在入库时分配。
     */
    private NodeResult novelChapterSave(RunContext context, Map<String, Object> params) {
        if (context.creativeProjectId() == null) {
            throw new IllegalArgumentException("章节入库节点需要运行绑定创作项目（请从创作台发起运行）");
        }
        String content = text(params.get("content"));
        if (content.isBlank()) {
            throw new IllegalArgumentException("章节正文为空，请检查上游正文节点输出");
        }
        var view = novelChapterService.saveFromRun(workflowActor(context), context.creativeProjectId(),
                UUID.fromString(context.id()), textOrDefault(text(params.get("title")), ""),
                content, text(params.get("summary")), text(params.get("plotDirection")));
        return new NodeResult(toJson(Map.of(
                        "chapterId", String.valueOf(view.id()),
                        "chapterNumber", view.chapterNumber(),
                        "wordCount", view.wordCount())),
                "章节草稿已入库：第" + view.chapterNumber() + "章「" + view.title() + "」（" + view.wordCount() + " 字）");
    }

    private com.zwx.zwxagent.platform.security.CurrentActor workflowActor(RunContext context) {
        return new com.zwx.zwxagent.platform.security.CurrentActor(
                context.userId(), context.tenantId(), context.userId(),
                com.zwx.zwxagent.platform.security.Role.USER);
    }

    /** 项目设定文本块（novel_context 与剧情建议共用格式）。 */
    private static String novelSettingsBlock(Map<String, Object> settings) {
        return com.zwx.zwxagent.creative.novel.NovelPlanningService.settingsBlock(settings);
    }

    private Map<String, Object> resolveParams(Map<String, Object> params, Map<String, Object> input,
                                              Map<String, String> outputs) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (params == null) return resolved;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            resolved.put(entry.getKey(), resolveValue(entry.getValue(), input, outputs));
        }
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private Object resolveValue(Object value, Map<String, Object> input, Map<String, String> outputs) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), resolveValue(entry.getValue(), input, outputs));
            }
            return result;
        }
        if (!(value instanceof String text)) return value;
        Matcher matcher = REF_PATTERN.matcher(text);
        if (matcher.matches()) {
            return lookupRef(matcher.group(1), input, outputs);
        }
        return matcher.replaceAll(match -> String.valueOf(lookupRef(match.group(1), input, outputs)));
    }

    private String lookupRef(String ref, Map<String, Object> input, Map<String, String> outputs) {
        if (ref.startsWith("input.")) {
            Object value = input.get(ref.substring("input.".length()));
            return value == null ? "" : String.valueOf(value);
        }
        int first = ref.indexOf('.');
        if (first > 0) {
            String nodeKey = ref.substring(0, first);
            String field = ref.substring(first + 1);
            String raw = outputs.getOrDefault(nodeKey, "");
            if ("output".equals(field)) return raw;
            // 媒体节点输出为 JSON：支持 {{node.assetId}} 与 {{node.output.assetId}}
            String path = field.startsWith("output.") ? field.substring("output.".length()) : field;
            try {
                JsonNode parsed = objectMapper.readTree(raw);
                for (String part : path.split("\\.")) {
                    if (parsed == null) break;
                    parsed = parsed.path(part);
                }
                if (parsed != null && !parsed.isMissingNode() && !parsed.isNull()) {
                    return parsed.isTextual() ? parsed.asText() : parsed.toString();
                }
            } catch (Exception ignored) {
                // 非 JSON 输出按空值处理
            }
        }
        return "";
    }

    private List<String> extractUrls(String raw) {
        List<String> urls = new ArrayList<>();
        if (raw == null || raw.isBlank()) return urls;
        String text = raw;
        try {
            JsonNode payload = objectMapper.readTree(raw);
            for (int depth = 0; depth < 5; depth++) {
                if (payload.isObject() && payload.has("text") && payload.get("text").isTextual()) {
                    payload = objectMapper.readTree(payload.get("text").asText());
                    continue;
                }
                if (payload.isTextual()) {
                    payload = objectMapper.readTree(payload.asText());
                    continue;
                }
                break;
            }
            text = payload.toString();
        } catch (Exception ignored) {
            text = raw;
        }
        for (String token : text.split("[\\s,\\[\\]\"]+")) {
            if (token.startsWith("http://") || token.startsWith("https://")) urls.add(token);
        }
        return urls;
    }

    private boolean isProbableImage(String url) {
        String path = url;
        int queryIndex = url.indexOf('?');
        if (queryIndex >= 0) path = url.substring(0, queryIndex);
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp");
    }

    /** Wikimedia Commons 开放图片检索：无密钥依赖，作为 MCP 搜索的回退来源。 */
    private List<String> searchCommons(String keyword) {
        try {
            String query = java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrsearch=" + query
                                    + "&gsrnamespace=6&gsrlimit=8&prop=imageinfo&iiprop=url%7Cmime&iiurlwidth=1280&format=json"))
                    .header("User-Agent", "Aster-Workflow/0.1 (product poster pilot)")
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode pages = parseNode(response.body()).path("query").path("pages");
            log.info("Commons 检索：keyword={} status={} pages={}", keyword, response.statusCode(), pages.size());
            List<String> urls = new ArrayList<>();
            pages.forEach(page -> page.path("imageinfo").forEach(info -> {
                String mime = info.path("mime").asText();
                String url = info.path("thumburl").asText("");
                if (url.isBlank()) url = info.path("url").asText("");
                if ((mime.equals("image/jpeg") || mime.equals("image/png")) && !url.isBlank()) urls.add(url);
            }));
            log.info("Commons urls={} first={}", urls.size(), urls.isEmpty() ? "-" : urls.get(0));
            return urls;
        } catch (Exception exception) {
            log.info("Commons 检索不可用：{}", exception.getMessage());
            return List.of();
        }
    }

    private String translateKeyword(String keyword) {
        try {
            var selection = modelCatalogService.resolve("", ModelCatalogService.PURPOSE_CHAT);
            ChatClient client = ChatClient.builder(chatModelFactory.model(selection.config())).build();
            String answer = client.prompt()
                    .user("把商品关键词翻译成英文，只输出英文词组本身，不要解释：" + keyword)
                    .call().content();
            String text = answer == null ? "" : answer.trim().replaceAll("[^A-Za-z ]", "").trim();
            log.info("关键词翻译：{} -> {}", keyword, text);
            return text.length() > 64 ? text.substring(0, 64) : text;
        } catch (Exception exception) {
            log.info("关键词翻译不可用：{}", exception.getMessage());
            return "";
        }
    }

    /** 检索来源为外部公开媒体 CDN：本机代理/VPN 环境常解析为保留段 IP，按可信来源放行 */
    private static final Set<String> TRUSTED_IMAGE_HOSTS =
            Set.of("wikimedia.org", "wikipedia.org", "pexels.com", "pixabay.com", "openverse.org", "aliyuncs.com");

    private byte[] downloadImage(String url) throws Exception {
        URI target = URI.create(url);
        String host = target.getHost() == null ? "" : target.getHost().toLowerCase(Locale.ROOT);
        boolean trusted = "https".equalsIgnoreCase(target.getScheme())
                && TRUSTED_IMAGE_HOSTS.stream().anyMatch(known -> host.equals(known) || host.endsWith("." + known));
        if (!trusted) {
            urlAccessPolicy.validateHttpUrl(url); // 非可信来源维持公网 SSRF 校验
        }
        HttpResponse<byte[]> response = httpClient.send(HttpRequest.newBuilder(target)
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "Aster-Workflow/0.1")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new IllegalStateException("商品图下载失败：HTTP " + response.statusCode());
        byte[] body = response.body();
        if (body == null || body.length == 0) throw new IllegalStateException("商品图为空");
        if (body.length > MAX_IMAGE_BYTES) throw new IllegalStateException("商品图超过 10MB 限制");
        String contentType = response.headers().firstValue("content-type").orElse("");
        if (!contentType.startsWith("image/") && !isProbableImage(url)) {
            throw new IllegalStateException("链接不是图片：" + contentType);
        }
        return body;
    }

    private Artifact storeArtifact(RunContext context, byte[] poster) {
        return storeArtifact(context, "poster", "海报.png", "image/png", poster,
                "workflow/" + context.userId() + "/" + context.id() + "/poster.png");
    }

    private Artifact storeArtifact(RunContext context, String type, String name, String contentType,
                                   byte[] bytes, String objectKey) {
        var metadata = new com.aliyun.oss.model.ObjectMetadata();
        metadata.setContentLength((long) bytes.length);
        metadata.setContentType(contentType);
        ossClientProvider.getClient().putObject(ossBucket, objectKey,
                new java.io.ByteArrayInputStream(bytes), metadata);
        Artifact artifact = new Artifact(type, name, objectKey);
        context.artifacts().add(artifact);
        jdbcTemplate.update("UPDATE workflow_run SET artifacts = ?::jsonb, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                toJson(context.artifacts()), context.id());
        return artifact;
    }

    public String presignArtifact(String tenantId, String userId, String runId, String objectKey) {
        return presignArtifact(tenantId, userId, runId, objectKey, null);
    }

    public String presignArtifact(String tenantId, String userId, String runId, String objectKey, String downloadName) {
        if (!objectKey.startsWith("workflow/" + userId + "/" + runId + "/")) {
            throw new IllegalArgumentException("无权访问该产物");
        }
        var request = new com.aliyun.oss.model.GeneratePresignedUrlRequest(ossBucket, objectKey,
                com.aliyun.oss.HttpMethod.GET);
        request.setExpiration(java.util.Date.from(Instant.now().plus(Duration.ofHours(2))));
        if (downloadName != null && !downloadName.isBlank()) {
            request.addQueryParameter("response-content-disposition",
                    "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(downloadName,
                            java.nio.charset.StandardCharsets.UTF_8));
        }
        return ossClientProvider.getClient().generatePresignedUrl(request).toString();
    }

    // ---------------------------------------------------------------- 状态持久化

    private void updateStep(RunContext context, StepState state) {
        Map<String, StepState> byKey = new LinkedHashMap<>();
        context.steps().forEach(item -> byKey.put(item.key(), item));
        byKey.put(state.key(), state);
        context.steps().clear();
        context.steps().addAll(byKey.values());
        jdbcTemplate.update("""
                        UPDATE workflow_run
                        SET steps = ?::jsonb, current_step = CASE WHEN ? = 'RUNNING' THEN ? ELSE current_step END,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, toJson(context.steps()), state.status(), state.key(), context.id());
    }

    private void markPendingSkipped(RunContext context) {
        List<StepState> updated = context.steps().stream()
                .map(item -> "PENDING".equals(item.status()) || "RUNNING".equals(item.status())
                        ? new StepState(item.key(), item.name(), "SKIPPED", "前序步骤失败", item.output(),
                        item.startedAt(), Instant.now())
                        : item)
                .toList();
        context.steps().clear();
        context.steps().addAll(updated);
        jdbcTemplate.update("UPDATE workflow_run SET steps = ?::jsonb, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                toJson(context.steps()), context.id());
    }

    private void finishRun(String id, String status, List<Artifact> artifacts, String error) {
        jdbcTemplate.update("""
                        UPDATE workflow_run
                        SET status = ?, artifacts = ?::jsonb, error = ?, current_step = NULL,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """,
                status, toJson(artifacts), error, id);
    }

    // ---------------------------------------------------------------- 查询

    public WorkflowRunView view(String id, String tenantId, String userId) {
        WorkflowRunRow row = find(id, tenantId, userId);
        return toView(row, true);
    }

    public List<WorkflowRunView> list(String tenantId, String userId, int limit) {
        return list(tenantId, userId, null, limit);
    }

    /** 按创作项目筛选运行（项目详情页工作流 tab）；projectId 为 null 时不过滤。 */
    public List<WorkflowRunView> list(String tenantId, String userId, UUID projectId, int limit) {
        String sql = """
                SELECT * FROM workflow_run WHERE tenant_id = ? AND user_id = ?
                """ + (projectId == null ? "" : " AND creative_project_id = ? ")
                + " ORDER BY created_at DESC LIMIT ?";
        Object[] args = projectId == null
                ? new Object[]{tenantId, userId, Math.max(1, Math.min(limit, 50))}
                : new Object[]{tenantId, userId, projectId, Math.max(1, Math.min(limit, 50))};
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapRow(rs), args)
                .stream().map(row -> toView(row, false)).toList();
    }

    private WorkflowRunRow find(String id, String tenantId, String userId) {
        List<WorkflowRunRow> rows = jdbcTemplate.query(
                "SELECT * FROM workflow_run WHERE id = ? AND tenant_id = ? AND user_id = ?",
                (rs, rowNum) -> mapRow(rs), id, tenantId, userId);
        if (rows.isEmpty()) throw new IllegalArgumentException("工作流运行不存在");
        return rows.getFirst();
    }

    private WorkflowRunRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.util.UUID projectId = null;
        Object projectRef = rs.getObject("creative_project_id");
        if (projectRef instanceof java.util.UUID uuid) projectId = uuid;
        return new WorkflowRunRow(rs.getString("id"), rs.getString("tenant_id"), rs.getString("user_id"),
                rs.getString("workflow_key"), rs.getInt("definition_version"), rs.getString("status"),
                rs.getString("current_step"),
                parseNode(rs.getString("input")),
                parseNode(rs.getString("steps")),
                parseNode(rs.getString("artifacts")),
                rs.getString("error"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getString("graph_snapshot") == null ? null : parseNode(rs.getString("graph_snapshot")),
                projectId);
    }

    private WorkflowRunView toView(WorkflowRunRow row, boolean withArtifactUrl) {
        List<StepState> steps = new ArrayList<>();
        row.steps().forEach(node -> steps.add(new StepState(
                node.path("key").asText(), node.path("name").asText(), node.path("status").asText(),
                node.path("message").asText(), node.path("output").asText(""),
                instantOrNull(node, "startedAt"), instantOrNull(node, "finishedAt"))));
        List<ArtifactView> artifacts = new ArrayList<>();
        row.artifacts().forEach(node -> {
            String objectKey = node.path("objectKey").asText();
            String type = node.path("type").asText();
            String name = node.path("name").asText();
            String assetId = node.path("assetId").asText("");
            String url = "";
            if (withArtifactUrl && !assetId.isBlank()) {
                // R05：已登记为创作素材的产物（海报等），前端经鉴权 blob 读取，本地/OSS 存储都可用
                url = "asset:" + assetId;
            } else if (withArtifactUrl && !objectKey.isBlank()) {
                url = presignArtifact(row.tenantId(), row.userId(), row.id(), objectKey,
                        "file".equals(type) ? name : null);
            }
            artifacts.add(new ArtifactView(type, name, url, assetId.isBlank() ? null : assetId));
        });
        Map<String, Object> input = objectMapper.convertValue(row.input(), new TypeReference<>() {
        });
        String name;
        try {
            name = latestVersion(row.tenantId(), row.workflowKey()).name();
        } catch (Exception exception) {
            name = row.workflowKey();
        }
        return new WorkflowRunView(row.id(), row.workflowKey(), name, row.definitionVersion(),
                row.status(), row.currentStep(), input, steps, artifacts, row.error(),
                row.createdAt(), row.updatedAt(), row.creativeProjectId());
    }

    // ---------------------------------------------------------------- 工具

    private JsonNode parseNode(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "null" : json);
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode parseLooseJson(String text) {
        String value = text == null ? "" : text.trim();
        value = value.replace("```json", "").replace("```", "").trim();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start >= 0 && end > start) value = value.substring(start, end + 1);
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }

    private static String textOrDefault(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static List<String> listOfText(JsonNode array, List<String> fallback) {
        List<String> result = new ArrayList<>();
        if (array.isArray()) array.forEach(item -> {
            String value = item.asText().trim();
            if (!value.isEmpty()) result.add(value);
        });
        return result.isEmpty() ? fallback : result.stream().limit(3).toList();
    }

    private static Instant instantOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        try {
            if (value.isNumber()) return Instant.ofEpochSecond((long) value.asDouble());
            String text = value.asText().trim();
            if (text.isEmpty()) return null;
            if (text.chars().allMatch(ch -> Character.isDigit(ch) || ch == '.' || ch == 'E' || ch == 'e' || ch == '-')) {
                return Instant.ofEpochSecond((long) Double.parseDouble(text));
            }
            return Instant.parse(text);
        } catch (Exception exception) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "[]";
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
