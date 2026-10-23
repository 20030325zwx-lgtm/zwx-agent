package com.zwx.zwxagent.knowledge.document;

import java.util.LinkedHashMap;
import java.util.Map;

/** Full result package from the MinerU precise parsing API. */
public final class MineruParseResult {
    private final String markdown;
    private final Map<String, byte[]> files;

    public MineruParseResult(String markdown, Map<String, byte[]> files) {
        this.markdown = markdown;
        Map<String, byte[]> copy = new LinkedHashMap<>();
        files.forEach((name, contents) -> copy.put(name, contents.clone()));
        this.files = Map.copyOf(copy);
    }

    public String markdown() {
        return markdown;
    }

    public Map<String, byte[]> files() {
        Map<String, byte[]> copy = new LinkedHashMap<>();
        files.forEach((name, contents) -> copy.put(name, contents.clone()));
        return Map.copyOf(copy);
    }

    public java.util.Set<String> fileNames() {
        return files.keySet();
    }

    public long countFiles(String extension) {
        String normalized = extension.toLowerCase(java.util.Locale.ROOT);
        return files.keySet().stream().filter(name -> name.toLowerCase(java.util.Locale.ROOT).endsWith(normalized)).count();
    }

    public Map<String, String> textFiles(String extension) {
        Map<String, String> matches = new LinkedHashMap<>();
        files.forEach((name, contents) -> {
            if (name.toLowerCase(java.util.Locale.ROOT).endsWith(extension.toLowerCase(java.util.Locale.ROOT))) {
                matches.put(name, new String(contents, java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        return Map.copyOf(matches);
    }
}
