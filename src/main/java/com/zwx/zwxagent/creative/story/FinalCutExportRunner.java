package com.zwx.zwxagent.creative.story;

import com.zwx.zwxagent.creative.domain.AssetOrigin;
import com.zwx.zwxagent.creative.domain.AssetStatus;
import com.zwx.zwxagent.creative.domain.CreativeAssetRecord;
import com.zwx.zwxagent.creative.domain.VideoFinalCutRecord;

import static com.zwx.zwxagent.creative.domain.VideoFinalCutRecord.*;
import com.zwx.zwxagent.creative.repository.CreativeAssetRepository;
import com.zwx.zwxagent.creative.repository.VideoFinalCutRepository;
import com.zwx.zwxagent.creative.video.FfmpegMedia;
import com.zwx.zwxagent.platform.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 成片导出执行器（P2 最小闭环）：下载已选片段 → ffprobe 时长 → 逐段规范化
 * （保比例补边、统一 fps/音频，禁止拉伸）→ concat 拼接 → 上传对象存储 → 登记 DERIVED 素材。
 *
 * <p>约束（需求 §9.3）：输入只来自受权素材引用；有整体超时与进程取消；临时目录隔离并在
 * 结束时清理；状态只到阶段级（本地转码无百分比）；导出不调用任何生成模型。</p>
 */
@Service
public class FinalCutExportRunner {

    private static final Logger log = LoggerFactory.getLogger(FinalCutExportRunner.class);
    private static final int MAX_CLIPS = 50;

    private final VideoFinalCutRepository cutRepository;
    private final CreativeAssetRepository assetRepository;
    private final ObjectStorage storage;
    private final String ffmpeg;
    private final String ffprobe;
    private final boolean enabled;
    private final int timeoutMinutes;

    private final AtomicBoolean workerBusy = new AtomicBoolean(false);
    private volatile Process currentProcess;
    private volatile UUID currentCutId;

    public FinalCutExportRunner(VideoFinalCutRepository cutRepository,
                                CreativeAssetRepository assetRepository,
                                ObjectStorage storage,
                                @Value("${app.final-cut.ffmpeg:ffmpeg}") String ffmpeg,
                                @Value("${app.final-cut.ffprobe:ffprobe}") String ffprobe,
                                @Value("${app.final-cut.enabled:true}") boolean enabled,
                                @Value("${app.final-cut.timeout-minutes:30}") int timeoutMinutes) {
        this.cutRepository = cutRepository;
        this.assetRepository = assetRepository;
        this.storage = storage;
        this.ffmpeg = ffmpeg;
        this.ffprobe = ffprobe;
        this.enabled = enabled;
        this.timeoutMinutes = timeoutMinutes;
    }

    /** 单并发轮询（需求：并发限制）；无任务时空转成本低。 */
    @Scheduled(fixedDelayString = "${app.final-cut.poll-ms:5000}")
    public void poll() {
        if (!enabled || !workerBusy.compareAndSet(false, true)) return;
        try {
            cutRepository.claimNextForExport().ifPresent(this::execute);
        } catch (Exception exception) {
            log.warn("成片导出调度异常：{}", exception.getMessage());
        } finally {
            workerBusy.set(false);
        }
    }

    /** 取消：条件状态转移 + 杀当前进程（只有真实支持取消的本地转码才提供该操作）。 */
    public void cancelCurrentIfMatches(UUID cutId) {
        if (cutId != null && cutId.equals(currentCutId) && currentProcess != null) {
            currentProcess.destroy();
        }
    }

    private void execute(VideoFinalCutRecord cut) {
        currentCutId = cut.id();
        Path workDir = null;
        long deadline = System.nanoTime() + Duration.ofMinutes(timeoutMinutes).toNanos();
        try {
            log.info("成片导出开始：cut={} clips={}", cut.id(), cut.items().size());
            workDir = Files.createTempDirectory("final-cut-" + cut.id());
            List<Path> normalized = new ArrayList<>();
            long totalMs = 0;
            for (int index = 0; index < cut.items().size(); index++) {
                VideoFinalCutItem item = cut.items().get(index);
                if (index >= MAX_CLIPS) break;
                if (!cutRepository.stillRunning(cut.id())) {
                    log.info("成片导出已取消：cut={}", cut.id());
                    return;
                }
                if (System.nanoTime() - deadline > 0) {
                    throw new IllegalStateException("导出超时（超过 " + timeoutMinutes + " 分钟），已终止");
                }
                Path clip = downloadClip(cut, item, index, workDir);
                long durationMs = probeDurationMs(clip);
                totalMs += durationMs;
                normalized.add(normalizeClip(clip, workDir, index, outputSize(cut), durationMs));
            }
            if (normalized.isEmpty()) throw new IllegalStateException("没有可用片段");
            cutRepository.markUploading(cut.id());
            Path output = concat(normalized, workDir);
            long bytes = Files.size(output);
            long outputMs = probeDurationMs(output);
            UUID assetId = registerOutput(cut, output, bytes, outputMs);
            cutRepository.markCompleted(cut.id(), assetId, bytes, outputMs);
            log.info("成片导出完成：cut={} bytes={} ms={}", cut.id(), bytes, outputMs);
        } catch (Exception exception) {
            log.warn("成片导出失败：cut={} {}", cut.id(), exception.getMessage());
            cutRepository.markFailed(cut.id(), exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage());
        } finally {
            currentCutId = null;
            currentProcess = null;
            cleanup(workDir);
        }
    }

