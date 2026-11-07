package com.zwx.zwxagent.creative.storage;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** A15（C06）：本地存储拒绝符号链接逃逸——读写都不能越过基础目录。 */
class LocalMediaStorageTest {

    @Test
    void rejectsSymlinkEscapeOnWriteAndRead(@TempDir Path base, @TempDir Path outside) throws Exception {
        try {
            Files.createSymbolicLink(base.resolve("link"), outside);
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            Assumptions.assumeTrue(false, "当前文件系统不支持符号链接，跳过");
        }
        LocalMediaStorage storage = new LocalMediaStorage(base);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> storage.put("link/evil.png", "image/png",
                        new ByteArrayInputStream(new byte[]{1}), 1));
        Files.write(outside.resolve("secret.png"), new byte[]{1, 2, 3});
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> storage.open("link/secret.png"));
    }

    @Test
    void normalRoundTripStaysInsideBase(@TempDir Path base) throws Exception {
        LocalMediaStorage storage = new LocalMediaStorage(base);
        byte[] payload = new byte[]{9, 8, 7};
        storage.put("a/b.png", "image/png", new ByteArrayInputStream(payload), payload.length);
        Assertions.assertArrayEquals(payload, storage.open("a/b.png").readAllBytes());
        Assertions.assertEquals(3, storage.size("a/b.png"));
        storage.delete("a/b.png");
        Assertions.assertFalse(Files.exists(base.resolve("a/b.png")));
    }
}
