package com.zwx.zwxagent.knowledge.cleaning;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 文本规范化工具：只做“确定的、可逆推的”格式修复，不改写语义。
 *
 * <p>刻意不做的事情：</p>
 * <ul>
 *   <li>不做 NFKC（会把全角标点、圈码 ①②③ 改成 ASCII，破坏业务编号与中文排版）；只做 NFC。</li>
 *   <li>不改写中文标点、不补全句子、不做同义词替换。</li>
 *   <li>不碰表格 HTML（留给 MyTokenTextSplitter 转行记录）与公式块（调用方不传 EQUATION）。</li>
 * </ul>
 */
public final class TextNormalizer {

    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");
    /** 英文 PDF 的断词：effi-\nciency → efficiency（只在两侧都是字母时生效）。 */
    private static final Pattern HYPHEN_BREAK = Pattern.compile("(?<=[A-Za-z])-[ \\t]*\\n[ \\t]*(?=[a-z])");
    private static final Pattern LINE_TRAILING_SPACE = Pattern.compile("[ \\t]+\\n");
    /** PDF 抽出的中文正文常在字间插空格（“发 生 交 通”），只合并汉字之间的单个空格，不跨行。 */
    private static final Pattern CJK_INNER_SPACE = Pattern.compile("(?<=[\\p{IsHan}])[ \\t]+(?=[\\p{IsHan}])");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t\\u00A0\\u3000]{2,}");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^\\]]{0,200}]\\([^)]{0,500}\\)");
    private static final Pattern HTML_TAG = Pattern.compile("</?[A-Za-z][^<>]{0,300}>");

    private TextNormalizer() {
    }

    /** 块级规范化：格式修复，保留换行结构（换行的是否合并由 ParagraphMerger 决定）。 */
    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String text = Normalizer.normalize(raw, Normalizer.Form.NFC);
        text = ZERO_WIDTH.matcher(text).replaceAll("");
        text = CONTROL.matcher(text).replaceAll("");
        text = toHalfWidthAlnum(text);
        text = HYPHEN_BREAK.matcher(text).replaceAll("");
        text = LINE_TRAILING_SPACE.matcher(text).replaceAll("\n");
        text = CJK_INNER_SPACE.matcher(text).replaceAll("");
        text = MULTI_SPACE.matcher(text).replaceAll(" ");
        text = BLANK_LINES.matcher(text).replaceAll("\n\n");
        return text.strip();
    }

    /**
     * 切片级规范化：在块级规则之上，再去掉 Markdown 图片语法与残留 HTML 标签。
     * 含 {@code <table>} 的文本原样保留，交给表格切分逻辑处理，避免把表格结构抹平。
     */
    public static String normalizeChunkText(String raw) {
        String text = normalize(raw);
        if (text.isEmpty()) return text;
        text = MARKDOWN_IMAGE.matcher(text).replaceAll(" ");
        if (!text.toLowerCase(Locale.ROOT).contains("<table")) {
            text = HTML_TAG.matcher(text).replaceAll("");
        }
        text = MULTI_SPACE.matcher(text).replaceAll(" ");
        text = BLANK_LINES.matcher(text).replaceAll("\n\n");
        return text.strip();
    }

    /**
     * 全角字母数字 → 半角。只映射 [Ａ-Ｚａ-ｚ０-９]，全角标点（，。：；！？）保持原样，
     * 避免改变中文正文的标点风格与既有检索基线。
     */
    static String toHalfWidthAlnum(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            boolean fullWidthAlnum = (value >= '\uFF10' && value <= '\uFF19')
                    || (value >= '\uFF21' && value <= '\uFF3A')
                    || (value >= '\uFF41' && value <= '\uFF5A');
            builder.append(fullWidthAlnum ? (char) (value - 0xFEE0) : value);
        }
        return builder.toString();
    }
}
