package com.zwx.zwxagent.creative.story;

import com.zwx.zwxagent.creative.domain.CreativeConflictException;
import com.zwx.zwxagent.creative.domain.VideoEpisodeRecord;
import com.zwx.zwxagent.creative.domain.VideoSceneRecord;
import com.zwx.zwxagent.creative.domain.VideoShotRecord;
import com.zwx.zwxagent.creative.repository.VideoStoryContentRepository;
import com.zwx.zwxagent.creative.service.PromptOptimizationService;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.platform.security.Role;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结构化拆镜草稿（P0）：同一草稿只允许一次有效采纳；替换只删未生成且无候选的镜头；
 * 来源故事版本不一致时默认拒绝，显式确认后才放行；短片默认剧集/场次幂等。
 */
class StoryboardDraftServiceTest {

    private final CurrentActor actor = new CurrentActor("user", "tenant", "test", Role.USER);
    private final UUID projectId = UUID.randomUUID();
    private final UUID draftId = UUID.randomUUID();
    private final UUID episodeId = UUID.randomUUID();
    private final UUID sceneId = UUID.randomUUID();

    private StoryboardDraftService.DraftView readyDraft() {
        return new StoryboardDraftService.DraftView(draftId, projectId, episodeId, sceneId, "READY",
                100L, "当前短片", List.of(
                new StoryboardDraftService.DraftRowView(UUID.randomUUID(), 1, 4, "开场",
                        "角色在桥上", "中景", "", "雨天石桥，角色回望"),
                new StoryboardDraftService.DraftRowView(UUID.randomUUID(), 2, 6, "收尾",
                        "角色离开", "远景", "再见。", "雨夜路灯，背影远去")),
                "", "wanx-test", "", null);
    }

    private StoryboardDraftService service(JdbcTemplate jdbc, TransactionTemplate tx,
                                           StoryContentService content, VideoStoryContentRepository repo) {
        var service = spy(new StoryboardDraftService(jdbc, tx, content, repo,
                mock(PromptOptimizationService.class), mock(com.zwx.zwxagent.creative.service.CreativeProjectService.class)));
        doReturn(readyDraft()).when(service).get(actor, projectId, draftId);
        // 来源故事版本代理：updatedAt 纪元毫秒 = 100，与草稿 sourceEpisodeVersion 对齐
        when(repo.findEpisode(actor.tenantId(), actor.userId(), episodeId))
                .thenReturn(java.util.Optional.of(new VideoEpisodeRecord(episodeId, projectId, "t", "u",
                        1, "当前短片", "", "", "", "DRAFT", null, java.time.Instant.ofEpochMilli(100))));
        return service;
    }

    private StoryContentService contentMock() {
        var content = mock(StoryContentService.class);
        when(content.createShot(any(), any(), any(), any())).thenReturn(newShot());
        return content;
    }

    private VideoShotRecord newShot() {
        return new VideoShotRecord(UUID.randomUUID(), projectId, "t", "u", sceneId, 1,
                "", "", "", "", "", 4, "", "DRAFT", 1L, null, null, "", java.util.List.of());
    }

    private TransactionTemplate txThatRunsCallback() {
        var tx = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = (Consumer<TransactionStatus>) invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
        return tx;
    }

    @Test
    void adoptConsumesDraftExactlyOnce() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        var service = service(jdbc, txThatRunsCallback(), contentMock(), mock(VideoStoryContentRepository.class));

        var first = service.adopt(actor, projectId, draftId,
                new StoryboardDraftService.AdoptRequest(StoryboardDraftService.MODE_APPEND, sceneId, 100L, false));
        Assertions.assertEquals(2, first.created());
        Assertions.assertEquals(2, first.shotIds().size());
        verify(jdbc, times(1)).update(anyString(), any(Object[].class));

