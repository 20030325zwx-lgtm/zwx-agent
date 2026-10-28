package com.zwx.zwxagent.platform.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwx.zwxagent.knowledge.image.ImageAnalysisService;
import com.zwx.zwxagent.capability.mcp.McpServerConfigurationService;
import com.zwx.zwxagent.capability.mcp.McpTools;
import com.zwx.zwxagent.capability.tools.UrlAccessPolicy;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 原生工作流引擎试点：代码注册的工作流定义 + 持久化的运行（步骤状态与产物）。
 * 试点工作流 product-poster：搜集商品图（MCP 搜索）→ 商品理解（图片 MCP）→
 * 文案规划（LLM）→ 海报合成（Java2D）→ 产物登记（OSS）。
 * 定义/版本拆表与画布编辑器留待原生画布阶段。
 */
@Service
@Slf4j
public class WorkflowService {

    public record StepDef(String key, String name, String description) {
    }

    public record WorkflowDefinition(String key, String name, String description, int version,
                                     List<StepDef> steps) {
    }

    public record StepState(String key, String name, String status, String message,
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

    private record WorkflowRunRow(String id, String tenantId, String userId, String workflowKey, int definitionVersion,
                                  String status, String currentStep, JsonNode input, JsonNode steps,
                                  JsonNode artifacts, String error, Instant createdAt, Instant updatedAt) {
    }

    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

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
                           @Value("${app.oss.bucket}") String ossBucket) {
        this.jdbcTemplate = jdbcTemplate;
        this.mcpServerConfigurationService = mcpServerConfigurationService;
        this.imageAnalysisService = imageAnalysisService;
        this.modelCatalogService = modelCatalogService;
        this.chatModelFactory = chatModelFactory;
        this.urlAccessPolicy = urlAccessPolicy;
        this.ossClientProvider = ossClientProvider;
        this.ossBucket = ossBucket;
    }

    // ---------------------------------------------------------------- 定义

    public List<WorkflowDefinition> definitions() {
        return List.of(new WorkflowDefinition(
                "product-poster", "商品海报生成", "搜集商品图，理解商品卖点，自动生成一张宣传海报。",
                1,
                List.of(
                        new StepDef("search_image", "搜集商品图", "通过图片搜索服务获取商品参考图"),
                        new StepDef("understand_product", "商品理解", "识别商品名称、卖点与建议配色"),
                        new StepDef("compose_copy", "文案规划", "生成海报标题、副标题与要点"),
                        new StepDef("render_poster", "海报合成", "排版合成海报图片并登记产物")
                )));
    }

