package com.zwx.zwxagent.creative.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zwx.zwxagent.platform.workflow.WorkflowService;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.platform.security.Role;
import static org.mockito.Mockito.*;

class CreativeCanvasServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void partialRunChecksBothPersistedParentsInsteadOfTrustingClientSuccessFlags() throws Exception {
        var graph = readGraph("""
                {"nodes":[
                  {"id":"a","type":"image_generate","data":{"label":"上级 A","status":"SUCCEEDED","prompt":"海边","modelRowId":7}},
                  {"id":"b","type":"image_generate","data":{"label":"上级 B","status":"SUCCEEDED","prompt":"山顶","modelRowId":7}},
                  {"id":"c","type":"video_generate","data":{"prompt":"推镜","modelRowId":9}}],
                 "edges":[{"source":"a","target":"c","sourceHandle":"text-out","targetHandle":"text-in"},
                   {"source":"b","target":"c","sourceHandle":"flow-out","targetHandle":"flow-in"}]}
                """);
        var jdbc = mock(JdbcTemplate.class);
        var workflows = mock(WorkflowService.class);
        var service = spy(new CreativeCanvasService(jdbc, mapper, mock(CreativeProjectService.class), workflows));
        var actor = new CurrentActor("user", "tenant", "test", Role.USER);
        var project = java.util.UUID.randomUUID();
        doReturn(new CreativeCanvasService.CanvasView(project, graph, mapper.createObjectNode(), 1, null))
                .when(service).get(actor, project);
        var persistedB = new java.util.concurrent.atomic.AtomicReference<>("RUNNING");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String parent = (String) invocation.getArguments()[4];
            return java.util.List.of(java.util.Map.of("status", parent.equals("b") ? persistedB.get() : "SUCCEEDED", "output", "{}"));
        });
        var request = new CreativeCanvasService.RunRequest("node", "c", 1L, "test");
        var error = Assertions.assertThrows(IllegalArgumentException.class, () -> service.validateRun(actor, project, request));
        Assertions.assertTrue(error.getMessage().contains("上级 B"));
        verifyNoInteractions(workflows);
        persistedB.set("WAITING_SELECTION");
        Assertions.assertThrows(IllegalArgumentException.class, () -> service.validateRun(actor, project, request));
        persistedB.set("SUCCEEDED");
        Assertions.assertEquals(1, service.validateRun(actor, project, request).executableNodes());
    }

    private com.fasterxml.jackson.databind.JsonNode readGraph(String json) throws Exception {
        var graph = mapper.readTree(json);
        var nodes = (com.fasterxml.jackson.databind.node.ArrayNode) graph.path("nodes");
        var edges = (com.fasterxml.jackson.databind.node.ArrayNode) graph.path("edges");
        java.util.List<String> roots = new java.util.ArrayList<>();
        for (var node : nodes) {
            boolean incoming = false;
            for (var edge : edges) if (edge.path("target").asText().equals(node.path("id").asText())) incoming = true;
            if (!incoming) roots.add(node.path("id").asText());
        }
        nodes.addObject().put("id", "start").put("type", "start");
        for (String id : roots) edges.addObject().put("source", "start").put("target", id)
                .put("sourceHandle", "flow-out").put("targetHandle", "flow-in");
        return graph;
    }

    @Test
    void requiresExactlyOneStartAndRejectsIncomingConnections() throws Exception {
        var missing = mapper.readTree("{\"nodes\":[],\"edges\":[]}");
        Assertions.assertThrows(IllegalArgumentException.class, () -> CreativeCanvasService.validate(
                new CreativeCanvasService.SaveRequest(missing, null, 0L), mapper));
        var doubleStart = mapper.readTree("""
                {"nodes":[{"id":"s1","type":"start"},{"id":"s2","type":"start"}],"edges":[]}
                """);
        Assertions.assertThrows(IllegalArgumentException.class, () -> CreativeCanvasService.validate(
                new CreativeCanvasService.SaveRequest(doubleStart, null, 0L), mapper));
        var incoming = mapper.readTree("""
                {"nodes":[{"id":"s","type":"start"},{"id":"a","type":"text_input"}],
                 "edges":[{"source":"a","target":"s","sourceHandle":"flow-out","targetHandle":"flow-in"}]}
                """);
        Assertions.assertThrows(IllegalArgumentException.class, () -> CreativeCanvasService.validate(
                new CreativeCanvasService.SaveRequest(incoming, null, 0L), mapper));
    }

    @Test
    void requiresAllExternalAncestorsForNodeAndFromHereEvenForTextAndControlEdges() throws Exception {
        var graph = readGraph("""
                {"nodes":[{"id":"a","type":"image_generate"},{"id":"b","type":"image_generate"},
                   {"id":"c","type":"video_generate"},{"id":"text","type":"text_input"}],
                 "edges":[{"source":"a","target":"text","sourceHandle":"flow-out","targetHandle":"flow-in"},
                   {"source":"text","target":"c","sourceHandle":"text-out","targetHandle":"text-in"},
                   {"source":"b","target":"c","sourceHandle":"flow-out","targetHandle":"flow-in"}]}
                """);
        Assertions.assertEquals(java.util.Set.of("a", "b"), CreativeCanvasService.requiredExternalParents(graph, "node", "c"));
        Assertions.assertEquals(java.util.Set.of("b"), CreativeCanvasService.requiredExternalParents(graph, "from_here", "a"));
        Assertions.assertTrue(CreativeCanvasService.requiredExternalParents(graph, "all", null).isEmpty());
        for (String status : java.util.List.of("PENDING", "QUEUED", "RUNNING", "WAITING_EXTERNAL", "WAITING_SELECTION", "FAILED", "CANCELLED", "SKIPPED")) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> CreativeCanvasService.requireCompletedParent("上级", status));
        }
        Assertions.assertDoesNotThrow(() -> CreativeCanvasService.requireCompletedParent("上级", "SUCCEEDED"));
    }

    @Test
    void startAndInputsBecomeNonModelStepsAndUnreachableNodesCannotRun() throws Exception {
        var graph = readGraph("""
                {"nodes":[{"id":"text","type":"text_input","data":{"text":"海边"}},
                 {"id":"image","type":"image_generate","data":{"modelRowId":7}}],
                 "edges":[{"source":"text","target":"image","sourceHandle":"text-out","targetHandle":"text-in"}]}
                """);
        var prepared = CreativeCanvasService.prepareGraph(graph, "all", null);
        Assertions.assertEquals(java.util.Set.of("image"), prepared.nodeIds());
        Assertions.assertTrue(prepared.graph().nodes().stream().anyMatch(node -> node.type().equals("start") && node.params().isEmpty()));
        Assertions.assertTrue(prepared.graph().nodes().stream().anyMatch(node -> node.key().equals("text") && node.type().equals("canvas_input")));
        ((com.fasterxml.jackson.databind.node.ArrayNode) graph.path("edges")).remove(1);
        Assertions.assertThrows(IllegalArgumentException.class, () -> CreativeCanvasService.prepareGraph(graph, "all", null));
    }

    @Test
    void textConnectionsReadRecursivePromptsWithoutRequiringMediaOutputs() throws Exception {
        var graph = readGraph("""
                {"nodes":[
                  {"id":"text-1","type":"text_input","data":{"text":"日落海边"}},
                  {"id":"video-1","type":"video_generate","data":{"prompt":"缓慢推镜","modelRowId":9}},
                  {"id":"image-1","type":"image_generate","data":{"prompt":"","modelRowId":7}}],
                 "edges":[
                   {"source":"text-1","target":"video-1","sourceHandle":"text-out","targetHandle":"text-in"},
                   {"source":"video-1","target":"image-1","sourceHandle":"text-out","targetHandle":"text-in"}]}
                """);
        Assertions.assertDoesNotThrow(() -> CreativeCanvasService.validate(
                new CreativeCanvasService.SaveRequest(graph, null, 0L), mapper));
        var prepared = CreativeCanvasService.prepareGraph(graph, "node", "image-1");
        Assertions.assertEquals("日落海边\n\n缓慢推镜", prepared.graph().nodes().getFirst().params().get("prompt"));
        Assertions.assertFalse(prepared.graph().nodes().getFirst().params().containsKey("imageAssetIds"));
    }

    @Test
    void rejectsCrossTypePorts() throws Exception {
        var graph = readGraph("""
                {"nodes":[{"id":"a","type":"text_input"},{"id":"b","type":"image_generate"}],
                 "edges":[{"source":"a","target":"b","sourceHandle":"text-out","targetHandle":"image-in"}]}
                """);
        Assertions.assertThrows(IllegalArgumentException.class, () -> CreativeCanvasService.validate(
                new CreativeCanvasService.SaveRequest(graph, null, 0L), mapper));
    }

    @Test
    void acceptsAConnectedAcyclicCreativeGraph() throws Exception {
        var graph = readGraph("""
                {"nodes":[
                  {"id":"asset-1","type":"asset_input"},
                  {"id":"video-1","type":"video_generate"}],
                 "edges":[{"source":"asset-1","target":"video-1"}]}
                """);
        Assertions.assertDoesNotThrow(() -> CreativeCanvasService.validate(
                new CreativeCanvasService.SaveRequest(graph, null, 0L), mapper));
    }

    @Test
    void rejectsDuplicateNodeIdsAndUnknownTypes() throws Exception {
        var duplicate = readGraph("""
                {"nodes":[{"id":"x","type":"text_input"},{"id":"x","type":"text_input"}],"edges":[]}
                """);
        Assertions.assertThrows(IllegalArgumentException.class, () -> CreativeCanvasService.validate(
                new CreativeCanvasService.SaveRequest(duplicate, null, 0L), mapper));

        var unknown = readGraph("""
                {"nodes":[{"id":"x","type":"terminal"}],"edges":[]}
                """);
        Assertions.assertThrows(IllegalArgumentException.class, () -> CreativeCanvasService.validate(
                new CreativeCanvasService.SaveRequest(unknown, null, 0L), mapper));
    }

    @Test
    void rejectsMissingEndpointsAndCycles() throws Exception {
        var missing = readGraph("""
                {"nodes":[{"id":"x","type":"text_input"}],"edges":[{"source":"x","target":"y"}]}
                """);
        Assertions.assertThrows(IllegalArgumentException.class, () -> CreativeCanvasService.validate(
                new CreativeCanvasService.SaveRequest(missing, null, 0L), mapper));

        var cyclic = readGraph("""
                {"nodes":[{"id":"x","type":"text_input"},{"id":"y","type":"text_input"}],
                 "edges":[{"source":"x","target":"y"},{"source":"y","target":"x"}]}
                """);
        Assertions.assertThrows(IllegalArgumentException.class, () -> CreativeCanvasService.validate(
                new CreativeCanvasService.SaveRequest(cyclic, null, 0L), mapper));
    }

    @Test
    void convertsCanvasChainIntoExecutableWorkflowSnapshot() throws Exception {
        var graph = readGraph("""
                {"nodes":[
                  {"id":"image-1","type":"image_generate","position":{"x":10,"y":20},
                   "data":{"label":"首图","prompt":"一只猫","modelRowId":7,"size":"1024*1024"}},
                  {"id":"video-1","type":"video_generate","position":{"x":300,"y":20},
                   "data":{"label":"视频","prompt":"缓慢推镜","modelRowId":9,"seconds":5}}],
                 "edges":[{"source":"image-1","target":"video-1"}]}
                """);
        var prepared = CreativeCanvasService.prepareGraph(graph, "all", null);
        Assertions.assertEquals(3, prepared.graph().nodes().size());
        Assertions.assertEquals("media_image_generate", prepared.graph().nodes().get(0).type());
        Assertions.assertEquals("media_video_generate", prepared.graph().nodes().get(1).type());
        Assertions.assertEquals(java.util.List.of("{{image-1.assetId}}"),
                prepared.graph().nodes().get(1).params().get("imageAssetIds"));
        Assertions.assertEquals(2, prepared.graph().edges().size());
    }

    @Test
    void requiresSelectedAncestorWhenRunningOnlyDownstreamNode() throws Exception {
        var graph = readGraph("""
                {"nodes":[
                  {"id":"image-1","type":"image_generate","data":{"label":"首图","prompt":"猫","modelRowId":7}},
                  {"id":"video-1","type":"video_generate","data":{"label":"视频","prompt":"推镜","modelRowId":9}}],
                 "edges":[{"source":"image-1","target":"video-1"}]}
                """);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> CreativeCanvasService.prepareGraph(graph, "node", "video-1"));
    }
}
