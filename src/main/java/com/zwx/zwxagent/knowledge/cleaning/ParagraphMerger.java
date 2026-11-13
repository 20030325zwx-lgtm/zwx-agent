package com.zwx.zwxagent.knowledge.cleaning;

import com.zwx.zwxagent.knowledge.document.AgentKnowledgeBlock;
import com.zwx.zwxagent.knowledge.document.MineruBlockParser;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 段落修复：把 PDF 解析产生的“段内异常换行”合回一段。
 *
 * <p>硬约束（保护引用定位与结构）：</p>
 * <ul>
 *   <li>只合并<b>同一页</b>的相邻文本块。跨页合并会把两个页的 bbox 压成一个，
 *       引用回图（pageNumber + bbox 打开原图区域）会直接错位，这是当前产品能力，不能牺牲。</li>
 *   <li>上一块必须以“非句末标点”结尾，下一块不能以列表编号/章节编号开头。</li>
 *   <li>标题、表格、公式、图片一律不参与合并。</li>
 * </ul>
 */
@Component
public class ParagraphMerger {

    private static final Pattern TERMINAL = Pattern.compile("[。．.!！?？;；:：…\"”’）)】\\]]$");
    private static final Pattern LIST_OR_CLAUSE_START = Pattern.compile(
            "^([一二三四五六七八九十百]+[、.．]|（[一二三四五六七八九十百\\d]+）|\\([\\d一二三四五六七八九十百]+\\)"
                    + "|\\d+[、.．)]|[-*+•·●○]|第[一二三四五六七八九十百\\d]+[章节条]|附录)");
    /** 下一块过长通常已是完整段落，合并会制造超长块。 */
    private static final int MAX_NEXT_BLOCK_CHARS = 120;

    private final CleaningSettings settings;

    public ParagraphMerger(CleaningSettings settings) {
        this.settings = settings;
    }

    public boolean canMerge(MineruBlockParser.MineruBlock previous, MineruBlockParser.MineruBlock next) {
        if (previous == null || next == null) return false;
        if (!AgentKnowledgeBlock.TEXT.equals(previous.type()) || !AgentKnowledgeBlock.TEXT.equals(next.type())) return false;
        if (previous.heading() || next.heading()) return false;
        if (previous.pageNumber() == null || !previous.pageNumber().equals(next.pageNumber())) return false;
        String left = previous.text() == null ? "" : previous.text().strip();
        String right = next.text() == null ? "" : next.text().strip();
        if (left.isEmpty() || right.isEmpty()) return false;
        if (right.length() > MAX_NEXT_BLOCK_CHARS) return false;
        if (TERMINAL.matcher(left).find()) return false;
        if (LIST_OR_CLAUSE_START.matcher(right).find()) return false;
        int mergedLength = left.length() + right.length();
        return mergedLength <= settings.maxParagraphMergeChars();
    }

    /** 合并两个块：保留前一块的 blockId/页码/rawJson；bbox 取并集，取不到时保留前一块。 */
    public MineruBlockParser.MineruBlock merge(MineruBlockParser.MineruBlock previous,
                                               MineruBlockParser.MineruBlock next) {
        String left = previous.text() == null ? "" : previous.text().strip();
        String right = next.text() == null ? "" : next.text().strip();
        String text = left + joiner(left, right) + right;
        return new MineruBlockParser.MineruBlock(previous.blockId(), previous.type(), previous.pageNumber(),
                unionBbox(previous.bbox(), next.bbox()), text, previous.caption(), previous.html(),
                previous.table(), previous.imagePath(), previous.heading(), previous.headingLevel(),
                previous.rawJson());
    }

    private String joiner(String left, String right) {
        char last = left.charAt(left.length() - 1);
        char first = right.charAt(0);
        boolean latinPair = ((last >= 'A' && last <= 'Z') || (last >= 'a' && last <= 'z') || Character.isDigit(last))
                && ((first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z') || Character.isDigit(first));
        return latinPair ? " " : "";
    }

    /** bbox 形如 [x0, y0, x1, y1]；解析失败保留前一块，不编造坐标。 */
    String unionBbox(String left, String right) {
        double[] first = parseBbox(left);
        double[] second = parseBbox(right);
        if (first == null) return left;
        if (second == null) return left;
        return "[" + Math.min(first[0], second[0]) + ", " + Math.min(first[1], second[1]) + ", "
                + Math.max(first[2], second[2]) + ", " + Math.max(first[3], second[3]) + "]";
    }

    private double[] parseBbox(String value) {
        if (value == null || value.isBlank()) return null;
        String body = value.strip();
        if (body.startsWith("[")) body = body.substring(1);
        if (body.endsWith("]")) body = body.substring(0, body.length() - 1);
        String[] parts = body.split(",");
        if (parts.length != 4) return null;
        try {
            double[] parsed = new double[4];
            for (int index = 0; index < 4; index++) parsed[index] = Double.parseDouble(parts[index].trim());
            return parsed;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