    private WorkflowDefinition definition(String key) {
        return definitions().stream().filter(item -> item.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知的工作流：" + key));
    }

    // ---------------------------------------------------------------- 运行

    public WorkflowRunView start(String tenantId, String userId, String workflowKey, String keyword) {
        WorkflowDefinition definition = definition(workflowKey);
        String trimmed = keyword == null ? "" : keyword.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("请填写商品关键词");
        if (trimmed.length() > 64) throw new IllegalArgumentException("关键词过长");

        String id = UUID.randomUUID().toString();
        // 引擎执行期会就地更新步骤状态，必须是可变列表
        List<StepState> steps = new ArrayList<>(definition.steps().stream()
                .map(step -> new StepState(step.key(), step.name(), "PENDING", "", null, null))
                .toList());
        Map<String, Object> input = Map.of("keyword", trimmed);
        jdbcTemplate.update("""
                        INSERT INTO workflow_run (id, tenant_id, user_id, workflow_key, definition_version,
                                                  status, current_step, input, steps, artifacts)
                        VALUES (?, ?, ?, ?, ?, 'RUNNING', ?, ?::jsonb, ?::jsonb, '[]'::jsonb)
                        """,
                id, tenantId, userId, definition.key(), definition.version(),
                definition.steps().getFirst().key(),
                toJson(input), toJson(steps));
        executor.submit(() -> execute(new RunContext(id, tenantId, userId, definition, trimmed, steps)));
        return view(id, tenantId, userId);
    }

    private record RunContext(String id, String tenantId, String userId, WorkflowDefinition definition,
                              String keyword, List<StepState> steps) {
    }

    private void execute(RunContext context) {
        try {
            StepState search = step(context, "search_image", "RUNNING");
            persistSteps(context, search);
            String imageUrl = searchProductImage(context.tenantId(), context.userId(), context.keyword());
            search = finish(search, "SUCCEEDED", "已获取商品参考图");
            persistSteps(context, search);

            StepState understand = step(context, "understand_product", "RUNNING");
            persistSteps(context, understand);
            String analysis = imageAnalysisService.analyze(context.tenantId(), imageUrl,
                    "这是什么商品？给出商品名称、品类、三个核心卖点、适合海报的配色建议（十六进制色值）。").summary();
            String productProfile = analysis == null || analysis.isBlank()
                    ? "商品：" + context.keyword() + "（图片理解服务不可用，按关键词直接生成）"
                    : analysis;
            understand = finish(understand, analysis == null || analysis.isBlank() ? "SKIPPED" : "SUCCEEDED",
                    analysis == null || analysis.isBlank() ? "图片理解不可用，已降级" : "商品画像已生成");
            persistSteps(context, understand);

            StepState copy = step(context, "compose_copy", "RUNNING");
            persistSteps(context, copy);
            PosterComposer.PosterCopy posterCopy = composeCopy(context.userId(), context.keyword(), productProfile);
            copy = finish(copy, "SUCCEEDED", "文案与配色已生成");
            persistSteps(context, copy);

            StepState render = step(context, "render_poster", "RUNNING");
            persistSteps(context, render);
            byte[] productImage = downloadImage(imageUrl);
            byte[] poster = PosterComposer.compose(productImage, posterCopy);
            Artifact artifact = storeArtifact(context, poster);
            render = finish(render, "SUCCEEDED", "海报已生成");
            persistSteps(context, render);

            finishRun(context.id(), "SUCCEEDED", List.of(artifact), null);
        } catch (Exception exception) {
            log.warn("工作流运行失败：runId={}", context.id(), exception);
            markRemainingFailed(context);
            persistSteps(context);
            finishRun(context.id(), "FAILED", List.of(), truncate(exception.getMessage(), 400));
        }
    }

    // ---------------------------------------------------------------- 步骤实现

    private String searchProductImage(String tenantId, String userId, String keyword) throws Exception {
        String mcpFailure = "MCP 搜索不可用";
        try (McpTools tools = mcpServerConfigurationService.toolsFor(tenantId)) {
            if (tools.callbacks().length > 0) {
                ToolCallback callback = Arrays.stream(tools.callbacks())
                        .filter(candidate -> candidate.getToolDefinition().name().endsWith("searchImage"))
                        .findFirst().orElse(null);
                if (callback != null) {
                    String raw = callback.call(objectMapper.writeValueAsString(Map.of("query", keyword)));
                    List<String> urls = extractUrls(raw);
                    for (String candidate : urls) {
                        if (isProbableImage(candidate)) return candidate;
                    }
                    mcpFailure = "MCP 搜索无可用结果（如未配置搜索服务密钥）";
                }
            }
        }
        // 回退：Wikimedia Commons 开放检索（无需密钥，内容以英文为主，中文关键词先译英）
        List<String> commons = searchCommons(keyword);
        if (commons.isEmpty()) {
            String english = translateKeyword(userId, keyword);
            if (!english.isEmpty()) commons = searchCommons(english);
        }
        for (String candidate : commons) {
            if (isProbableImage(candidate)) return candidate;
        }
        throw new IllegalStateException("图片搜索没有返回结果（" + mcpFailure + "，Commons 无结果），请更换关键词重试");
    }

    private String translateKeyword(String userId, String keyword) {
        try {
            var selection = modelCatalogService.resolve(userId, ModelCatalogService.PURPOSE_CHAT);
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

    private List<String> extractUrls(String raw) {
        List<String> urls = new ArrayList<>();
        if (raw == null || raw.isBlank()) return urls;
        String text = raw;
        try {
            JsonNode node = objectMapper.readTree(raw);
            JsonNode payload = node;
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
        String lower = path.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp");
    }

    private PosterComposer.PosterCopy composeCopy(String userId, String keyword, String productProfile) {
        var selection = modelCatalogService.resolve(userId, ModelCatalogService.PURPOSE_CHAT);
        ChatClient client = ChatClient.builder(chatModelFactory.model(selection.config())).build();
        String prompt = """
                你是海报文案策划。根据商品信息生成一张宣传海报的文案，只输出 JSON，不要解释：
                {"title":"主标题(不超过12字)","subtitle":"副标题(不超过20字)","points":["卖点1","卖点2","卖点3"],
                 "palette":{"bgFrom":"#十六进制","bgTo":"#十六进制","accent":"#十六进制","text":"#十六进制"}}
                商品关键词：%s
                商品信息：%s
                """.formatted(keyword, productProfile);
        String answer = client.prompt().user(prompt).call().content();
        JsonNode node = parseLooseJson(answer);
        JsonNode palette = node.path("palette");
        return new PosterComposer.PosterCopy(
                textOrDefault(node.path("title").asText(""), keyword),
                textOrDefault(node.path("subtitle").asText(""), "用心选品 · 品质之选"),
                listOfText(node.path("points"), List.of("品质保障", "匠心工艺", "即刻拥有")),
                textOrDefault(palette.path("bgFrom").asText(""), "#EAF1FF"),
                textOrDefault(palette.path("bgTo").asText(""), "#D6E6FF"),
                textOrDefault(palette.path("accent").asText(""), "#2E6BE6"),
                textOrDefault(palette.path("text").asText(""), "#16224A"));
    }

    /** 检索来源为外部公开媒体 CDN：这类主机的直连解析在本机代理/VPN 环境常为保留段 IP，按可信来源放行 */
    private static final java.util.Set<String> TRUSTED_IMAGE_HOSTS =
            java.util.Set.of("wikimedia.org", "wikipedia.org", "pexels.com", "pixabay.com", "openverse.org");

    private byte[] downloadImage(String url) throws Exception {
        URI target = URI.create(url);
        String host = target.getHost() == null ? "" : target.getHost().toLowerCase(java.util.Locale.ROOT);
        boolean trusted = "https".equalsIgnoreCase(target.getScheme())
                && TRUSTED_IMAGE_HOSTS.stream().anyMatch(known -> host.equals(known) || host.endsWith("." + known));
        if (!trusted) {
            urlAccessPolicy.validateHttpUrl(url); // 非可信来源维持公网 SSRF 校验
        }
        String validated = url;
        HttpResponse<byte[]> response = httpClient.send(HttpRequest.newBuilder(URI.create(validated))
                        .timeout(Duration.ofSeconds(20))
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
        String objectKey = "workflow/" + context.userId() + "/" + context.id() + "/poster.png";
        var metadata = new com.aliyun.oss.model.ObjectMetadata();
        metadata.setContentLength((long) poster.length);
        metadata.setContentType("image/png");
        ossClientProvider.getClient().putObject(ossBucket, objectKey, new ByteArrayInputStream(poster), metadata);
        return new Artifact("poster", "海报.png", objectKey);
    }

    public String presignArtifact(String tenantId, String userId, String runId, String objectKey) {
        if (!objectKey.startsWith("workflow/" + userId + "/" + runId + "/")) {
            throw new IllegalArgumentException("无权访问该产物");
        }
        var request = new com.aliyun.oss.model.GeneratePresignedUrlRequest(ossBucket, objectKey,
                com.aliyun.oss.HttpMethod.GET);
        request.setExpiration(java.util.Date.from(Instant.now().plus(Duration.ofHours(2))));
        return ossClientProvider.getClient().generatePresignedUrl(request).toString();
    }

    // ---------------------------------------------------------------- 状态持久化

    private StepState step(RunContext context, String key, String status) {
        StepState current = context.steps().stream().filter(item -> item.key().equals(key)).findFirst().orElseThrow();
        return new StepState(current.key(), current.name(), status, current.message(), Instant.now(), null);
    }

    private StepState finish(StepState state, String status, String message) {
        return new StepState(state.key(), state.name(), status, message, state.startedAt(), Instant.now());
    }

    private void persistSteps(RunContext context, StepState... updates) {
        Map<String, StepState> byKey = new LinkedHashMap<>();
        context.steps().forEach(item -> byKey.put(item.key(), item));
        for (StepState update : updates) byKey.put(update.key(), update);
        context.steps().clear();
        context.steps().addAll(byKey.values());
        jdbcTemplate.update("""
                        UPDATE workflow_run
                        SET steps = ?::jsonb, current_step = COALESCE((
                            SELECT key FROM jsonb_to_recordset(steps) AS s(key text, status text)
                            WHERE status = 'RUNNING' LIMIT 1), current_step),
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, toJson(context.steps()), context.id());
    }

    private void markRemainingFailed(RunContext context) {
        List<StepState> updated = context.steps().stream()
                .map(item -> "PENDING".equals(item.status()) || "RUNNING".equals(item.status())
                        ? finish(item, "FAILED", "前序步骤失败") : item)
                .toList();
        context.steps().clear();
        context.steps().addAll(updated);
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
                node.path("message").asText(), instantOrNull(node, "startedAt"), instantOrNull(node, "finishedAt"))));
        List<ArtifactView> artifacts = new ArrayList<>();
        row.artifacts().forEach(node -> {
            String objectKey = node.path("objectKey").asText();
            String url = withArtifactUrl && !objectKey.isBlank() ? presignArtifact(row.tenantId(), row.userId(), row.id(), objectKey) : "";
            artifacts.add(new ArtifactView(node.path("type").asText(), node.path("name").asText(), url));
        });
        Map<String, Object> input = objectMapper.convertValue(row.input(), new com.fasterxml.jackson.core.type.TypeReference<>() {
        });
        WorkflowDefinition definition = definition(row.workflowKey());
        return new WorkflowRunView(row.id(), row.workflowKey(), definition.name(), row.definitionVersion(),
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

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
