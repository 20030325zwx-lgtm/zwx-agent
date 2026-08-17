package com.zwx.zwxagent.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
public class MineruPdfExtractor {
    private final boolean enabled;
    private final String command;
    private final String backend;
    private final Duration timeout;

    public MineruPdfExtractor(@Value("${app.pdf.mineru.enabled:false}") boolean enabled,
                              @Value("${app.pdf.mineru.command:mineru}") String command,
                              @Value("${app.pdf.mineru.backend:pipeline}") String backend,
                              @Value("${app.pdf.mineru.timeout-seconds:300}") long timeoutSeconds) {
        this.enabled = enabled;
        this.command = command;
        this.backend = backend;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public Optional<String> extract(byte[] pdf) {
        if (!enabled) return Optional.empty();
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("zwx-mineru-");
            Path input = workspace.resolve("document.pdf");
            Path output = workspace.resolve("output");
            Path log = workspace.resolve("mineru.log");
            Files.write(input, pdf);
            Process process = new ProcessBuilder(command, "-p", input.toString(), "-o", output.toString(), "-b", backend)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                return Optional.empty();
            }
            if (process.exitValue() != 0) return Optional.empty();
            try (var files = Files.walk(output)) {
                String markdown = files.filter(path -> path.toString().endsWith(".md"))
                        .sorted(Comparator.comparing(Path::toString))
                        .map(this::readText).filter(text -> !text.isBlank())
                        .findFirst().orElse("");
                return markdown.isBlank() ? Optional.empty() : Optional.of(markdown);
            }
        } catch (Exception exception) {
            return Optional.empty();
        } finally {
            if (workspace != null) deleteWorkspace(workspace);
        }
    }

    private String readText(Path path) {
        try { return Files.readString(path, StandardCharsets.UTF_8); }
        catch (IOException exception) { return ""; }
    }

    private void deleteWorkspace(Path workspace) {
        try (var paths = Files.walk(workspace)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
