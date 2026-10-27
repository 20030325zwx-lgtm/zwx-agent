package com.zwx.zwxagent.capability.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwx.zwxagent.platform.workspace.ProjectExecutionLockService;
import com.zwx.zwxagent.platform.workspace.ProjectPathGuard;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 探测并执行受控的 build/test/lint 任务；不接受任意命令文本。 */
public class ProjectTaskTool {

    private static final int MAX_OUTPUT_CHARS = 100_000;
    private static final Set<String> ENV_ALLOWLIST = Set.of(
            "PATH", "JAVA_HOME", "M2_HOME", "MAVEN_HOME", "GRADLE_HOME", "GRADLE_USER_HOME",
            "HOME", "USER", "TMPDIR", "TMP", "TEMP", "LANG", "LC_ALL", "CI", "NO_COLOR");

    private final String projectId;
    private final String runId;
    private final ProjectPathGuard guard;
    private final ProjectExecutionLockService lockService;
    private final int timeoutSeconds;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProjectTaskTool(String projectId, Path projectRoot, String runId,
                           ProjectExecutionLockService lockService, int timeoutSeconds) {
        this.projectId = projectId;
        this.runId = runId;
        this.guard = new ProjectPathGuard(projectRoot);
        this.lockService = lockService;
        this.timeoutSeconds = Math.max(10, timeoutSeconds);
    }

    @Tool(description = "List detected build, test and lint tasks for the current project. Run this before runProjectTask to obtain an allowed task id.")
    public String listProjectTasks() {
        Map<String, ProjectTask> tasks = detectTasks();
        if (tasks.isEmpty()) return "No supported build, test or lint tasks were detected";
        StringBuilder result = new StringBuilder();
        tasks.values().forEach(task -> result.append(task.id()).append(" | ")
                .append(task.kind()).append(" | cwd=").append(task.relativeDirectory()).append(" | ")
                .append(String.join(" ", task.command())).append('\n'));
        return result.toString();
    }

    @Tool(description = "Run one detected build, test or lint task by exact task id. Repository scripts execute code and therefore require user confirmation.")
    public String runProjectTask(@ToolParam(description = "Exact task id returned by listProjectTasks") String taskId) {
        ProjectTask task = detectTasks().get(taskId == null ? "" : taskId.trim());
        if (task == null) return "Error: unknown project task id; call listProjectTasks first";
        lockService.acquire(projectId, runId);
        long started = System.nanoTime();
        CommandResult result = execute(task);
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toSeconds();
        return "Task " + task.id() + " " + (result.success() ? "completed" : "failed")
                + " (exit=" + result.exitCode() + ", " + elapsed + "s)\n" + result.output();
    }

    private Map<String, ProjectTask> detectTasks() {
        Map<String, ProjectTask> tasks = new LinkedHashMap<>();
        detectMaven(tasks);
        detectGradle(tasks);
        detectNode(tasks);
        return tasks;
    }

    private void detectMaven(Map<String, ProjectTask> tasks) {
        if (!Files.isRegularFile(guard.root().resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)) return;
        String executable = Files.isExecutable(guard.root().resolve("mvnw")) ? "./mvnw" : "mvn";
        add(tasks, "maven:build", "build", ".", executable, "-DskipTests", "package");
        add(tasks, "maven:test", "test", ".", executable, "test");
        try {
            String pom = Files.readString(guard.root().resolve("pom.xml"), StandardCharsets.UTF_8);
            if (pom.contains("maven-checkstyle-plugin")) add(tasks, "maven:lint", "lint", ".", executable, "checkstyle:check");
            else if (pom.contains("maven-pmd-plugin")) add(tasks, "maven:lint", "lint", ".", executable, "pmd:check");
            else if (pom.contains("spotbugs-maven-plugin")) add(tasks, "maven:lint", "lint", ".", executable, "spotbugs:check");
        } catch (Exception ignored) {
        }
    }

