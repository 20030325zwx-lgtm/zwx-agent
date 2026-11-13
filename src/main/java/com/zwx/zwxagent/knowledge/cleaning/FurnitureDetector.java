package com.zwx.zwxagent.knowledge.cleaning;

import com.zwx.zwxagent.knowledge.document.AgentKnowledgeBlock;
import com.zwx.zwxagent.knowledge.document.MineruBlockParser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 页眉页脚识别：跨页重复 + 短文本 + 非标题。
 *
 * <p>为什么不能只看 label：新统一解析服务已在解析侧丢弃 page_header/page_footer，
 * 真正的漏网之鱼是“被当成普通 text 的页眉”。因此这里用文档级统计兜底：
 * 同一文本（归一化后）在足够比例的页面上重复出现，且长度很短、不是标题，判定为页眉页脚。</p>
 *
 * <p>误删保护：标题块（heading=true）不参与判定；阈值取 max(2, ceil(页数 × ratio))，
 * 单页文档、只在两页偶然重复的正文不会被误杀。</p>
 */
@Component
public class FurnitureDetector {

    /** 尾部页码：正文“第 12 页”“- 12 -”“3/10”“Page 3”在跨页重复时并入同一 key。 */
    private static final List<Pattern> TRAILING_PAGE_PATTERNS = List.of(
            Pattern.compile("(?i)(?:page|p\\.)\\s*\\d{1,4}\\s*$"),
            Pattern.compile("共\\s*\\d{1,4}\\s*页\\s*$"),
            Pattern.compile("第\\s*\\d{1,4}\\s*页\\s*$"),
            Pattern.compile("\\d{1,4}\\s*/\\s*\\d{1,4}\\s*$"),
            Pattern.compile("[-—–·|]\\s*\\d{1,4}\\s*$"),
            Pattern.compile("\\s\\d{1,4}\\s*$")
    );

    private final CleaningSettings settings;

    public FurnitureDetector(CleaningSettings settings) {
        this.settings = settings;
    }

    /** @return 判定为页眉页脚的 blockId 集合 */
    public Set<String> detect(List<MineruBlockParser.MineruBlock> blocks) {
        if (blocks == null || blocks.isEmpty() || !settings.furnitureEnabled()) return Set.of();
        Map<String, Set<Integer>> pagesByKey = new LinkedHashMap<>();
        Map<String, List<String>> idsByKey = new HashMap<>();
        Set<Integer> allPages = new HashSet<>();

        for (MineruBlockParser.MineruBlock block : blocks) {
            if (block == null || !AgentKnowledgeBlock.TEXT.equals(block.type()) || block.heading()) continue;
            if (block.pageNumber() != null) allPages.add(block.pageNumber());
            String text = TextNormalizer.normalize(block.text());
            if (text.isBlank() || text.length() > settings.furnitureMaxChars()) continue;
            String key = key(text);
            if (key.length() < 4) continue;
            pagesByKey.computeIfAbsent(key, ignored -> new HashSet<>())
                    .add(block.pageNumber() == null ? -1 : block.pageNumber());
            idsByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(block.blockId());
        }
        // 两页文档无法区分“页脚”和“正文重复”，统计不可靠，直接不做判定。
        if (allPages.size() < 3) return Set.of();

        int threshold = Math.max(2, (int) Math.ceil(allPages.size() * settings.furniturePageRatio()));
        Set<String> result = new HashSet<>();
        pagesByKey.forEach((key, pages) -> {
            if (pages.size() >= threshold && !pages.contains(-1)) {
                result.addAll(idsByKey.getOrDefault(key, List.of()));
            }
        });
        return result;
    }

    /**
     * 去掉尾部页码得到页眉/页脚指纹。
     *
     * <p>刻意<b>不</b>把正文里的数字统一替换成占位符：像“正文内容第 1 段……”这种按页编号的正文
     * 会因此被误判成页眉（真实踩过的误杀路径）。只剥离明确的尾部页码表达式。</p>
     */
    private String key(String normalized) {
        String key = normalized;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Pattern pattern : TRAILING_PAGE_PATTERNS) {
                String next = pattern.matcher(key).replaceAll("");
                if (!next.equals(key)) {
                    key = next;
                    changed = true;
                }
            }
        }
        return key.strip().toLowerCase(Locale.ROOT);
    }
}
