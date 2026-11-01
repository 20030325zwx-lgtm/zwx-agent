package com.zwx.zwxagent.creative.video;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 视频探测器（V1-A）：纯 Java 解析 MP4（ISO BMFF）box 结构，读取 mvhd 时长与 tkhd 画幅。
 * 设计约束同 ImageInspector——魔数判定、尽力而为：解析失败返回 supported=false，
 * 不阻断素材 READY；不信任文件扩展名与 Content-Type。首版不抽帧（封面由播放器原生首帧承担），
 * ffmpeg/ffprobe 受控运行时属于成片阶段（R02）。
 */
public final class VideoProbe {

    private VideoProbe() {
    }

    public record VideoInspection(boolean supported, Long durationMs, Integer width, Integer height) {
    }

    private static final int MAX_SCAN_BYTES = 32 * 1024 * 1024;
    private static final int MAX_DEPTH = 4;

    public static boolean isMp4(byte[] bytes) {
        return bytes != null && bytes.length >= 12
                && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p';
    }

    public static VideoInspection inspect(byte[] bytes) {
        if (!isMp4(bytes) || bytes.length > MAX_SCAN_BYTES) {
            return new VideoInspection(false, null, null, null);
        }
        try {
            var result = new ScanResult();
            scanContainers(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN),
                    0, bytes.length, 0, result);
            Long durationMs = null;
            if (result.mvhdTimescale > 0 && result.mvhdDuration >= 0) {
                durationMs = Math.round(result.mvhdDuration * 1000.0 / result.mvhdTimescale);
            }
            boolean supported = durationMs != null || (result.width != null && result.height != null);
            return new VideoInspection(supported, durationMs, result.width, result.height);
        } catch (RuntimeException exception) {
            return new VideoInspection(false, null, null, null);
        }
    }

    private static final class ScanResult {
        long mvhdTimescale = -1;
        long mvhdDuration = -1;
        Integer width;
        Integer height;
    }

    /** 深度受限扫描：moov → mvhd / trak → tkhd；其余 box（含 mdat）只按 size 跳过。 */
    private static void scanContainers(ByteBuffer buffer, int start, int end, int depth, ScanResult result) {
        if (depth > MAX_DEPTH) return;
        int offset = start;
        while (offset + 8 <= end) {
            long size = buffer.getInt(offset) & 0xFFFFFFFFL;
            String type = readType(buffer, offset + 4);
            int headerSize = 8;
            if (size == 1) {
                if (offset + 16 > end) return;
                size = buffer.getLong(offset + 8);
                headerSize = 16;
            } else if (size == 0) {
                size = end - offset;
            }
            if (size < headerSize || offset + size > end) return;
            switch (type) {
                case "moov", "trak", "mdia" ->
                        scanContainers(buffer, offset + headerSize, (int) (offset + size), depth + 1, result);
                case "mvhd" -> readMvhd(buffer, offset + headerSize, (int) (offset + size), result);
                case "tkhd" -> readTkhd(buffer, offset + headerSize, (int) (offset + size), result);
                default -> { /* mdat/free/sgat 等：整体跳过 */ }
            }
            offset += (int) size;
        }
    }

    /**
     * mvhd payload：version+flags(4)；v0 → creation(4) modification(4) timescale(4) duration(4)；
     * v1 → creation(8) modification(8) timescale(4) duration(8)。
     */
    private static void readMvhd(ByteBuffer buffer, int start, int end, ScanResult result) {
        if (end - start < 4) return;
        int version = buffer.get(start) & 0xFF;
        if (version == 1) {
            if (end - start < 32) return;
            result.mvhdTimescale = buffer.getInt(start + 20) & 0xFFFFFFFFL;
            result.mvhdDuration = buffer.getLong(start + 24);
        } else {
            if (end - start < 20) return;
            result.mvhdTimescale = buffer.getInt(start + 12) & 0xFFFFFFFFL;
            result.mvhdDuration = buffer.getInt(start + 16) & 0xFFFFFFFFL;
        }
    }

    /** tkhd payload 末 8 字节即 width/height（各 4 字节 16.16 定点）。 */
    private static void readTkhd(ByteBuffer buffer, int start, int end, ScanResult result) {
        if (end - start < 8) return;
        int widthFixed = buffer.getInt(end - 8);
        int heightFixed = buffer.getInt(end - 4);
        int width = widthFixed >>> 16;
        int height = heightFixed >>> 16;
        if (width > 0 && height > 0) {
            result.width = width;
            result.height = height;
        }
    }

    private static String readType(ByteBuffer buffer, int position) {
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            bytes[i] = buffer.get(position + i);
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}