    private Path downloadClip(VideoFinalCutRecord cut, VideoFinalCutItem item, int index, Path workDir)
            throws Exception {
        var asset = assetRepository.findByIdForOwner(cut.tenantId(), cut.userId(), item.assetId())
                .orElseThrow(() -> new IllegalStateException("镜 " + item.shotNumber() + " 的视频素材不可读取"));
        if (!"VIDEO".equals(asset.mediaType())) {
            throw new IllegalStateException("镜 " + item.shotNumber() + " 的候选不是视频素材");
        }
        Path clip = workDir.resolve("clip-" + index + ".mp4");
        try (InputStream in = storage.open(asset.objectKey())) {
            Files.copy(in, clip, StandardCopyOption.REPLACE_EXISTING);
        }
        return clip;
    }

    private String outputSize(VideoFinalCutRecord cut) {
        String size = cut.outputSize() == null || cut.outputSize().isBlank() ? "1280x720" : cut.outputSize();
        if (!size.matches("\\d{2,5}x\\d{2,5}")) size = "1280x720";
        return size;
    }

    /** 单段规范化：保比例缩放 + 补边（禁止拉伸）、统一 fps/像素格式，补静音轨保证可拼接。 */
    private Path normalizeClip(Path clip, Path workDir, int index, String size, long durationMs) throws Exception {
        String[] wh = size.split("x");
        int w = Integer.parseInt(wh[0]) / 2 * 2;
        int h = Integer.parseInt(wh[1]) / 2 * 2;
        return FfmpegMedia.normalize(ffmpeg, clip, workDir, index, w, h, durationMs,
                command -> trackProcess(command, "规范化镜 " + (index + 1)), timeoutMinutes);
    }

    private Path concat(List<Path> normalized, Path workDir) throws Exception {
        return FfmpegMedia.concat(ffmpeg, normalized, workDir,
                command -> trackProcess(command, "拼接成片"), timeoutMinutes);
    }

    private long probeDurationMs(Path file) throws Exception {
        return FfmpegMedia.probeDurationMs(ffprobe, file);
    }

    private void trackProcess(List<String> command, String stage) {
        log.info("成片导出 {}: {}", stage, command.getFirst());
        try {
            currentProcess = new ProcessBuilder(command).redirectErrorStream(false).start();
        } catch (Exception exception) {
            throw new IllegalStateException(stage + " 启动失败：" + exception.getMessage(), exception);
        }
    }


    private UUID registerOutput(VideoFinalCutRecord cut, Path output, long bytes, long durationMs) throws Exception {
        String key = "final-cut/" + cut.projectId() + "/" + cut.id() + ".mp4";
        try (InputStream in = Files.newInputStream(output)) {
            storage.put(key, "video/mp4", in, bytes);
        }
        var existing = assetRepository.findByIdForOwner(cut.tenantId(), cut.userId(), cut.id());
        if (existing.isPresent()) return existing.get().id();
        UUID assetId = UUID.randomUUID();
        assetRepository.insert(new CreativeAssetRecord(
                assetId, cut.projectId(), cut.tenantId(), cut.userId(),
                null, null, AssetOrigin.DERIVED,
                "VIDEO", com.zwx.zwxagent.creative.domain.StorageBackend.valueOf(storageBackend()), key,
                "video/mp4", bytes, sha256(output),
                null, null, durationMs,
                "成片导出 " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),
                false, AssetStatus.READY,
                null, "{}", java.time.Instant.now(), null));
        return assetId;
    }

    private String storageBackend() {
        return switch (storage.backend() == null ? "" : storage.backend().toUpperCase(java.util.Locale.ROOT)) {
            case "LOCAL" -> "LOCAL";
            case "S3" -> "S3";
            default -> "OSS";
        };
    }

    private String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void cleanup(Path workDir) {
        if (workDir == null) return;
        try (var paths = Files.walk(workDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) {
            // 临时目录清理失败不影响结果
        }
    }
}
