package com.zwx.zwxagent.creative.video;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * ffmpeg/ffprobe 进程封装（P2 成片）：命令只由服务端按受权输入构造；超时与失败如实上抛。
 * 抽成静态工具便于用本地合成片段做集成测试（不涉及任何生成模型）。
 */
public final class FfmpegMedia {

    private FfmpegMedia() {
    }

    /** 单段规范化：保比例缩放 + 补边（禁止拉伸）、统一 fps/像素格式，补静音轨保证可拼接。 */
    public static Path normalize(String ffmpeg, Path in, Path workDir, int index, int width, int height,
                                 long durationMs, Consumer<List<String>> processTracker,
                                 int timeoutMinutes) throws Exception {
        Path out = workDir.resolve("norm-" + index + ".mp4");
        List<String> command = List.of(ffmpeg, "-y", "-i", in.toString(),
                "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100",
                "-map", "0:v:0", "-map", "1:a:0", "-shortest",
                "-vf", "scale=" + width + ":" + height + ":force_original_aspect_ratio=decrease,"
                        + "pad=" + width + ":" + height + ":(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30,format=yuv420p",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                "-c:a", "aac", "-ar", "44100", "-ac", "2",
                "-t", String.valueOf(Math.max(0.2, durationMs / 1000.0)),
                out.toString());
        run(command, processTracker, timeoutMinutes);
        return out;
    }

    /** concat demuxer 拼接：各段已规范化为相同参数，流复制不再重编码。 */
    public static Path concat(String ffmpeg, List<Path> normalized, Path workDir,
                              Consumer<List<String>> processTracker, int timeoutMinutes) throws Exception {
        Path list = workDir.resolve("concat.txt");
        StringBuilder content = new StringBuilder();
        for (Path file : normalized) {
            content.append("file '").append(file.toAbsolutePath()).append("'\n");
        }
        Files.writeString(list, content.toString());
        Path output = workDir.resolve("final-cut.mp4");
        run(List.of(ffmpeg, "-y", "-f", "concat", "-safe", "0", "-i", list.toString(),
                "-c", "copy", "-movflags", "+faststart", output.toString()), processTracker, timeoutMinutes);
        return output;
    }

    public static long probeDurationMs(String ffprobe, Path file) throws Exception {
        Process process = new ProcessBuilder(ffprobe, "-v", "error", "-show_entries",
                "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", file.toString())
                .redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes()).trim();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("ffprobe 超时");
        }
        try {
            return Math.round(Double.parseDouble(out.split("\n")[0]) * 1000);
        } catch (Exception exception) {
            return 0L;
        }
    }

    private static void run(List<String> command, Consumer<List<String>> processTracker,
                            int timeoutMinutes) throws Exception {
        if (processTracker != null) processTracker.accept(command);
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("ffmpeg 超时");
        }
        if (process.exitValue() != 0) {
            String stderr = new String(process.getErrorStream().readAllBytes());
            String[] lines = stderr.isBlank() ? new String[]{"未知错误"} : stderr.trim().split("\n");
            throw new IllegalStateException("ffmpeg 失败：" + lines[lines.length - 1]);
        }
    }
}
