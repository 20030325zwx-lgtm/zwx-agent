package com.zwx.zwxagent.creative.story;

import com.zwx.zwxagent.creative.domain.VideoShotRecord;
import com.zwx.zwxagent.creative.repository.CreativeAssetRepository;
import com.zwx.zwxagent.creative.repository.VideoShotCandidateRepository;
import com.zwx.zwxagent.creative.repository.VideoStoryContentRepository;
import com.zwx.zwxagent.creative.service.CreativeProjectService;
import com.zwx.zwxagent.creative.service.MediaJobService;
import com.zwx.zwxagent.creative.service.MediaModelResolver;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.platform.security.Role;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 生成预检（P1）：纯校验不创建任务——逐镜给出可提交/阻塞原因、实际 operation、任务数；
 * 模型不支持当前 operation（如 gk-video-3.5 的 text_to_video=false）必须在预检给出原因（A07）。
 */
class ShotProductionPreflightTest {

    private final CurrentActor actor = new CurrentActor("user", "tenant", "test", Role.USER);
    private final UUID projectId = UUID.randomUUID();

    private VideoShotRecord shot(String prompt, String status, List<UUID> references) {
        return new VideoShotRecord(UUID.randomUUID(), projectId, "t", "u", UUID.randomUUID(), 1,
                "", "", "", "", "", 4, prompt, status, 1L, null, null, "", references);
    }

    @Test
    void preflightBlocksShotsAndExplainsModelCapability() {
        var contentRepo = mock(VideoStoryContentRepository.class);
        var shot1 = shot("雨天石桥", "READY", List.of());
        var shot2 = shot("", "DRAFT", List.of());
        when(contentRepo.listShotsByProject(actor.tenantId(), actor.userId(), projectId))
                .thenReturn(List.of(shot1, shot2));

        var projectService = mock(CreativeProjectService.class);
        when(projectService.settingsOf(any(), any())).thenReturn(java.util.Map.of("videoStyle", ""));
        var resolver = mock(MediaModelResolver.class);
        // text_to_video 不被当前模型支持 → 预检给出原因（不创建任务）
        when(resolver.resolve(anyString(), any(), eq("text_to_video")))
                .thenThrow(new IllegalArgumentException("该模型不支持 text_to_video"));
        when(resolver.resolve(anyString(), any(), eq("image_to_video")))
                .thenThrow(new IllegalArgumentException("该模型不支持 image_to_video"));

        var service = new ShotProductionService(
                mock(StoryContentService.class), contentRepo,
                mock(VideoShotCandidateRepository.class), mock(CreativeAssetRepository.class),
                mock(MediaJobService.class), projectService, resolver, mock(TransactionTemplate.class));

        var result = service.preflight(actor, projectId, List.of(shot1.id(), shot2.id()));
        Assertions.assertEquals(0, result.readyCount());
        Assertions.assertEquals("费用未知（以供应商账单为准）", result.costNote());
        Assertions.assertFalse(result.items().get(0).ok());
        Assertions.assertTrue(result.items().get(0).reason().contains("text_to_video"),
                "预检原因要说明模型能力限制");
        Assertions.assertTrue(result.items().get(1).reason().contains("缺少提示词"));
    }

    @Test
    void preflightCountsReadyShotsAndReportsModel() {
        var contentRepo = mock(VideoStoryContentRepository.class);
        var assetId = UUID.randomUUID();
        var shot = shot("雨天石桥", "READY", List.of(assetId));
        when(contentRepo.listShotsByProject(actor.tenantId(), actor.userId(), projectId))
                .thenReturn(List.of(shot));
        var assetRepo = mock(CreativeAssetRepository.class);
        when(assetRepo.findByIdForOwner(anyString(), anyString(), eq(assetId)))
                .thenReturn(java.util.Optional.of(new com.zwx.zwxagent.creative.domain.CreativeAssetRecord(
                        assetId, projectId, "t", "u", null, null, null, "IMAGE", null, null,
                        "image/png", 1L, null, 8, 8, null,
                        "首帧图", false, com.zwx.zwxagent.creative.domain.AssetStatus.READY,
                        null, null, null, null)));
        var projectService = mock(CreativeProjectService.class);
        when(projectService.settingsOf(any(), any())).thenReturn(java.util.Map.of());
        var resolver = mock(MediaModelResolver.class);
        when(resolver.resolve(anyString(), any(), anyString()))
                .thenReturn(new com.zwx.zwxagent.creative.domain.ResolvedMediaModel(
                        19L, 45L, "openai-compatible", "https://api.lk888.ai/v1", "gk-video-3.5",
                        "sk-x", "lk-media", null, false, 1L));

        var service = new ShotProductionService(
                mock(StoryContentService.class), contentRepo,
                mock(VideoShotCandidateRepository.class), assetRepo,
                mock(MediaJobService.class), projectService, resolver, mock(TransactionTemplate.class));

        var result = service.preflight(actor, projectId, List.of(shot.id()));
        Assertions.assertEquals(1, result.readyCount());
        Assertions.assertEquals("gk-video-3.5", result.modelName());
        Assertions.assertEquals("image_to_video", result.items().get(0).operation(),
                "预检回显实际 operation，便于解释能力限制");
    }

    @Test
    void unknownShotIsReportedNotSwallowed() {
        var contentRepo = mock(VideoStoryContentRepository.class);
        when(contentRepo.listShotsByProject(actor.tenantId(), actor.userId(), projectId)).thenReturn(List.of());
        var service = new ShotProductionService(
                mock(StoryContentService.class), contentRepo,
                mock(VideoShotCandidateRepository.class), mock(CreativeAssetRepository.class),
                mock(MediaJobService.class), mock(CreativeProjectService.class),
                mock(MediaModelResolver.class), mock(TransactionTemplate.class));
        var result = service.preflight(actor, projectId, List.of(UUID.randomUUID()));
        Assertions.assertFalse(result.items().getFirst().ok());
        Assertions.assertTrue(result.items().getFirst().reason().contains("不存在"));
    }
}
