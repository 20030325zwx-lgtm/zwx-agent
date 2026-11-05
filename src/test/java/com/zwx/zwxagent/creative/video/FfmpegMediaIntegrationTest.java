package com.zwx.zwxagent.creative.video;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * ffmpeg 拼接管线集成测试（P2 成片）：用本地合成片段（testsrc）验证
 * 「规范化（保比例补边/补静音轨）→ concat → 探测时长」在真实 ffmpeg 下可用。
 * 该测试不调用任何生成模型、不访问网络，只在本机有 ffmpeg 时运行。
 */
class FfmpegMediaIntegrationTest {

    private static final String FFMPEG = "ffmpeg";
    private static final String FFPROBE = "ffprobe";

    private boolean ffmpegAvailable() {
        try {
            new ProcessBuilder(FFMPEG, "-version").start().waitFor();
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    /** 生成 1 秒合成片段：testsrc 视频 + sine 音频，H.264/AAC，避免依赖外部素材。 */
    private Path syntheticClip(Path workDir, String name, int seconds, int width, int height) throws Exception {
        Path out = workDir.resolve(name);
        new ProcessBuilder(FFMPEG, "-y",
                "-f", "lavfi", "-i", "testsrc=size=" + width + "x" + height + ":rate=30:duration=" + seconds,
                "-f", "lavfi", "-i", "sine=frequency=440:duration=" + seconds,
                "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-shortest",
                out.toString()).start().waitFor();
        Assertions.assertTrue(Files.exists(out) && Files.size(out) > 0, name + " 生成失败");
        return out;
    }

    @Test
    void normalizeAndConcatProducesPlayableOutput(@TempDir Path workDir) throws Exception {
        Assumptions.assumeTrue(ffmpegAvailable(), "本机无 ffmpeg，跳过集成测试");
        // 两段不同画幅/来源（一段无音轨），验证保比例补边与静音轨补充
        Path clipA = syntheticClip(workDir, "a.mp4", 1, 640, 360);
        Path silent = workDir.resolve("silent.mp4");
        new ProcessBuilder(FFMPEG, "-y",
                "-f", "lavfi", "-i", "testsrc=size=320x240:rate=30:duration=1",
                "-c:v", "libx264", "-preset", "ultrafast", silent.toString()).start().waitFor();

        Path normA = FfmpegMedia.normalize(FFMPEG, clipA, workDir, 0, 320, 180, 1000, c -> { }, 2);
        Path normB = FfmpegMedia.normalize(FFMPEG, silent, workDir, 1, 320, 180, 1000, c -> { }, 2);
        long normAms = FfmpegMedia.probeDurationMs(FFPROBE, normA);
        long normBms = FfmpegMedia.probeDurationMs(FFPROBE, normB);
        Assertions.assertEquals(1000, normAms, 300, "规范化后时长应接近 1 秒");
        Assertions.assertEquals(1000, normBms, 300, "无音轨片段补静音后时长不变");

        Path output = FfmpegMedia.concat(FFMPEG, List.of(normA, normB), workDir, c -> { }, 2);
        long total = FfmpegMedia.probeDurationMs(FFPROBE, output);
        Assertions.assertEquals(2000, total, 500, "拼接总时长应为两段之和");
        Assertions.assertTrue(Files.size(output) > 0);
    }
}