    private void detectGradle(Map<String, ProjectTask> tasks) {
        boolean gradle = Files.exists(guard.root().resolve("build.gradle"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(guard.root().resolve("build.gradle.kts"), LinkOption.NOFOLLOW_LINKS);
        if (!gradle) return;
        String executable = Files.isExecutable(guard.root().resolve("gradlew")) ? "./gradlew" : "gradle";
        add(tasks, "gradle:build", "build", ".", executable, "build");
        add(tasks, "gradle:test", "test", ".", executable, "test");
        add(tasks, "gradle:lint", "lint", ".", executable, "check");
    }

    private void detectNode(Map<String, ProjectTask> tasks) {
        List<Path> manifests = new ArrayList<>();
        Path rootManifest = guard.root().resolve("package.json");
        if (Files.isRegularFile(rootManifest, LinkOption.NOFOLLOW_LINKS)) manifests.add(rootManifest);
        try (var children = Files.list(guard.root())) {
            children.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .map(path -> path.resolve("package.json"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .limit(20)
                    .forEach(manifests::add);
        } catch (Exception ignored) {
        }
        for (Path manifest : manifests) detectNodeManifest(tasks, manifest);
    }

    private void detectNodeManifest(Map<String, ProjectTask> tasks, Path manifest) {
        try {
            JsonNode scripts = objectMapper.readTree(manifest.toFile()).path("scripts");
            String directory = guard.relative(manifest.getParent());
            if (directory.isEmpty()) directory = ".";
            String manager = packageManager(manifest.getParent());
            for (String kind : List.of("build", "test", "lint")) {
                if (!scripts.hasNonNull(kind)) continue;
                String id = manager + ":" + (".".equals(directory) ? "root" : directory.replace('/', ':')) + ":" + kind;
                add(tasks, id, kind, directory, manager, "run", kind);
            }
        } catch (Exception ignored) {
        }
    }

    private String packageManager(Path directory) {
        if (Files.exists(directory.resolve("pnpm-lock.yaml"), LinkOption.NOFOLLOW_LINKS)) return "pnpm";
        if (Files.exists(directory.resolve("yarn.lock"), LinkOption.NOFOLLOW_LINKS)) return "yarn";
        return "npm";
    }

    private void add(Map<String, ProjectTask> tasks, String id, String kind, String directory, String... command) {
        tasks.put(id, new ProjectTask(id, kind, directory, List.of(command)));
    }

    private CommandResult execute(ProjectTask task) {
        Path directory = guard.resolveExisting(task.relativeDirectory());
        ProcessBuilder builder = new ProcessBuilder(task.command()).directory(directory.toFile()).redirectErrorStream(true);
        Map<String, String> inherited = new LinkedHashMap<>(builder.environment());
        builder.environment().clear();
        ENV_ALLOWLIST.forEach(name -> {
            String value = inherited.get(name);
            if (value != null) builder.environment().put(name, value);
        });
        builder.environment().put("CI", "true");
        builder.environment().put("NO_COLOR", "1");
        StringBuilder output = new StringBuilder();
        try {
            Process process = builder.start();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        if (output.length() >= MAX_OUTPUT_CHARS) {
                            process.destroyForcibly();
                            break;
                        }
                        output.append(line).append('\n');
                    }
                } catch (Exception ignored) {
                }
            });
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();
            reader.join(2000);
            if (!completed) return new CommandResult(false, -1, output + "\n...[task timed out]");
            if (output.length() >= MAX_OUTPUT_CHARS) output.append("...[output truncated]\n");
            return new CommandResult(process.exitValue() == 0, process.exitValue(), output.toString());
        } catch (Exception exception) {
            return new CommandResult(false, -1, "Unable to run task: " + exception.getMessage());
        }
    }

    private record ProjectTask(String id, String kind, String relativeDirectory, List<String> command) {
    }

    private record CommandResult(boolean success, int exitCode, String output) {
    }
}
