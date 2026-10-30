package com.zwx.zwxagent.creative.asset;

import com.zwx.zwxagent.creative.repository.CreativeAssetRepository;
import com.zwx.zwxagent.creative.storage.LocalMediaStorage;
import com.zwx.zwxagent.creative.domain.CreativeConflictException;
import com.zwx.zwxagent.creative.repository.CreativeProjectRepository;
import com.zwx.zwxagent.creative.repository.MediaJobAssetRefRepository;
import com.zwx.zwxagent.creative.repository.MediaJobRepository;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.platform.security.Role;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * A14–A17（C06）：上传校验（签名/大小/像素）、软删除与引用互锁、本地存储往返与越界拒绝。
 * 独立构建服务栈（LocalMediaStorage + 临时目录 + 独立 JDBC 连接），不依赖 Spring 上下文。
 */
class CreativeAssetServiceTest {

    private static final String TEST_USER = "creative-asset-test-user";
    private static Creds creds;
    private static LocalMediaStorage storage;
    private static CreativeAssetService assetService;
    private static JdbcTemplate jdbcTemplate;
    private static CurrentActor actor;
    private static com.zwx.zwxagent.creative.service.CreativeProjectService projectService;

    private record Creds(String baseUrl, String username, String password) {
    }

    private static Optional<Creds> fromEnvironment() {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        String username = System.getenv("SPRING_DATASOURCE_USERNAME");
        String password = System.getenv("SPRING_DATASOURCE_PASSWORD");
        if (url == null || username == null || password == null) return Optional.empty();
        String prefix = url.substring(0, url.lastIndexOf('/'));
        return Optional.of(new Creds(prefix, username, password));
    }

    @BeforeAll
    static void setUp() throws Exception {
        creds = fromEnvironment().or(CreativeAssetServiceTest::fromLocalYml).orElse(null);
        Assumptions.assumeTrue(creds != null, "未找到 PostgreSQL 连接配置，跳过素材验证");
        var dataSource = new DriverManagerDataSource(creds.baseUrl(), creds.username(), creds.password());
        jdbcTemplate = new JdbcTemplate(dataSource);
        storage = new LocalMediaStorage(Files.createTempDirectory("creative-asset-test"));
        assetService = new CreativeAssetService(
                new CreativeAssetRepository(jdbcTemplate),
                new CreativeProjectRepository(jdbcTemplate),
                new MediaJobRepository(jdbcTemplate),
                new MediaJobAssetRefRepository(jdbcTemplate),
                storage);
        projectService = new com.zwx.zwxagent.creative.service.CreativeProjectService(
                new CreativeProjectRepository(jdbcTemplate));
        actor = new CurrentActor(TEST_USER, "default", TEST_USER, Role.USER);
    }

    @AfterAll
    static void cleanUp() {
        jdbcTemplate.update("DELETE FROM media_job_asset_ref WHERE job_id IN (SELECT id FROM media_job WHERE user_id = ?)", TEST_USER);
        jdbcTemplate.update("DELETE FROM media_job_attempt WHERE job_id IN (SELECT id FROM media_job WHERE user_id = ?)", TEST_USER);
        jdbcTemplate.update("DELETE FROM media_job WHERE user_id = ?", TEST_USER);
        jdbcTemplate.update("DELETE FROM creative_asset WHERE user_id = ?", TEST_USER);
        jdbcTemplate.update("DELETE FROM creative_project WHERE user_id = ?", TEST_USER);
    }

    private UUID projectId;

    private UUID ensureProject() {
        if (projectId == null) {
            projectId = projectService.create(actor, new com.zwx.zwxagent.creative.service.CreativeProjectService
                    .CreateProjectRequest("素材校验项目", null)).id();
        }
        return projectId;
    }

    @Test
    void uploadsValidPngAndParsesDimensions() throws Exception {
        var bytes = png(3, 2);
        UUID pid = ensureProject();
        var view = assetService.upload(actor, pid, "shot.png", bytes);
        Assertions.assertEquals("READY", view.status());
        Assertions.assertEquals(3, view.width());
        Assertions.assertEquals(2, view.height());
        var content = assetService.content(actor, view.id());
        Assertions.assertArrayEquals(bytes, content.bytes());
    }

    @Test
    void acceptsJpegAndWebpHeaders() throws Exception {
        UUID pid = ensureProject();
        var viewJpeg = assetService.upload(actor, pid, "a.jpg", jpeg(4, 3));
        Assertions.assertEquals("READY", viewJpeg.status());
        Assertions.assertEquals(4, viewJpeg.width());
        var viewWebp = assetService.upload(actor, pid, "b.webp", webpVp8x(5, 6));
        Assertions.assertEquals("READY", viewWebp.status());
        Assertions.assertEquals(5, viewWebp.width());
        Assertions.assertEquals(6, viewWebp.height());
    }

