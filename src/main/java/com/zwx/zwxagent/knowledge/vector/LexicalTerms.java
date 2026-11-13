package com.zwx.zwxagent.knowledge.vector;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 词法通道查询词提取：中文 2-gram 滑窗 + 英文/数字词元。
 * 从 {@code AgentKnowledgeRagService} 迁出（pgvector 词法通道与 ragTrace 的 lexicalScore 共用）；
 * Weaviate 通道不使用 2-gram（BM25 由服务端 gse 分词），但 trace 打分逻辑保持两侧一致。
 */
public final class LexicalTerms {

    private LexicalTerms() {
    }

    /** 高频虚词 2-gram 与英文短词：不剔除会贡献大量假命中（评审 #1）。 */
    static final Set<String> STOPWORDS = Set.of(
            "的是", "一个", "什么", "怎么", "怎样", "如何", "可以", "可能", "我们", "你们", "他们",
            "这个", "那个", "哪些", "多少", "没有", "不是", "就是", "还是", "以及", "或者", "并且",
            "关于", "对于", "通过", "如果", "因为", "所以", "但是", "然后", "进行", "使用",
            "相关", "内容", "信息", "时候", "出现", "之后", "以后",
            "of", "to", "in", "on", "or", "is", "it", "an", "as", "at", "by", "if", "no", "do", "so", "up");

    public static List<String> extract(String query) {
        String normalized = query.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9]+", " ").trim();
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String word : normalized.split("\\s+")) {
            if (word.isBlank()) continue;
            boolean han = word.codePoints().anyMatch(code -> Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN);
            if (!han && word.length() >= 2 && !STOPWORDS.contains(word)) terms.add(word);
        }
        String han = normalized.replaceAll("[^\\p{IsHan}]", "");
        for (int i = 0; i + 1 < han.length(); i++) {
            String gram = han.substring(i, i + 2);
            if (!STOPWORDS.contains(gram)) terms.add(gram);
        }
        // 32 而非 20：整句滑窗下 20 会把长问题后半句整体丢弃；32 覆盖约 60 字问题的全部 2-gram
        return terms.stream().limit(32).toList();
    }
}
