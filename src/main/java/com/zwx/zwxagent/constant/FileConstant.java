package com.zwx.zwxagent.constant;

/**
 * 文件常量
 */
public interface FileConstant {

    /**
     * 文件保存目录
     */
    String FILE_SAVE_DIR = resolveTempDir();

    private static String resolveTempDir() {
        String configured = System.getenv("APP_TEMP_DIR");
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("app.temp-dir");
        }
        return configured == null || configured.isBlank()
                ? System.getProperty("user.dir") + "/temp"
                : configured;
    }
}
