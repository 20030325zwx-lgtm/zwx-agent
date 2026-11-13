package com.zwx.zwxagent.knowledge.cleaning;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 文档清洗配置（app.knowledge.cleaning.*）。
 *
 * <p>全部阈值来自配置而非硬编码：清洗属于“可关闭、可回退”的增强能力，
 * 出问题时把 {@code enabled} 置 false 即回到清洗前的行为，不需要改代码。</p>
 */
@Component
public class CleaningSettings {

    /** 清洗规则版本：写进清洗记录，便于后续换规则后判断哪些文档需要重新清洗。 */
    public static final int CLEANING_VERSION = 1;

    private final boolean enabled;
    private final int minChunkChars;
    private final double maxDeletionRatio;
    private final boolean furnitureEnabled;
    private final int furnitureMaxChars;
    private final double furniturePageRatio;
    private final boolean dedupeEnabled;
    private final int dedupeMinChars;
    private final int maxParagraphMergeChars;

    public CleaningSettings(@Value("${app.knowledge.cleaning.enabled:true}") boolean enabled,
                            @Value("${app.knowledge.cleaning.min-chunk-chars:12}") int minChunkChars,
                            @Value("${app.knowledge.cleaning.max-deletion-ratio:0.15}") double maxDeletionRatio,
                            @Value("${app.knowledge.cleaning.furniture-enabled:true}") boolean furnitureEnabled,
                            @Value("${app.knowledge.cleaning.furniture-max-chars:60}") int furnitureMaxChars,
                            @Value("${app.knowledge.cleaning.furniture-page-ratio:0.3}") double furniturePageRatio,
                            @Value("${app.knowledge.cleaning.dedupe-enabled:true}") boolean dedupeEnabled,
                            @Value("${app.knowledge.cleaning.dedupe-min-chars:40}") int dedupeMinChars,
                            @Value("${app.knowledge.cleaning.max-paragraph-merge-chars:1200}") int maxParagraphMergeChars) {
        this.enabled = enabled;
        this.minChunkChars = Math.max(1, minChunkChars);
        this.maxDeletionRatio = Math.min(1.0d, Math.max(0.0d, maxDeletionRatio));
        this.furnitureEnabled = furnitureEnabled;
        this.furnitureMaxChars = Math.max(4, furnitureMaxChars);
        this.furniturePageRatio = Math.min(1.0d, Math.max(0.05d, furniturePageRatio));
        this.dedupeEnabled = dedupeEnabled;
        this.dedupeMinChars = Math.max(8, dedupeMinChars);
        this.maxParagraphMergeChars = Math.max(200, maxParagraphMergeChars);
    }

    public boolean enabled() {
        return enabled;
    }

    public int minChunkChars() {
        return minChunkChars;
    }

    public double maxDeletionRatio() {
        return maxDeletionRatio;
    }

    public boolean furnitureEnabled() {
        return furnitureEnabled;
    }

    public int furnitureMaxChars() {
        return furnitureMaxChars;
    }

    public double furniturePageRatio() {
        return furniturePageRatio;
    }

    public boolean dedupeEnabled() {
        return dedupeEnabled;
    }

    public int dedupeMinChars() {
        return dedupeMinChars;
    }

    public int maxParagraphMergeChars() {
        return maxParagraphMergeChars;
    }
}
