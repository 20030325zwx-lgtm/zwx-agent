package com.zwx.zwxagent.creative.service;

import com.zwx.zwxagent.creative.domain.CreativeKind;
import com.zwx.zwxagent.creative.domain.CreativeProjectRecord;
import com.zwx.zwxagent.creative.domain.CreativeConflictException;
import com.zwx.zwxagent.creative.domain.ProjectStatus;
import com.zwx.zwxagent.creative.repository.CreativeProjectRepository;
import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 创作项目（C03）：归属从 CurrentActor 派生（BR01），名称 trim 后 1–80 字符（BR05），
 * 归档走乐观锁（BR15），归档不取消在途任务。
 */
@Service
public class CreativeProjectService {

    public static final int NAME_MAX = 80;
    public static final int DESCRIPTION_MAX = 500;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    public record ProjectView(UUID id, String name, String description, String kind, String status,
                              long version, Instant createdAt, Instant updatedAt) {
    }

    public record CreateProjectRequest(String name, String description, String kind) {
        public CreateProjectRequest(String name, String description) {
            this(name, description, null);
        }
    }

    public record UpdateProjectRequest(String name, String description, String status, Long expectedVersion) {
    }

    public record ProjectList(List<ProjectView> items, String nextCursor) {
    }

    private final CreativeProjectRepository repository;

    public CreativeProjectService(CreativeProjectRepository repository) {
        this.repository = repository;
    }

    public ProjectView create(CurrentActor actor, CreateProjectRequest request) {
        String name = normalizeName(request == null ? null : request.name());
        String description = normalizeDescription(request == null ? null : request.description());
        CreativeKind kind = CreativeKind.parse(request == null ? null : request.kind());
        var record = new CreativeProjectRecord(UUID.randomUUID(), actor.tenantId(), actor.userId(),
                name, description, kind, ProjectStatus.ACTIVE, 1, Instant.now(), Instant.now());
        repository.insert(record);
        return toView(record);
    }

    /** 更新（C03）：名称/描述/状态可改，乐观锁保护；仅允许 ACTIVE→ARCHIVED 归档方向。 */
    public ProjectView update(CurrentActor actor, UUID projectId, UpdateProjectRequest request) {
        var record = repository.lockByIdForOwner(actor.tenantId(), actor.userId(), projectId)
                .orElseThrow(() -> new IllegalArgumentException("创作项目不存在"));
        long expected = request == null || request.expectedVersion() == null
                ? record.version() : request.expectedVersion();
        String name = request != null && request.name() != null ? normalizeName(request.name()) : record.name();
        String description = request != null && request.description() != null
                ? normalizeDescription(request.description()) : record.description();
        ProjectStatus target = request == null || request.status() == null
                ? record.status() : ProjectStatus.valueOf(request.status());
        if (target == ProjectStatus.ARCHIVED && record.status() == ProjectStatus.ARCHIVED) {
            throw new CreativeConflictException("项目已归档");
        }
        if (!repository.update(projectId, expected, name, description, target)) {
            throw new CreativeConflictException("项目已被其他修改更新，请刷新后重试");
        }
        return get(actor, projectId);
    }

    public ProjectView get(CurrentActor actor, UUID projectId) {
        return repository.findByIdForOwner(actor.tenantId(), actor.userId(), projectId)
                .map(CreativeProjectService::toView)
                .orElseThrow(() -> new IllegalArgumentException("创作项目不存在"));
    }

    public ProjectList list(CurrentActor actor, String cursor, Integer limit) {
        int size = normalizeLimit(limit);
        var decoded = decodeCursor(cursor);
        List<ProjectView> items = repository
                .listByOwner(actor.tenantId(), actor.userId(), decoded.createdAt(), decoded.id(), size + 1)
                .stream().map(CreativeProjectService::toView).toList();
        if (items.size() > size) {
            items = items.subList(0, size);
            var last = items.get(size - 1);
            return new ProjectList(items, encodeCursor(last.createdAt(), last.id()));
        }
        return new ProjectList(items, null);
    }

    public ProjectView archive(CurrentActor actor, UUID projectId, Long expectedVersion) {
        var record = repository.lockByIdForOwner(actor.tenantId(), actor.userId(), projectId)
                .orElseThrow(() -> new IllegalArgumentException("创作项目不存在"));
        long version = expectedVersion != null ? expectedVersion : record.version();
        if (!repository.archive(projectId, version)) {
            throw new CreativeConflictException("项目已被修改或归档，请刷新后重试");
        }
        return get(actor, projectId);
    }

    /** 项目设定（V13）：长篇创作共用的自由文本块；小说约定 {characters, worldview, style}。 */
    public static final int SETTINGS_VALUE_MAX = 6000;

    public Map<String, Object> settingsOf(CurrentActor actor, UUID projectId) {
        get(actor, projectId); // 归属校验
        String json = repository.settingsJsonOf(actor.tenantId(), actor.userId(), projectId).orElse("{}");
        Map<String, Object> settings = new java.util.LinkedHashMap<>();
        com.zwx.zwxagent.creative.support.CreativeJson.parse(json).properties()
                .forEach(entry -> settings.put(entry.getKey(), entry.getValue().isTextual()
                        ? entry.getValue().asText() : entry.getValue()));
        return settings;
    }

    /** 浅合并更新设定：只接受已知文本键，值裁剪到长度上限。 */
    public Map<String, Object> updateSettings(CurrentActor actor, UUID projectId, Map<String, Object> patch) {
        get(actor, projectId);
        Map<String, Object> merged = settingsOf(actor, projectId);
        if (patch != null) {
            for (String key : SETTINGS_KEYS) {
                if (!patch.containsKey(key)) continue;
                Object value = patch.get(key);
                String text = value == null ? "" : String.valueOf(value).trim();
                if (text.length() > SETTINGS_VALUE_MAX) text = text.substring(0, SETTINGS_VALUE_MAX);
                merged.put(key, text);
            }
        }
        repository.updateSettings(projectId, com.zwx.zwxagent.creative.support.CreativeJson.toJson(merged));
        return merged;
    }

    public static final List<String> SETTINGS_KEYS = List.of("characters", "worldview", "style");

    public static String normalizeName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty() || value.length() > NAME_MAX) {
            throw new IllegalArgumentException("项目名称需为 1–" + NAME_MAX + " 个字符");
        }
        return value;
    }

    public static String normalizeDescription(String description) {
        String value = description == null ? "" : description.trim();
        if (value.length() > DESCRIPTION_MAX) {
            throw new IllegalArgumentException("项目描述不能超过 " + DESCRIPTION_MAX + " 个字符");
        }
        return value;
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    public static String encodeCursor(Instant createdAt, UUID id) {
        return createdAt.toEpochMilli() + ":" + id;
    }

    public record Cursor(Instant createdAt, UUID id) {
    }

    public static Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return new Cursor(null, null);
        String[] parts = cursor.split(":", 2);
        if (parts.length != 2) throw new IllegalArgumentException("分页游标格式不正确");
        try {
            return new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("分页游标格式不正确");
        }
    }

    private static ProjectView toView(CreativeProjectRecord record) {
        return new ProjectView(record.id(), record.name(), record.description(),
                record.kind().name(), record.status().name(), record.version(),
                record.createdAt(), record.updatedAt());
    }
}
