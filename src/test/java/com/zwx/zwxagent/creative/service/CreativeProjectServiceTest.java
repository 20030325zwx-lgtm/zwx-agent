package com.zwx.zwxagent.creative.service;

import com.zwx.zwxagent.creative.domain.CreativeConflictException;
import com.zwx.zwxagent.creative.domain.CreativeDraftRecord;
import com.zwx.zwxagent.creative.repository.CreativeDraftRepository;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.platform.security.Role;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

/**
 * A05/A06（C03）：项目与草稿的归属隔离、乐观锁冲突、归档约束。
 * 使用随机用户标识，不依赖真实账号；数据写入开发库后不清理（仅测试租户可见）。
 */
@SpringBootTest
class CreativeProjectServiceTest {

    @Resource
    private CreativeProjectService projectService;

    @Resource
    private CreativeDraftService draftService;

    @Resource
    private CreativeDraftRepository draftRepository;

    private CurrentActor actor(String tag) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return new CurrentActor("creative-test-" + tag + "-" + suffix, "default",
                "creative-test-" + tag, Role.USER);
    }

    @Test
    void createsGetsAndListsProjectsForOwner() {
        CurrentActor actor = actor("a");
        var created = projectService.create(actor,
                new CreativeProjectService.CreateProjectRequest("  测试项目  ", "说明"));
        Assertions.assertEquals("测试项目", created.name());
        Assertions.assertEquals("ACTIVE", created.status());

        Assertions.assertEquals(created.id(), projectService.get(actor, created.id()).id());

        projectService.create(actor, new CreativeProjectService.CreateProjectRequest("第二项", ""));
        var page = projectService.list(actor, null, 1);
        Assertions.assertEquals(1, page.items().size());
        Assertions.assertNotNull(page.nextCursor());
        var secondPage = projectService.list(actor, page.nextCursor(), 10);
        Assertions.assertTrue(secondPage.items().stream().anyMatch(p -> p.id().equals(created.id())));
        Assertions.assertNull(secondPage.nextCursor());
    }

    @Test
    void isolatesProjectsBetweenUsers() {
        CurrentActor owner = actor("owner");
        CurrentActor stranger = actor("stranger");
        var project = projectService.create(owner,
                new CreativeProjectService.CreateProjectRequest("私有项目", null));

        Assertions.assertThrows(IllegalArgumentException.class, () -> projectService.get(stranger, project.id()));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> projectService.archive(stranger, project.id(), null));
        Assertions.assertTrue(projectService.list(stranger, null, 10).items().isEmpty());

        // 相同名称不同用户互不影响
        var sameName = projectService.create(stranger,
                new CreativeProjectService.CreateProjectRequest("私有项目", null));
        Assertions.assertNotEquals(project.id(), sameName.id());
    }

    @Test
    void draftCrudWithVersionConflict() {
        CurrentActor actor = actor("draft");
        var project = projectService.create(actor,
                new CreativeProjectService.CreateProjectRequest("草稿项目", null));

        var draft = draftService.create(actor, project.id(),
                new CreativeDraftService.CreateDraftRequest(null, "原始想法", "优化后的提示词"));
        Assertions.assertEquals("text_to_image", draft.operation());
        Assertions.assertEquals("原始想法", draft.originalPrompt());
        Assertions.assertEquals(1, draft.version());

        var updated = draftService.update(actor, draft.id(),
                new CreativeDraftService.UpdateDraftRequest(null, "原始想法 v2", "优化 v2", 1L));
        Assertions.assertEquals(2, updated.version());
        Assertions.assertEquals("原始想法 v2", updated.originalPrompt());

        // 旧版本再更新 → 409（不覆盖服务器文本）
        Assertions.assertThrows(CreativeConflictException.class, () -> draftService.update(actor, draft.id(),
                new CreativeDraftService.UpdateDraftRequest(null, "过期修改", "过期修改", 1L)));
        Assertions.assertEquals("原始想法 v2", draftService.get(actor, draft.id()).originalPrompt());

        // 不支持的创作类型
        Assertions.assertThrows(IllegalArgumentException.class, () -> draftService.update(actor, draft.id(),
                new CreativeDraftService.UpdateDraftRequest("video_generation", "x", "y", 2L)));
        // 超长提示词（BR05 平台上限）
        Assertions.assertThrows(IllegalArgumentException.class, () -> draftService.update(actor, draft.id(),
                new CreativeDraftService.UpdateDraftRequest(null, "x".repeat(8001), null, 2L)));
    }

    @Test
    void archivedProjectRejectsNewDraftAndConflictingUpdate() {
        CurrentActor actor = actor("archive");
        var project = projectService.create(actor,
                new CreativeProjectService.CreateProjectRequest("待归档", null));
        var draft = draftService.create(actor, project.id(),
                new CreativeDraftService.CreateDraftRequest(null, "草稿", null));

        var archived = projectService.update(actor, project.id(),
                new CreativeProjectService.UpdateProjectRequest(null, null, "ARCHIVED", 1L));
        Assertions.assertEquals("ARCHIVED", archived.status());

        // BR02：归档后不能新增草稿，但已有草稿仍可读
        Assertions.assertThrows(CreativeConflictException.class,
                () -> draftService.create(actor, project.id(),
                        new CreativeDraftService.CreateDraftRequest(null, "x", null)));
        Assertions.assertEquals(draft.id(), draftService.get(actor, draft.id()).id());

        // 乐观锁：旧版本归档/更新冲突
        Assertions.assertThrows(CreativeConflictException.class, () -> projectService.archive(actor, project.id(), 1L));
        Assertions.assertThrows(CreativeConflictException.class, () -> projectService.update(actor, project.id(),
                new CreativeProjectService.UpdateProjectRequest("改名", null, null, 1L)));

        // 归档不可逆
        Assertions.assertThrows(CreativeConflictException.class, () -> projectService.update(actor, project.id(),
                new CreativeProjectService.UpdateProjectRequest(null, null, "ACTIVE", archived.version())));
    }

    @Test
    void validatesProjectNameLength() {
        CurrentActor actor = actor("name");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> projectService.create(actor, new CreativeProjectService.CreateProjectRequest("   ", null)));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> projectService.create(actor, new CreativeProjectService.CreateProjectRequest("x".repeat(81), null)));
    }

    @Test
    void draftRepositoryRoundTripsReferenceAndParameters() {
        CurrentActor actor = actor("repo");
        var project = projectService.create(actor,
                new CreativeProjectService.CreateProjectRequest("仓库往返", null));
        var record = new CreativeDraftRecord(UUID.randomUUID(), project.id(), actor.tenantId(), actor.userId(),
                "text_to_image", "原始", "有效", java.util.List.of(UUID.randomUUID(), UUID.randomUUID()),
                null, java.util.Map.of("size", "1024*1024"), 1, java.time.Instant.now(), java.time.Instant.now());
        draftRepository.insert(record);
        var loaded = draftRepository.findByIdForOwner(actor.tenantId(), actor.userId(), record.id()).orElseThrow();
        Assertions.assertEquals(2, loaded.referenceAssetIds().size());
        Assertions.assertEquals("1024*1024", loaded.parameters().get("size"));
    }
}
