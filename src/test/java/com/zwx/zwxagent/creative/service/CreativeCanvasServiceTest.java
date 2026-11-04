package com.zwx.zwxagent.creative.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CreativeCanvasServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void textConnectionsReadRecursivePromptsWithoutRequiringMediaOutputs() throws Exception {
        var graph = mapper.readTree("""
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
        var graph = mapper.readTree("""
                {"nodes":[{"id":"a","type":"text_input"},{"id":"b","type":"image_generate"}],
                 "edges":[{"source":"a","target":"b","sourceHandle":"text-out","targetHandle":"image-in"}]}
                """);
        Assertions.assertThrows(IllegalArgumentException.class, () -> CreativeCanvasService.validate(
                new CreativeCanvasService.SaveRequest(graph, null, 0L), mapper));
    }

    @Test
    void acceptsAConnectedAcyclicCreativeGraph() throws Exception {
        var graph = mapper.readTree("""
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
        var duplicate = mapper.readTree("""
                {"nodes":[{"id":"x","type":"text_input"},{"id":"x","type":"text_input"}],"edges":[]}
                """);
        Assertions.assertThrows(IllegalArgumentException.class, () -> CreativeCanvasService.validate(
                new CreativeCanvasService.SaveRequest(duplicate, null, 0L), mapper));

        var unknown = mapper.readTree("""
                {"nodes":[{"id":"x","type":"terminal"}],"edges":[]}
                """);
        Assertions.assertThrows(IllegalArgumentException.class, () -> CreativeCanvasService.validate(
                new CreativeCanvasService.SaveRequest(unknown, null, 0L), mapper));
    }

    @Test
    void rejectsMissingEndpointsAndCycles() throws Exception {
        var missing = mapper.readTree("""
                {"nodes":[{"id":"x","type":"text_input"}],"edges":[{"source":"x","target":"y"}]}
                """);
        Assertions.assertThrows(IllegalArgumentException.class, () -> CreativeCanvasService.validate(
                new CreativeCanvasService.SaveRequest(missing, null, 0L), mapper));

        var cyclic = mapper.readTree("""
                {"nodes":[{"id":"x","type":"text_input"},{"id":"y","type":"text_input"}],
                 "edges":[{"source":"x","target":"y"},{"source":"y","target":"x"}]}
                """);
        Assertions.assertThrows(IllegalArgumentException.class, () -> CreativeCanvasService.validate(
                new CreativeCanvasService.SaveRequest(cyclic, null, 0L), mapper));
    }

    @Test
    void convertsCanvasChainIntoExecutableWorkflowSnapshot() throws Exception {
        var graph = mapper.readTree("""
                {"nodes":[
                  {"id":"image-1","type":"image_generate","position":{"x":10,"y":20},
                   "data":{"label":"首图","prompt":"一只猫","modelRowId":7,"size":"1024*1024"}},
                  {"id":"video-1","type":"video_generate","position":{"x":300,"y":20},
                   "data":{"label":"视频","prompt":"缓慢推镜","modelRowId":9,"seconds":5}}],
                 "edges":[{"source":"image-1","target":"video-1"}]}
                """);
        var prepared = CreativeCanvasService.prepareGraph(graph, "all", null);
        Assertions.assertEquals(2, prepared.graph().nodes().size());
        Assertions.assertEquals("media_image_generate", prepared.graph().nodes().get(0).type());
        Assertions.assertEquals("media_video_generate", prepared.graph().nodes().get(1).type());
        Assertions.assertEquals(java.util.List.of("{{image-1.assetId}}"),
                prepared.graph().nodes().get(1).params().get("imageAssetIds"));
        Assertions.assertEquals(1, prepared.graph().edges().size());
    }

    @Test
    void requiresSelectedAncestorWhenRunningOnlyDownstreamNode() throws Exception {
        var graph = mapper.readTree("""
                {"nodes":[
                  {"id":"image-1","type":"image_generate","data":{"label":"首图","prompt":"猫","modelRowId":7}},
                  {"id":"video-1","type":"video_generate","data":{"label":"视频","prompt":"推镜","modelRowId":9}}],
                 "edges":[{"source":"image-1","target":"video-1"}]}
                """);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> CreativeCanvasService.prepareGraph(graph, "node", "video-1"));
    }
}
