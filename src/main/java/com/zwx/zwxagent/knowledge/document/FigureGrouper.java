package com.zwx.zwxagent.knowledge.document;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 大图防碎：把解析层产出的 figure 块按几何/图注信号合并为「逻辑图」组。
 * MinerU 版面检测偶发把一张大流程图切成相邻多块；合并后交给视觉模型整体描述，
 * 避免节点/箭头跨块断裂。跨页默认不合并（误拼两张图的代价远大于漏拼）。
 */
public final class FigureGrouper {

    /** 相邻块的bbox 间隔阈值（pt）：小于该值视为紧邻。 */
    static final double MAX_GAP = 15.0;
    /** 合并要求某一轴上重叠率 ≥ 该值（同图碎片在另一轴上高度/宽度基本对齐）。 */
    static final double MIN_OVERLAP_RATIO = 0.5;
    /** 单组块数上限：防止 runaway 合并吞掉整页。 */
    static final int MAX_GROUP_SIZE = 4;
    private static final Pattern CONTINUATION = Pattern.compile("[（(]续[)）]");

    private FigureGrouper() {
    }

    /**
     * @return 按阅读顺序排列的逻辑图组：合并块同组；未合并的 figure 为单元素组；非 figure 块不入组。
     */
    public static List<FigureGroup> group(List<MineruBlockParser.MineruBlock> blocks) {
        List<FigureGroup> groups = new java.util.ArrayList<>();
        if (blocks == null || blocks.isEmpty()) return groups;
        FigureGroup current = null;
        int groupSeq = 0;
        for (MineruBlockParser.MineruBlock block : blocks) {
            if (block == null || !AgentKnowledgeBlock.FIGURE.equals(block.type())) {
                current = null;
                continue;
            }
            if (current != null && (current.blocks().size() >= MAX_GROUP_SIZE || !mergeable(current.last(), block))) {
                current = null;
            }
            if (current == null) {
                current = new FigureGroup("fg-" + (++groupSeq), new java.util.ArrayList<>());
                groups.add(current);
            }
            current.blocks().add(block);
        }
        return groups;
    }

    /** 相邻判定：几何紧邻（一轴重叠 ≥50% 且另一轴间隔 <15pt）或图注信号（相同/(续)/一方缺失）。跨页仅「(续)」可合并。 */
    private static boolean mergeable(MineruBlockParser.MineruBlock left, MineruBlockParser.MineruBlock right) {
        boolean samePage = samePage(left, right);
        boolean continuation = hasContinuationSignal(left.caption(), right.caption());
        if (!samePage && !continuation) return false;
        if (samePage) {
            double[] a = parseBbox(left.bbox());
            double[] b = parseBbox(right.bbox());
            if (a != null && b != null) {
                double overlapX = Math.min(a[2], b[2]) - Math.max(a[0], b[0]);
                double overlapY = Math.min(a[3], b[3]) - Math.max(a[1], b[1]);
                double gapX = Math.max(0, Math.max(a[0], b[0]) - Math.min(a[2], b[2]));
                double gapY = Math.max(0, Math.max(a[1], b[1]) - Math.min(a[3], b[3]));
                double heightA = a[3] - a[1], heightB = b[3] - b[1];
                double widthA = a[2] - a[0], widthB = b[2] - b[0];
                boolean verticallyStacked = gapX <= 0.5 && overlapY >= 0
                        && overlapX >= MIN_OVERLAP_RATIO * Math.max(1, Math.min(widthA, widthB))
                        && gapY < MAX_GAP;
                boolean horizontallyStacked = gapY <= 0.5 && overlapX >= 0
                        && overlapY >= MIN_OVERLAP_RATIO * Math.max(1, Math.min(heightA, heightB))
                        && gapX < MAX_GAP;
                if (verticallyStacked || horizontallyStacked) return true;
            }
        }
        return captionSignal(left.caption(), right.caption());
    }

    private static boolean hasContinuationSignal(String left, String right) {
        return CONTINUATION.matcher(nullToEmpty(left)).find() || CONTINUATION.matcher(nullToEmpty(right)).find();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 图注信号：完全相同、一方为空且几何至少同页，或命中「(续)」模式。 */
    private static boolean captionSignal(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isEmpty() || b.isEmpty()) return true;
        if (a.equals(b)) return true;
        return CONTINUATION.matcher(a).find() || CONTINUATION.matcher(b).find();
    }

    private static boolean samePage(MineruBlockParser.MineruBlock left, MineruBlockParser.MineruBlock right) {
        return left.pageNumber() != null && left.pageNumber().equals(right.pageNumber());
    }

    /** bbox 形如 "[x0.0,y0.0,x1.0,y1.0]"（Jackson ArrayNode.toString）；解析失败返回 null（仅图注信号兜底）。 */
    private static double[] parseBbox(String bbox) {
        if (bbox == null || bbox.isBlank()) return null;
        String inner = bbox.replace("[", "").replace("]", "").trim();
        String[] parts = inner.split("[,\\s]+");
        if (parts.length != 4) return null;
        try {
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]), Double.parseDouble(parts[3])};
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String normalize(String caption) {
        return caption == null ? "" : CONTINUATION.matcher(caption.toLowerCase(Locale.ROOT)).replaceAll("").trim();
    }

    /** 逻辑图组：id 取首块 blockId 便于描述映射；last() 供合并判定。 */
    public record FigureGroup(String id, List<MineruBlockParser.MineruBlock> blocks) {

        public MineruBlockParser.MineruBlock first() {
            return blocks.getFirst();
        }

        public MineruBlockParser.MineruBlock last() {
            return blocks.getLast();
        }

        public String firstCaption() {
            return blocks.stream().map(MineruBlockParser.MineruBlock::caption)
                    .filter(value -> value != null && !value.isBlank()).findFirst().orElse("");
        }
    }
}