    @Test
    void rejectsForgedTextAndOversizeAndHugePixels() throws Exception {
        UUID pid = ensureProject();
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> assetService.upload(actor, pid, "fake.png", "这不是图片".getBytes()));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> assetService.upload(actor, pid, "big.png", new byte[21 * 1024 * 1024]));

    }

    @Test
    void localStorageRejectsTraversalAndRoundTrips() throws Exception {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> storage.resolve("../../escape.txt"));
        var key = "creative/t/u/p/" + UUID.randomUUID() + "/original.png";
        byte[] payload = {1, 2, 3, 4};
        storage.put(key, "image/png", new java.io.ByteArrayInputStream(payload), payload.length);
        Assertions.assertEquals(payload.length, storage.size(key));
        try (var in = storage.open(key)) {
            Assertions.assertArrayEquals(payload, in.readAllBytes());
        }
        storage.delete(key);
        Assertions.assertThrows(IllegalStateException.class, () -> storage.size(key));
    }

    @Test
    void softDeleteBlockedByActiveJobReferenceThenAllowed() throws Exception {
        UUID pid = ensureProject();
        var view = assetService.upload(actor, pid, "ref.png", png(2, 2));
        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO media_job (id, project_id, tenant_id, user_id, source_type, operation,
                            idempotency_key, request_hash, input_snapshot, model_snapshot)
                        VALUES (?, ?, 'default', ?, 'UI', 'text_to_image', ?, 'h', '{}', '{}')
                        """,
                ps -> {
                    ps.setObject(1, jobId);
                    ps.setObject(2, pid);
                    ps.setString(3, TEST_USER);
                    ps.setString(4, "ref-" + view.id());
                });
        jdbcTemplate.update("""
                        INSERT INTO media_job_asset_ref (job_id, asset_id, role, tenant_id, user_id)
                        VALUES (?, ?, 'REFERENCE', 'default', ?)
                        """, jobId, view.id(), TEST_USER);
        Assertions.assertThrows(CreativeConflictException.class, () -> assetService.delete(actor, view.id()));
        jdbcTemplate.update("UPDATE media_job SET status = 'SUCCEEDED' WHERE id = ?", jobId);
        assetService.delete(actor, view.id());
        var deleted = new CreativeAssetRepository(jdbcTemplate)
                .findByIdForOwner("default", TEST_USER, view.id()).orElseThrow();
        Assertions.assertEquals("DELETED", deleted.status().name());
    }

    @Test
    void otherUserCannotReadOrUpdateOrDelete() throws Exception {
        UUID pid = ensureProject();
        var tinyPng = png(1, 1);
        var view = assetService.upload(actor, pid, "mine.png", tinyPng);
        var stranger = new CurrentActor("creative-asset-stranger", "default", "stranger", Role.USER);
        Assertions.assertThrows(IllegalArgumentException.class, () -> assetService.content(stranger, view.id()));
        Assertions.assertThrows(IllegalArgumentException.class, () -> assetService.delete(stranger, view.id()));
        Assertions.assertThrows(IllegalArgumentException.class, () -> assetService.update(stranger, view.id(),
                new CreativeAssetService.UpdateAssetRequest("x", null)));
    }

    private static byte[] png(int width, int height) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] jpeg(int width, int height) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static byte[] webpVp8x(int width, int height) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'R', 'I', 'F', 'F'});
        out.writeBytes(new byte[]{0, 0, 0, 0});
        out.writeBytes(new byte[]{'W', 'E', 'B', 'P'});
        out.writeBytes(new byte[]{'V', 'P', '8', 'X'});
        out.writeBytes(new byte[]{10, 0, 0, 0});
        out.writeBytes(new byte[]{16, 0, 0, 0});
        int w = width - 1;
        int h = height - 1;
        out.writeBytes(new byte[]{(byte) (w & 0xFF), (byte) ((w >> 8) & 0xFF), (byte) ((w >> 16) & 0xFF),
                (byte) (h & 0xFF), (byte) ((h >> 8) & 0xFF), (byte) ((h >> 16) & 0xFF)});
        out.writeBytes(new byte[]{0, 0, 0, 0});
        return out.toByteArray();
    }

    private static Optional<Creds> fromLocalYml() {
        try {
            var path = Path.of("src/main/resources/application-local.yml");
            if (!Files.exists(path)) return Optional.empty();
            String url = null;
            String username = null;
            String password = null;
            for (String line : Files.readAllLines(path)) {
                if (line.matches("^    url: .*")) url = line.substring(line.indexOf(':') + 1).trim();
                else if (line.matches("^    username: .*")) username = line.substring(line.indexOf(':') + 1).trim();
                else if (line.matches("^    password: .*")) password = line.substring(line.indexOf(':') + 1).trim();
            }
            if (url == null || username == null || password == null) return Optional.empty();
            return Optional.of(new Creds(url, username, password));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }
}
