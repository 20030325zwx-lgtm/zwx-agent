package com.zwx.zwxagent.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务器启动时读取外部配置文件（默认 /home/globe.conf，properties 格式，UTF-8），
 * 并以最高优先级注入 Spring Environment，让 API Key、JWT 密钥等由用户在服务器上自行维护。
 *
 * 文件路径可通过系统属性 globe.conf.path 或环境变量 GLOBE_CONF_PATH 覆盖。
 * 文件缺失或不可读时仅记录日志、跳过，绝不阻断启动。
 */
public class GlobeConfEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String PROPERTY_SOURCE_NAME = "globeConf";

    static final String PATH_PROPERTY = "globe.conf.path";
    static final String PATH_ENV = "GLOBE_CONF_PATH";
    static final String DEFAULT_PATH = "/home/globe.conf";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        Path file = resolvePath(environment);
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            log("未找到 " + file + "，跳过外部配置注入（使用 .env / application*.yml 默认值）");
            return;
        }
        Map<String, Object> values = parse(file);
        if (values.isEmpty()) {
            log(file + " 中没有有效配置项，跳过");
            return;
        }
        PropertySource<?> source = new MapPropertySource(PROPERTY_SOURCE_NAME, values);
        environment.getPropertySources().addFirst(source);
        log("已加载 " + file + "（" + values.size() + " 项，优先级最高；值不打印日志）");
    }

    private Path resolvePath(ConfigurableEnvironment environment) {
        String configured = environment.getProperty(PATH_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = environment.getProperty(PATH_ENV);
        }
        return Path.of(configured == null || configured.isBlank() ? DEFAULT_PATH : configured.trim());
    }

    private Map<String, Object> parse(Path file) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log("读取 " + file + " 失败：" + e.getMessage());
            return values;
        }
        for (String raw : lines) {
            String line = stripBom(raw).trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (key.isEmpty() || isPlaceholder(value)) {
                continue;
            }
            values.put(key, value);
        }
        return values;
    }

    /**
     * 未填写的占位值（如模板里的 YOUR_XXX / CHANGE_ME_XXX）视为未配置，
     * 避免模板占位符意外覆盖 .env 或环境变量里的真实值。
     */
    private boolean isPlaceholder(String value) {
        return value.isEmpty()
                || value.startsWith("YOUR_")
                || value.startsWith("CHANGE_ME_")
                || "${".equals(value);
    }

    private String stripBom(String line) {
        return line.startsWith("\uFEFF") ? line.substring(1) : line;
    }

    private void log(String message) {
        System.out.println("[globe-conf] " + message);
    }

    /**
     * 排在 ConfigDataEnvironmentPostProcessor（HIGHEST + 10）之后执行，
     * 保证加载 application*.yml 之后再插入 globeConf（addFirst → 优先级最高）。
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
