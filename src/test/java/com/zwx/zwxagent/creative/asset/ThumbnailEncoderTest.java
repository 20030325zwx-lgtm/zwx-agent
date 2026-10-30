package com.zwx.zwxagent.creative.asset;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/** A17（C11）：缩略图降采样与失败降级——大图缩到 320px，损坏字节返回 null。 */
class ThumbnailEncoderTest {

    @Test
    void downsamplesLargeImageWithinBudget() throws Exception {
        BufferedImage source = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = source.createGraphics();
        graphics.setColor(new Color(32, 96, 200));
        graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
        graphics.dispose();
        ByteArrayOutputStream original = new ByteArrayOutputStream();
        ImageIO.write(source, "png", original);

        byte[] thumbnail = ThumbnailEncoder.encode(original.toByteArray());
        Assertions.assertNotNull(thumbnail);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumbnail));
        Assertions.assertNotNull(decoded);
        Assertions.assertTrue(Math.max(decoded.getWidth(), decoded.getHeight()) <= ThumbnailEncoder.MAX_EDGE);
        Assertions.assertEquals(320, decoded.getWidth());
        Assertions.assertEquals(213, decoded.getHeight());
    }

    @Test
    void corruptedBytesYieldNullWithoutThrowing() {
        Assertions.assertNull(ThumbnailEncoder.encode(new byte[]{1, 2, 3, 4}));
        Assertions.assertNull(ThumbnailEncoder.encode(new byte[0]));
        Assertions.assertNull(ThumbnailEncoder.encode(null));
    }

    @Test
    void thumbnailKeyKeepsDirectoryAndAddsSuffix() {
        Assertions.assertEquals("a/b/original.png.thumb.png",
                ThumbnailEncoder.thumbnailKey("a/b/original.png"));
    }
}
