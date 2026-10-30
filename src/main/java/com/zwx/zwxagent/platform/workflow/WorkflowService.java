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
            "image_generate", "http_request", "db_query", "text_template", "file_export");

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

    public record Artifact(String type, String name, String objectKey) {
    }

    public record WorkflowRunView(String id, String workflowKey, String workflowName, int definitionVersion,
                                  String status, String currentStep, Map<String, Object> input,
                                  List<StepState> steps, List<ArtifactView> artifacts, String error,
                                  Instant createdAt, Instant updatedAt) {
    }

    public record ArtifactView(String type, String name, String url) {
    }

    public record SaveVersionRequest(String name, String description, WorkflowGraph graph) {
    }

    public record StartRequest(String keyword, Map<String, Object> input) {
    }

    private record DefVersionRow(int version, String name, String description, WorkflowGraph graph, Instant createdAt) {
    }

    private record WorkflowRunRow(String id, String tenantId, String userId, String workflowKey, int definitionVersion,
                                  String status, String currentStep, JsonNode input, JsonNode steps,
                                  JsonNode artifacts, String error, Instant createdAt, Instant updatedAt) {
    }

    private record RunContext(String id, String tenantId, String userId, String workflowKey,
                              int definitionVersion, String name, String description, WorkflowGraph graph,
                              Map<String, Object> input, List<StepState> steps, List<Artifact> artifacts) {
    }

    private record NodeResult(String output, String message) {
    }

    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_OUTPUT_CHARS = 4000;
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
                           @Value("${app.config.allow-local-model-endpoints:false}") boolean allowLocalEndpoints) {
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
        topologicalOrder(definition.graph());

        String id = UUID.randomUUID().toString();
        List<StepState> steps = new ArrayList<>(definition.graph().nodes().stream()
                .map(node -> new StepState(node.key(), node.name(), "PENDING", "", "", null, null))
                .toList());
        jdbcTemplate.update("""
                        INSERT INTO workflow_run (id, tenant_id, user_id, workflow_key, definition_version,
                                                  status, current_step, input, steps, artifacts)
                        VALUES (?, ?, ?, ?, ?, 'RUNNING', ?, ?::jsonb, ?::jsonb, '[]'::jsonb)
                        """,
                id, tenantId, userId, workflowKey, definition.version(),
                definition.graph().nodes().getFirst().key(),
                toJson(input), toJson(steps));
        RunContext context = new RunContext(id, tenantId, userId, workflowKey, definition.version(),
                definition.name(), definition.description(), definition.graph(), input, steps, new ArrayList<>());
        executor.submit(() -> execute(context));
        return view(id, tenantId, userId);
    }

    private void execute(RunContext context) {
        try {
            Map<String, String> outputs = new LinkedHashMap<>();
            for (GraphNodeDef node : topologicalOrder(context.graph())) {
                StepState running = new StepState(node.key(), node.name(), "RUNNING", "", "", Instant.now(), null);
                updateStep(context, running);
                try {
                    Map<String, Object> params = resolveParams(node.params(), context.input(), outputs);
                    NodeResult result = executeNode(context, node.type(), params);
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

    private NodeResult executeNode(RunContext context, String type, Map<String, Object> params) throws Exception {
        return switch (type) {
            case "search_image" -> searchProductImage(context.tenantId(), text(params.get("keyword")));
            case "image_understand" -> understandImage(context.tenantId(), text(params.get("image")),
                    text(params.get("question")));
            case "llm_text" -> {
                var selection = modelCatalogService.resolve(context.userId(), ModelCatalogService.PURPOSE_CHAT);
                ChatClient client = ChatClient.builder(chatModelFactory.model(selection.config())).build();
                String answer = client.prompt().user(text(params.get("prompt"))).call().content();
                yield new NodeResult(answer == null ? "" : answer, "文本已生成");
            }
            case "web_search" -> webSearch(text(params.get("query")));
            case "poster_render" -> renderPoster(context, text(params.get("image")), text(params.get("copy")));
            case "start" -> new NodeResult(toJson(context.input()), "开始");
            case "http_request" -> httpRequest(context, params);
            case "db_query" -> dbQuery(text(params.get("sql")));
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

    private NodeResult understandImage(String tenantId, String imageUrl, String question) {
        String analysis = imageAnalysisService.analyze(tenantId, imageUrl, question).summary();
        if (analysis == null || analysis.isBlank()) {
            return new NodeResult("商品画像不可用，请基于关键词直接生成。", "图片理解不可用，已降级");
        }
        return new NodeResult(analysis, "商品画像已生成");
    }

    private NodeResult webSearch(String query) throws Exception {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("搜索关键词为空");
        WebSearchTool tool = new WebSearchTool(searchProvider, searchApiKey);
        String result = tool.searchWeb(query);
        if (result == null || result.isBlank()) throw new IllegalStateException("联网搜索没有返回结果");
        return new NodeResult(result, "联网搜索完成");
    }

    private NodeResult renderPoster(RunContext context, String imageUrl, String copyJson) throws Exception {
        if (imageUrl == null || imageUrl.isBlank()) throw new IllegalArgumentException("海报缺少商品图输入");
        JsonNode node = parseLooseJson(copyJson);
        PosterComposer.PosterCopy copy = new PosterComposer.PosterCopy(
                textOrDefault(node.path("title").asText(), "精选好物"),
                textOrDefault(node.path("subtitle").asText(), "用心选品 · 品质之选"),
                listOfText(node.path("points"), List.of("品质保障", "匠心工艺", "即刻拥有")),
                textOrDefault(node.path("palette").path("bgFrom").asText(), "#EAF1FF"),
                textOrDefault(node.path("palette").path("bgTo").asText(), "#D6E6FF"),
                textOrDefault(node.path("palette").path("accent").asText(), "#2E6BE6"),
                textOrDefault(node.path("palette").path("text").asText(), "#16224A"));
        byte[] productImage = downloadImage(imageUrl);
        byte[] poster = PosterComposer.compose(productImage, copy);
        Artifact artifact = storeArtifact(context, poster);
        return new NodeResult(artifact.objectKey(), "海报已生成");
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

    // ---------------------------------------------------------------- 参数引用与工具

    /** 替换 params 中的 "{{input.字段}}" 与 "{{节点key.output}}" 引用；整体引用还原原值，嵌套结构递归处理。 */
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
        int dot = ref.lastIndexOf('.');
        if (dot > 0 && ref.substring(dot + 1).equals("output")) {
            return outputs.getOrDefault(ref.substring(0, dot), "");
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
        return jdbcTemplate.query("""
                        SELECT * FROM workflow_run WHERE tenant_id = ? AND user_id = ?
                        ORDER BY created_at DESC LIMIT ?
                        """, (rs, rowNum) -> mapRow(rs), tenantId, userId, Math.max(1, Math.min(limit, 50)))
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
        return new WorkflowRunRow(rs.getString("id"), rs.getString("tenant_id"), rs.getString("user_id"),
                rs.getString("workflow_key"), rs.getInt("definition_version"), rs.getString("status"),
                rs.getString("current_step"),
                parseNode(rs.getString("input")),
                parseNode(rs.getString("steps")),
                parseNode(rs.getString("artifacts")),
                rs.getString("error"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
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
            String url = "";
            if (withArtifactUrl && !objectKey.isBlank()) {
                url = presignArtifact(row.tenantId(), row.userId(), row.id(), objectKey,
                        "file".equals(type) ? name : null);
            }
            artifacts.add(new ArtifactView(type, name, url));
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
                row.createdAt(), row.updatedAt());
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