        // 第二次采纳：草稿已被消费（条件更新不命中）→ 409，不重复建镜
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        Assertions.assertThrows(CreativeConflictException.class, () -> service.adopt(actor, projectId, draftId,
                new StoryboardDraftService.AdoptRequest(StoryboardDraftService.MODE_APPEND, sceneId, 100L, false)));
    }

    @Test
    void adoptRejectsDraftThatIsNotReady() {
        var jdbc = mock(JdbcTemplate.class);
        var service = service(jdbc, txThatRunsCallback(), contentMock(), mock(VideoStoryContentRepository.class));
        doReturn(new StoryboardDraftService.DraftView(draftId, projectId, episodeId, sceneId, "CONSUMED",
                100L, "当前短片", readyDraft().rows(), "", "m", "", null))
                .when(service).get(actor, projectId, draftId);
        Assertions.assertThrows(CreativeConflictException.class, () -> service.adopt(actor, projectId, draftId,
                new StoryboardDraftService.AdoptRequest(StoryboardDraftService.MODE_APPEND, sceneId, 100L, false)));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void adoptGuardsSourceStoryVersionUnlessForced() {
        var jdbc = mock(JdbcTemplate.class);
        var service = service(jdbc, txThatRunsCallback(), contentMock(), mock(VideoStoryContentRepository.class));
        Assertions.assertThrows(CreativeConflictException.class, () -> service.adopt(actor, projectId, draftId,
                new StoryboardDraftService.AdoptRequest(StoryboardDraftService.MODE_APPEND, sceneId, 999L, false)));
        // 显式确认后允许继续采用旧草稿
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        var forced = service.adopt(actor, projectId, draftId,
                new StoryboardDraftService.AdoptRequest(StoryboardDraftService.MODE_APPEND, sceneId, 999L, true));
        Assertions.assertEquals(2, forced.created());
    }

    @Test
    void replaceModeDeletesOnlyUngeneratedShotsWithoutCandidates() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        var content = contentMock();
        var repo = mock(VideoStoryContentRepository.class);
        var service = service(jdbc, txThatRunsCallback(), content, repo);
        UUID removable = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(UUID.class), any(Object[].class))).thenReturn(List.of(removable));
        when(repo.listShotsByProject(actor.tenantId(), actor.userId(), projectId)).thenReturn(List.of());

        var result = service.adopt(actor, projectId, draftId,
                new StoryboardDraftService.AdoptRequest(StoryboardDraftService.MODE_REPLACE_UNGENERATED,
                        sceneId, 100L, false));

        Assertions.assertEquals(1, result.replaced());
        Assertions.assertEquals(2, result.created());
        verify(content, times(2)).createShot(any(), any(), any(), any());
    }

    @Test
    void ensureShortfilmIsIdempotent() {
        var jdbc = mock(JdbcTemplate.class);
        var content = mock(StoryContentService.class);
        var repo = mock(VideoStoryContentRepository.class);
        var service = spy(new StoryboardDraftService(jdbc, mock(TransactionTemplate.class), content, repo,
                mock(PromptOptimizationService.class), mock(com.zwx.zwxagent.creative.service.CreativeProjectService.class)));
        var episode = new VideoEpisodeRecord(episodeId, projectId, "t", "u",
                1, "当前短片", "", "", "", "DRAFT", null, null);
        var scene = new VideoSceneRecord(sceneId, projectId, "t", "u",
                episodeId, 1, "第 1 场", "", "", "", "", "", "", "", "DRAFT", null, null);
        when(repo.listEpisodes(actor.tenantId(), actor.userId(), projectId)).thenReturn(List.of(episode));
        when(repo.listScenesByProject(actor.tenantId(), actor.userId(), projectId)).thenReturn(List.of(scene));

        var existing = service.ensureShortfilm(actor, projectId);
        Assertions.assertFalse(existing.created());
        Assertions.assertEquals(episodeId, existing.episodeId());
        Assertions.assertEquals(sceneId, existing.sceneId());
        verify(content, never()).createEpisode(any(), any(), any());

        when(repo.listEpisodes(actor.tenantId(), actor.userId(), projectId)).thenReturn(List.of());
        when(repo.listScenesByProject(actor.tenantId(), actor.userId(), projectId)).thenReturn(List.of());
        doReturn(episode).when(content).createEpisode(any(), any(), any());
        doReturn(scene).when(content).createScene(any(), any(), any(), any());
        var created = service.ensureShortfilm(actor, projectId);
        Assertions.assertTrue(created.created());
        verify(content, times(1)).createEpisode(any(), any(), any());
        verify(content, times(1)).createScene(any(), any(), any(), any());
    }
}
