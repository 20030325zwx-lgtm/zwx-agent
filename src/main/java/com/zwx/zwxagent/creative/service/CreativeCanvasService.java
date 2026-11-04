package com.zwx.zwxagent.creative.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwx.zwxagent.creative.domain.CreativeConflictException;
import com.zwx.zwxagent.creative.domain.ParameterSchema;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.platform.workflow.WorkflowService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CreativeCanvasService {

    private static final int MAX_NODES = 60;
    private static final int MAX_EDGES = 120;
    private static final int MAX_JSON_CHARS = 1_000_000;
    private static final Set<String> NODE_TYPES = Set.of(
            "asset_input", "text_input", "image_generate", "image_edit", "video_generate");
    private static final Set<String> GENERATION_TYPES = Set.of(
            "image_generate", "image_edit", "video_generate");

    public record CanvasView(UUID projectId, JsonNode graph, JsonNode viewport, long revision,
                             Instant updatedAt) {}
    public record SaveRequest(JsonNode graph, JsonNode viewport, Long expectedRevision) {}
    public record RunRequest(String scope, String nodeId, Long expectedRevision, String idempotencyKey) {}
    public record ValidationView(int executableNodes, List<String> warnings) {}
    public record CandidateView(String nodeId, List<Map<String, Object>> assets) {}
    public record CanvasRunView(UUID id, UUID projectId, long canvasRevision, String scope,
                                String nodeId, WorkflowService.WorkflowRunView run,
                                List<CandidateView> candidates, Instant createdAt) {}

    record PreparedGraph(WorkflowService.WorkflowGraph graph, Set<String> nodeIds,
                         Set<UUID> literalAssetIds) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CreativeProjectService projects;
    private final WorkflowService workflows;

    public CreativeCanvasService(JdbcTemplate jdbc, ObjectMapper mapper, CreativeProjectService projects,
                                 WorkflowService workflows) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.projects = projects;
        this.workflows = workflows;
    }

    public CanvasView get(CurrentActor actor, UUID projectId) {
        projects.get(actor, projectId);
        return jdbc.query("""
                SELECT graph::text, viewport::text, revision, updated_at
                FROM creative_canvas WHERE project_id = ? AND tenant_id = ? AND user_id = ?
                """, rs -> rs.next()
                ? new CanvasView(projectId, read(rs.getString(1)), read(rs.getString(2)),
                        rs.getLong(3), rs.getTimestamp(4).toInstant())
                : new CanvasView(projectId, emptyGraph(), emptyViewport(), 0, null),
                projectId, actor.tenantId(), actor.userId());
    }

    public CanvasView save(CurrentActor actor, UUID projectId, SaveRequest request) {
        var project = projects.get(actor, projectId);
        if ("ARCHIVED".equals(project.status())) throw new CreativeConflictException("已归档项目不能编辑画布");
        validate(request, mapper);
        long expected = request.expectedRevision() == null ? 0 : request.expectedRevision();
        int changed;
        if (expected == 0) {
            changed = jdbc.update("""
                    INSERT INTO creative_canvas (id, project_id, tenant_id, user_id, graph, viewport, revision)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, 1)
                    ON CONFLICT (project_id) DO NOTHING
                    """, UUID.randomUUID(), projectId, actor.tenantId(), actor.userId(),
                    write(request.graph()), write(request.viewport() == null ? emptyViewport() : request.viewport()));
        } else {
            changed = jdbc.update("""
                    UPDATE creative_canvas SET graph = ?::jsonb, viewport = ?::jsonb,
                        revision = revision + 1, updated_at = now()
                    WHERE project_id = ? AND tenant_id = ? AND user_id = ? AND revision = ?
                    """, write(request.graph()), write(request.viewport() == null ? emptyViewport() : request.viewport()),
                    projectId, actor.tenantId(), actor.userId(), expected);
        }
        if (changed != 1) throw new CreativeConflictException("画布已被其他操作更新，请刷新后重试");
        return get(actor, projectId);
    }

    public ValidationView validateRun(CurrentActor actor, UUID projectId, RunRequest request) {
        CanvasView canvas = requireRevision(actor, projectId, request);
        PreparedGraph prepared = prepareGraph(canvas.graph(), normalizeScope(request.scope()), request.nodeId());
        validateAssets(actor, prepared.literalAssetIds());
        List<String> warnings = prepared.nodeIds().size() > 6
                ? List.of("本次将生成 " + prepared.nodeIds().size() + " 个媒体节点，请确认模型费用") : List.of();
        return new ValidationView(prepared.nodeIds().size(), warnings);
    }

    @Transactional
    public CanvasRunView startRun(CurrentActor actor, UUID projectId, RunRequest request) {
        String idempotencyKey = request == null ? null : request.idempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key 必填（1–128 字符）");
        }
        CanvasView canvas = requireRevision(actor, projectId, request);
        String scope = normalizeScope(request.scope());
        PreparedGraph prepared = prepareGraph(canvas.graph(), scope, request.nodeId());
        validateAssets(actor, prepared.literalAssetIds());

        UUID canvasRunId = UUID.randomUUID();
        int reserved = jdbc.update("""
                INSERT INTO creative_canvas_run
                    (id, project_id, tenant_id, user_id, canvas_revision, run_scope, from_node_id, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, user_id, idempotency_key) DO NOTHING
                """, canvasRunId, projectId, actor.tenantId(), actor.userId(), canvas.revision(), scope,
                request.nodeId(), idempotencyKey);
        if (reserved == 0) {
            UUID existing = jdbc.queryForObject("""
                    SELECT id FROM creative_canvas_run
                    WHERE tenant_id = ? AND user_id = ? AND idempotency_key = ?
                    """, UUID.class, actor.tenantId(), actor.userId(), idempotencyKey);
            return getRun(actor, projectId, existing);
        }

        WorkflowService.WorkflowRunView workflow = workflows.startSnapshot(
                actor.tenantId(), actor.userId(), "canvas-" + projectId, prepared.graph(),
                Map.of("canvasRunId", canvasRunId.toString(), "scope", scope), projectId);
        jdbc.update("UPDATE creative_canvas_run SET workflow_run_id = ?, updated_at = now() WHERE id = ?",
                workflow.id(), canvasRunId);
        return getRun(actor, projectId, canvasRunId);
    }

    public List<CanvasRunView> listRuns(CurrentActor actor, UUID projectId, Integer limit) {
        projects.get(actor, projectId);
        int safeLimit = Math.max(1, Math.min(limit == null ? 20 : limit, 100));
        return jdbc.queryForList("""
                SELECT id FROM creative_canvas_run
                WHERE project_id = ? AND tenant_id = ? AND user_id = ? AND workflow_run_id IS NOT NULL
                ORDER BY created_at DESC LIMIT ?
                """, UUID.class, projectId, actor.tenantId(), actor.userId(), safeLimit)
                .stream().map(id -> getRun(actor, projectId, id)).toList();
    }

    public CanvasRunView getRun(CurrentActor actor, UUID projectId, UUID canvasRunId) {
        projects.get(actor, projectId);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT id, canvas_revision, run_scope, from_node_id, workflow_run_id, created_at
                FROM creative_canvas_run
                WHERE id = ? AND project_id = ? AND tenant_id = ? AND user_id = ?
                """, canvasRunId, projectId, actor.tenantId(), actor.userId());
        String workflowRunId = row.get("workflow_run_id") == null ? null : String.valueOf(row.get("workflow_run_id"));
        if (workflowRunId == null) throw new IllegalStateException("画布运行仍在初始化，请稍后刷新");
        return new CanvasRunView(canvasRunId, projectId, ((Number) row.get("canvas_revision")).longValue(),
                String.valueOf(row.get("run_scope")), row.get("from_node_id") == null ? null : String.valueOf(row.get("from_node_id")),
                workflows.view(workflowRunId, actor.tenantId(), actor.userId()), candidates(workflowRunId),
                ((java.sql.Timestamp) row.get("created_at")).toInstant());
    }

    public CanvasRunView selectCandidate(CurrentActor actor, UUID projectId, UUID canvasRunId,
                                         String nodeId, UUID assetId) {
        CanvasRunView current = getRun(actor, projectId, canvasRunId);
        workflows.selectMediaCandidate(actor.tenantId(), actor.userId(), current.run().id(), nodeId,
                assetId == null ? null : assetId.toString());
        return getRun(actor, projectId, canvasRunId);
    }

    public CanvasRunView cancelRun(CurrentActor actor, UUID projectId, UUID canvasRunId) {
        CanvasRunView current = getRun(actor, projectId, canvasRunId);
        workflows.cancelRun(actor.tenantId(), actor.userId(), current.run().id());
        return getRun(actor, projectId, canvasRunId);
    }

    private CanvasView requireRevision(CurrentActor actor, UUID projectId, RunRequest request) {
        if (request == null) throw new IllegalArgumentException("缺少运行参数");
        CanvasView canvas = get(actor, projectId);
        if (canvas.revision() == 0) throw new IllegalArgumentException("请先保存画布");
        if (request.expectedRevision() == null || request.expectedRevision() != canvas.revision()) {
            throw new CreativeConflictException("画布已更新，请使用最新版本后再运行");
        }
        return canvas;
    }

    @SuppressWarnings("unchecked")
    private List<CandidateView> candidates(String workflowRunId) {
        return jdbc.query("""
                SELECT node_key, candidate_assets::text FROM workflow_node_execution
                WHERE run_id = ? AND status = 'WAITING_SELECTION' ORDER BY node_key
                """, (rs, rowNum) -> new CandidateView(rs.getString(1),
                mapper.convertValue(read(rs.getString(2)), List.class)), workflowRunId);
    }

    private void validateAssets(CurrentActor actor, Set<UUID> ids) {
        for (UUID id : ids) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT media_type, status FROM creative_asset
                    WHERE id = ? AND tenant_id = ? AND user_id = ?
                    """, id, actor.tenantId(), actor.userId());
            if (rows.isEmpty()) throw new IllegalArgumentException("素材不存在或无权使用：" + id);
            Map<String, Object> asset = rows.getFirst();
            if (!"READY".equals(String.valueOf(asset.get("status")))) {
                throw new IllegalArgumentException("素材尚未就绪：" + id);
            }
            if (!"IMAGE".equals(String.valueOf(asset.get("media_type")))) {
                throw new IllegalArgumentException("当前生成节点只接受图片素材：" + id);
            }
        }
    }

    static PreparedGraph prepareGraph(JsonNode graph, String scope, String selectedNodeId) {
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        graph.path("nodes").forEach(node -> nodes.put(node.path("id").asText(), node));
        List<JsonNode> edges = new ArrayList<>();
        graph.path("edges").forEach(edges::add);
        Set<String> included = includedNodes(nodes, edges, scope, selectedNodeId);
        List<WorkflowService.GraphNodeDef> workflowNodes = new ArrayList<>();
        Set<UUID> literalAssets = new LinkedHashSet<>();

        for (String id : included) {
            JsonNode node = nodes.get(id);
            JsonNode data = node.path("data");
            String type = node.path("type").asText();
            String prompt = effectivePrompt(id, nodes, edges, new HashSet<>());
            if (prompt.isBlank()) throw new IllegalArgumentException("节点「" + label(node) + "」缺少提示词");
            if (prompt.length() > ParameterSchema.MODEL_PROMPT_MAX) {
                throw new IllegalArgumentException("节点「" + label(node) + "」提示词超过 " + ParameterSchema.MODEL_PROMPT_MAX + " 个字符");
            }
            long modelRowId = data.path("modelRowId").asLong(0);
            if (modelRowId <= 0) throw new IllegalArgumentException("节点「" + label(node) + "」未选择生成模型");

            List<Object> references = new ArrayList<>();
            addAssetIds(data.path("referenceAssetIds"), references, literalAssets);
            for (JsonNode edge : edges) {
                if (!id.equals(edge.path("target").asText())) continue;
                String parentId = edge.path("source").asText();
                JsonNode parent = nodes.get(parentId);
                if (parent == null) continue;
                String parentType = parent.path("type").asText();
                if (isTextEdge(edge, parentType)) continue;
                if ("asset_input".equals(parentType)) {
                    addAssetIds(parent.path("data").path("assetIds"), references, literalAssets);
                } else if (GENERATION_TYPES.contains(parentType)) {
                    if (included.contains(parentId)) references.add("{{" + parentId + ".assetId}}");
                    else {
                        String selected = parent.path("data").path("selectedAssetId").asText("");
                        if (selected.isBlank()) throw new IllegalArgumentException(
                                "请先为上游节点「" + label(parent) + "」选择一个输出");
                        UUID assetId = UUID.fromString(selected);
                        references.add(assetId.toString());
                        literalAssets.add(assetId);
                    }
                }
            }
            references = references.stream().distinct().toList();
            if ("video_generate".equals(type) && references.size() > 1) {
                throw new IllegalArgumentException("视频节点「" + label(node) + "」只允许一个首帧");
            }
            if (!"video_generate".equals(type) && references.size() > 3) {
                throw new IllegalArgumentException("图片节点「" + label(node) + "」最多允许三张参考图");
            }
            if ("image_edit".equals(type) && references.isEmpty()) {
                throw new IllegalArgumentException("图片编辑节点「" + label(node) + "」需要参考图");
            }

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("prompt", prompt);
            params.put("modelRowId", modelRowId);
            if (!references.isEmpty()) params.put("imageAssetIds", references);
            if ("video_generate".equals(type)) params.put("seconds", data.path("seconds").asInt(5));
            else params.put("size", data.path("size").asText("1024*1024"));
            String workflowType = "video_generate".equals(type) ? "media_video_generate"
                    : references.isEmpty() && !"image_edit".equals(type) ? "media_image_generate" : "media_image_edit";
            workflowNodes.add(new WorkflowService.GraphNodeDef(id, workflowType, label(node), params,
                    node.path("position").path("x").asDouble(), node.path("position").path("y").asDouble()));
        }

        List<WorkflowService.GraphEdgeDef> workflowEdges = edges.stream()
                .filter(edge -> included.contains(edge.path("source").asText())
                        && included.contains(edge.path("target").asText()))
                .map(edge -> new WorkflowService.GraphEdgeDef(edge.path("source").asText(), edge.path("target").asText()))
                .distinct()
                .toList();
        return new PreparedGraph(new WorkflowService.WorkflowGraph(workflowNodes, workflowEdges), included, literalAssets);
    }

    private static Set<String> includedNodes(Map<String, JsonNode> nodes, List<JsonNode> edges,
                                             String scope, String selectedNodeId) {
        if ("all".equals(scope)) {
            Set<String> all = new LinkedHashSet<>();
            nodes.forEach((id, node) -> { if (GENERATION_TYPES.contains(node.path("type").asText())) all.add(id); });
            if (all.isEmpty()) throw new IllegalArgumentException("画布没有可运行的生成节点");
            return all;
        }
        JsonNode selected = nodes.get(selectedNodeId);
        if (selected == null || !GENERATION_TYPES.contains(selected.path("type").asText())) {
            throw new IllegalArgumentException("请选择一个生成节点");
        }
        Set<String> included = new LinkedHashSet<>();
        included.add(selectedNodeId);
        if ("from_here".equals(scope)) {
            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(selectedNodeId);
            while (!queue.isEmpty()) {
                String current = queue.remove();
                for (JsonNode edge : edges) {
                    if (!current.equals(edge.path("source").asText())) continue;
                    String target = edge.path("target").asText();
                    JsonNode targetNode = nodes.get(target);
                    if (targetNode != null && GENERATION_TYPES.contains(targetNode.path("type").asText())
                            && included.add(target)) queue.add(target);
                }
            }
        }
        return included;
    }

    private static boolean isTextEdge(JsonNode edge, String sourceType) {
        String handle = edge.path("sourceHandle").asText("");
        return "text-out".equals(handle) || (handle.isBlank() && "text_input".equals(sourceType));
    }

    private static String effectivePrompt(String id, Map<String, JsonNode> nodes,
                                          List<JsonNode> edges, Set<String> ancestors) {
        if (!ancestors.add(id)) throw new IllegalArgumentException("画布存在循环依赖");
        JsonNode node = nodes.get(id);
        List<String> parts = new ArrayList<>();
        for (JsonNode edge : edges) {
            JsonNode parent = nodes.get(edge.path("source").asText());
            if (id.equals(edge.path("target").asText()) && parent != null
                    && isTextEdge(edge, parent.path("type").asText())) {
                String text = effectivePrompt(parent.path("id").asText(), nodes, edges, new HashSet<>(ancestors));
                if (!text.isBlank()) parts.add(text);
            }
        }
        String local = node.path("data").path("text_input".equals(node.path("type").asText())
                ? "text" : "prompt").asText("").trim();
        if (!local.isBlank()) parts.add(local);
        return String.join("\n\n", parts);
    }

    private static void addAssetIds(JsonNode values, List<Object> references, Set<UUID> literalAssets) {
        if (!values.isArray()) return;
        for (JsonNode value : values) {
            UUID id;
            try { id = UUID.fromString(value.asText()); }
            catch (IllegalArgumentException exception) { throw new IllegalArgumentException("素材 ID 格式不正确"); }
            references.add(id.toString());
            literalAssets.add(id);
        }
    }

    private static String label(JsonNode node) {
        String label = node.path("data").path("label").asText("").trim();
        return label.isBlank() ? node.path("id").asText() : label;
    }

    private static String normalizeScope(String scope) {
        String value = scope == null ? "node" : scope.trim().toLowerCase();
        if (!Set.of("node", "from_here", "all").contains(value)) {
            throw new IllegalArgumentException("运行范围只支持 node/from_here/all");
        }
        return value;
    }

    static void validate(SaveRequest request, ObjectMapper mapper) {
        if (request == null || request.graph() == null || !request.graph().isObject()) {
            throw new IllegalArgumentException("缺少画布 graph");
        }
        JsonNode nodes = request.graph().path("nodes");
        JsonNode edges = request.graph().path("edges");
        if (!nodes.isArray() || !edges.isArray() || nodes.size() > MAX_NODES || edges.size() > MAX_EDGES) {
            throw new IllegalArgumentException("画布节点或连线数量无效");
        }
        if (jsonLength(request.graph(), mapper) > MAX_JSON_CHARS
                || request.viewport() != null && jsonLength(request.viewport(), mapper) > 4096) {
            throw new IllegalArgumentException("画布数据过大");
        }
        Set<String> ids = new HashSet<>();
        Map<String, String> types = new HashMap<>();
        for (JsonNode node : nodes) {
            String id = node.path("id").asText("");
            String type = node.path("type").asText("");
            if (id.isBlank() || id.length() > 128 || !ids.add(id)) {
                throw new IllegalArgumentException("画布节点标识无效或重复");
            }
            if (!NODE_TYPES.contains(type)) throw new IllegalArgumentException("不支持的画布节点类型：" + type);
            types.put(id, type);
        }
        Map<String, List<String>> outgoing = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        ids.forEach(id -> indegree.put(id, 0));
        for (JsonNode edge : edges) {
            String source = edge.path("source").asText();
            String target = edge.path("target").asText();
            if (!ids.contains(source) || !ids.contains(target) || source.equals(target)) {
                throw new IllegalArgumentException("连线引用了不存在的节点");
            }
            if (!GENERATION_TYPES.contains(types.get(target))) {
                throw new IllegalArgumentException("连线目标必须是生成节点");
            }
            boolean textEdge = isTextEdge(edge, types.get(source));
            String sourceHandle = edge.path("sourceHandle").asText("");
            String targetHandle = edge.path("targetHandle").asText("");
            if ((!sourceHandle.isBlank() && !sourceHandle.equals(textEdge ? "text-out" : "media-out"))
                    || (!targetHandle.isBlank() && !targetHandle.equals(textEdge ? "text-in" : "image-in"))
                    || (textEdge && "asset_input".equals(types.get(source)))
                    || (!textEdge && "text_input".equals(types.get(source)))) {
                throw new IllegalArgumentException("连线端口类型不匹配：提示词连接紫色端口，图片连接绿色端口");
            }
            if (!textEdge && "video_generate".equals(types.get(source))) {
                throw new IllegalArgumentException("当前版本不支持把视频结果作为模型输入");
            }
            outgoing.computeIfAbsent(source, ignored -> new ArrayList<>()).add(target);
            indegree.compute(target, (ignored, count) -> count + 1);
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        indegree.forEach((id, count) -> { if (count == 0) ready.add(id); });
        int visited = 0;
        while (!ready.isEmpty()) {
            String node = ready.remove();
            visited++;
            for (String next : outgoing.getOrDefault(node, List.of())) {
                int remaining = indegree.compute(next, (ignored, count) -> count - 1);
                if (remaining == 0) ready.add(next);
            }
        }
        if (visited != ids.size()) throw new IllegalArgumentException("画布存在循环依赖");
    }

    private static int jsonLength(JsonNode node, ObjectMapper mapper) {
        try { return mapper.writeValueAsString(node).length(); }
        catch (Exception e) { throw new IllegalArgumentException("画布数据格式无效", e); }
    }

    private JsonNode read(String json) {
        try { return mapper.readTree(json); }
        catch (Exception e) { throw new IllegalStateException("无法读取创作画布", e); }
    }

    private String write(JsonNode node) {
        try { return mapper.writeValueAsString(node); }
        catch (Exception e) { throw new IllegalArgumentException("画布数据格式无效", e); }
    }

    private JsonNode emptyGraph() {
        var graph = mapper.createObjectNode();
        graph.putArray("nodes");
        graph.putArray("edges");
        return graph;
    }

    private JsonNode emptyViewport() {
        var viewport = mapper.createObjectNode();
        viewport.put("x", 0);
        viewport.put("y", 0);
        viewport.put("zoom", 1);
        return viewport;
    }
}
