package com.zwx.zwxagent.creative.video;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * VideoProbe 契约：MP4（ftyp 魔数）解析 mvhd 时长与 tkhd 画幅；
 * 非 MP4/损坏数据如实返回 supported=false（尽力而为，不抛异常）。
 * 测试用例手工构造最小合法 box 结构，不依赖真实媒体文件。
 */
class VideoProbeTest {

    @Test
    void parsesDurationAndDimensionsFromMinimalMp4() {
        byte[] mp4 = minimalMp4(1000, 5000, 320, 240);
        var inspection = VideoProbe.inspect(mp4);
        Assertions.assertTrue(inspection.supported());
        Assertions.assertEquals(5000L, inspection.durationMs());
        Assertions.assertEquals(320, inspection.width());
        Assertions.assertEquals(240, inspection.height());
    }

    @Test
    void versionOneMvhdIsParsed() {
        byte[] mp4 = minimalMp4V1(600, 3000, 640, 360);
        var inspection = VideoProbe.inspect(mp4);
        Assertions.assertTrue(inspection.supported());
        Assertions.assertEquals(5000L, inspection.durationMs()); // 3000 / 600 * 1000
        Assertions.assertEquals(640, inspection.width());
        Assertions.assertEquals(360, inspection.height());
    }

    @Test
    void nonMp4BytesAreNotSupported() {
        Assertions.assertFalse(VideoProbe.isMp4(new byte[]{0, 0, 0, 8, 'm', 'd', 'a', 't'}));
        Assertions.assertFalse(VideoProbe.inspect(new byte[]{1, 2, 3, 4, 5}).supported());
        Assertions.assertFalse(VideoProbe.inspect(new byte[0]).supported());
        Assertions.assertFalse(VideoProbe.inspect(null).supported());
    }

    @Test
    void corruptedBoxStructureIsNotSupportedAndDoesNotThrow() {
        // ftyp 魔数正确但 box size 越界：应安全返回 false
        byte[] corrupt = new byte[]{0, 0, 0, 64, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 1, 2, 3, 4};
        var inspection = VideoProbe.inspect(corrupt);
        Assertions.assertFalse(inspection.supported());
        Assertions.assertNull(inspection.durationMs());
    }

    @Test
    void truncatedMvhdIsTolerated() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(box("ftyp", new byte[]{'i', 's', 'o', 'm'}));
        out.writeBytes(box("moov", new byte[]{0, 0, 0, 6, 'm', 'v', 'h', 'd', 0, 0, 0, 0}));
        var inspection = VideoProbe.inspect(out.toByteArray());
        Assertions.assertFalse(inspection.supported());
        Assertions.assertNull(inspection.durationMs());
    }

    // ---------------------------------------------------------------- fixture 构造

    /** version 0 mvhd：timescale/duration 各 4 字节。 */
    private static byte[] minimalMp4(int timescale, int duration, int width, int height) {
        return wrap(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(box("ftyp", new byte[]{'i', 's', 'o', 'm'}));
            byte[] mvhdPayload = new byte[20];
            mvhdPayload[0] = 0; // version 0
            putInt(mvhdPayload, 12, timescale);
            putInt(mvhdPayload, 16, duration);
            byte[] tkhdPayload = new byte[84];
            tkhdPayload[0] = 0; // version 0
            putInt(tkhdPayload, 76, width << 16);  // 16.16 定点宽
            putInt(tkhdPayload, 80, height << 16); // 16.16 定点高
            byte[] trak = box("trak", box("tkhd", tkhdPayload));
            out.writeBytes(box("moov", concat(box("mvhd", mvhdPayload), trak)));
            return out.toByteArray();
        });
    }

    /** version 1 mvhd：creation/modification 各 8 字节，timescale 4 字节、duration 8 字节。 */
    private static byte[] minimalMp4V1(int timescale, long duration, int width, int height) {
        return wrap(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(box("ftyp", new byte[]{'i', 's', 'o', 'm'}));
            byte[] mvhdPayload = new byte[32];
            mvhdPayload[0] = 1; // version 1
            putInt(mvhdPayload, 20, timescale);
            putLong(mvhdPayload, 24, duration);
            byte[] tkhdPayload = new byte[84];
            tkhdPayload[0] = 0;
            putInt(tkhdPayload, 76, width << 16);
            putInt(tkhdPayload, 80, height << 16);
            byte[] trak = box("trak", box("tkhd", tkhdPayload));
            out.writeBytes(box("moov", concat(box("mvhd", mvhdPayload), trak)));
            return out.toByteArray();
        });
    }

    private static byte[] box(String type, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(8 + payload.length);
        buffer.put(type.getBytes(StandardCharsets.US_ASCII));
        buffer.put(payload);
        return buffer.array();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] all = new byte[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static void putLong(byte[] target, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            target[offset + i] = (byte) (value >>> (8 * (7 - i)));
        }
    }

    private static <T> T wrap(IoSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws Exception;
    }
}
