package com.zwx.zwxagent.creative.asset;

/**
 * 图片文件嗅探（C06）：按文件签名判定 PNG/JPEG/WebP，并解析尺寸用于像素预算校验（A14）。
 */
public final class ImageInspector {

    private ImageInspector() {
    }

    public static ImageInspection inspect(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return new ImageInspection(ImageInspection.Type.PNG, readPngWidth(bytes), readPngHeight(bytes));
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            var dims = readJpegSize(bytes);
            return new ImageInspection(ImageInspection.Type.JPEG, dims[0], dims[1]);
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            var dims = readWebPSize(bytes);
            return new ImageInspection(ImageInspection.Type.WEBP, dims[0], dims[1]);
        }
        return new ImageInspection(ImageInspection.Type.OTHER, null, null);
    }

    public static boolean withinByteBudget(int byteSize) {
        return byteSize > 0 && byteSize <= 20 * 1024 * 1024;
    }

    private static int readPngWidth(byte[] b) {
        return ((b[16] & 0xFF) << 24) | ((b[17] & 0xFF) << 16) | ((b[18] & 0xFF) << 8) | (b[19] & 0xFF);
    }

    private static int readPngHeight(byte[] b) {
        return ((b[20] & 0xFF) << 24) | ((b[21] & 0xFF) << 16) | ((b[22] & 0xFF) << 8) | (b[23] & 0xFF);
    }

    /** 扫描 SOF0/1/2/9/10 段读取 JPEG 尺寸；解析失败返回 0。 */
    private static int[] readJpegSize(byte[] b) {
        int i = 2;
        while (i + 9 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) { i++; continue; }
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { i += 2; continue; }
            int length = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if ((marker >= 0xC0 && marker <= 0xCF) && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                int height = ((b[i + 5] & 0xFF) << 8) | (b[i + 6] & 0xFF);
                int width = ((b[i + 7] & 0xFF) << 8) | (b[i + 8] & 0xFF);
                return new int[]{width, height};
            }
            i += 2 + length;
        }
        return new int[]{0, 0};
    }

    /** VP8X 扩展头：24-26 宽-1、27-29 高-1（小端 24 位）；其他 WebP 子格式返回 0。 */
    private static int[] readWebPSize(byte[] b) {
        if (b.length < 30 || b[12] != 'V' || b[13] != 'P' || b[14] != '8' || b[15] != 'X') return new int[]{0, 0};
        int width = 1 + ((b[24] & 0xFF) | ((b[25] & 0xFF) << 8) | ((b[26] & 0xFF) << 16));
        int height = 1 + ((b[27] & 0xFF) | ((b[28] & 0xFF) << 8) | ((b[29] & 0xFF) << 16));
        return new int[]{width, height};
    }
}
