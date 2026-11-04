package com.zwx.zwxartifactmcpserver.render;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 格式转换：docx/pptx/xlsx → pdf，依赖 headless LibreOffice。
 * 容器镜像需安装 libreoffice-core + libreoffice-writer + 中文字体；未安装时返回可读错误。
 */
@Service
public class ConversionService {

    private static final Logger log = LoggerFactory.getLogger(ConversionService.class);

    private final String configuredSoffice;
    private final long timeoutSeconds;

    public ConversionService(@Value("${artifact.soffice-path:}") String configuredSoffice,
                             @Value("${artifact.timeout-seconds:120}") long timeoutSeconds) {
        this.configuredSoffice = configuredSoffice;
        this.timeoutSeconds = Math.max(10, timeoutSeconds);
    }

    public byte[] toPdf(String fileName, byte[] source) throws IOException, InterruptedException {
        String soffice = resolveSoffice();
        if (soffice == null) {
            throw new IllegalStateException("LibreOffice (soffice) is not installed on the server");
        }
        String safeName = sanitize(fileName);
        Path workDir = Files.createTempDirectory("artifact-convert");
        try {
            Path input = workDir.resolve(safeName);
            Files.write(input, source);
            ProcessBuilder builder = new ProcessBuilder(List.of(
                    soffice, "--headless", "--norestore", "--nolockcheck",
                    "--convert-to", "pdf", "--outdir", workDir.toString(), input.toString()));
            builder.redirectErrorStream(true);
            Process process = builder.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("conversion timed out after " + timeoutSeconds + "s");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("conversion failed with exit code " + process.exitValue());
            }
            Path pdf = workDir.resolve(safeName.replaceAll("\\.[^.]+$", "") + ".pdf");
            if (!Files.isRegularFile(pdf)) {
                throw new IllegalStateException("conversion produced no pdf output");
            }
            log.info("Converted {} -> pdf ({} bytes)", safeName, Files.size(pdf));
            return Files.readAllBytes(pdf);
        } finally {
            deleteRecursively(workDir);
        }
    }

    public String resolveSoffice() {
        if (configuredSoffice != null && !configuredSoffice.isBlank() && new File(configuredSoffice).canExecute()) {
            return configuredSoffice;
        }
        for (String candidate : List.of(
                "/usr/bin/soffice",
                "/usr/lib/libreoffice/program/soffice",
                "/Applications/LibreOffice.app/Contents/MacOS/soffice")) {
            if (new File(candidate).canExecute()) return candidate;
        }
        return null;
    }

    private String sanitize(String fileName) {
        String base = fileName == null || fileName.isBlank() ? "input" : fileName;
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        return base.length() > 80 ? base.substring(base.length() - 80) : base;
    }

    private void deleteRecursively(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
