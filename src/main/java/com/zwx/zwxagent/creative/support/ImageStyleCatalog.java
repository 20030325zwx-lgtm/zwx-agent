package com.zwx.zwxagent.creative.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 图片提示词优化的风格目录（2026-11-05 扩充）：按「媒介」分组、按「题材/倾向」标注，
 * 前后端共用一份口径。新增风格只改这里：前端通过 GET /ai/creative/prompts/image-styles
 * 读取分组、标签与一句话描述，不再硬编码选项。
 *
 * <p>rubric 是注入系统提示词的「风格锚点」（媒介/造型/光线/色彩/镜头/质感/避免项），
 * 只写视觉语言，不写具体主体，保证风格与用户描述的内容解耦。真实拍摄取向走摄影骨架，
 * 3D 与 2D 各有自己的骨架，见 PromptOptimizationService。</p>
 */
public final class ImageStyleCatalog {

    /** 媒介分组：前端左侧分类与骨架选择都以此为准。 */
    public static final String GROUP_PHOTO = "photo";
    public static final String GROUP_CG = "cg";
    public static final String GROUP_ILLUSTRATION = "illustration";

    /** 跟随参考片：需要视觉模型读取参考图，沿用其镜头/光线/调色，只改写画面内容。 */
    public static final String PHOTO_MATCH = "PHOTO_MATCH";
    /** 自然写实：通用「像照片」取向，历史默认值。 */
    public static final String REALISTIC = "REALISTIC";
    public static final String FILM_PORTRAIT = "FILM_PORTRAIT";
    public static final String BLACK_WHITE = "BLACK_WHITE";
    public static final String DOCUMENTARY = "DOCUMENTARY";
    public static final String CINEMATIC = "CINEMATIC";
    public static final String LANDSCAPE = "LANDSCAPE";
    public static final String PRODUCT = "PRODUCT";
    public static final String RETRO_CCD = "RETRO_CCD";
    public static final String JAPANESE_FRESH = "JAPANESE_FRESH";
    public static final String MOODY_NIGHT = "MOODY_NIGHT";
    public static final String MACRO = "MACRO";
    public static final String COMIC = "COMIC";
    // 2026-11-05 扩充：题材×媒介预设
    public static final String FASHION_EDITORIAL = "FASHION_EDITORIAL";
    public static final String URBAN_ROMANCE = "URBAN_ROMANCE";
    public static final String NOIR_THRILLER = "NOIR_THRILLER";
    public static final String WUXIA_REAL = "WUXIA_REAL";
    public static final String SCI_FI_REAL = "SCI_FI_REAL";
    public static final String FOOD_PHOTO = "FOOD_PHOTO";
    public static final String PIXAR_3D = "PIXAR_3D";
    public static final String STYLIZED_3D = "STYLIZED_3D";
    public static final String CLAY_STOPMOTION = "CLAY_STOPMOTION";
    public static final String TOY_FIGURE = "TOY_FIGURE";
    public static final String ANIME_2D = "ANIME_2D";
    public static final String GUOMAN_2D = "GUOMAN_2D";
    public static final String WATERCOLOR = "WATERCOLOR";
    public static final String OIL_PAINTING = "OIL_PAINTING";
    public static final String INK_WASH = "INK_WASH";
    public static final String PIXEL_ART = "PIXEL_ART";
    public static final String LINE_ART = "LINE_ART";

    /**
     * 网络描述：code/label/group/category/hint/tags/accent/needsReference 会返回给前端，
     * rubric 只在服务端使用。accent 是卡片色带的两端颜色，用于在面板里区分风格取向。
     */
    public record Style(String code, String label, String group, String category, String hint,
                        List<String> tags, List<String> accent, boolean needsReference, String rubric) {
        public View view() {
            return new View(code, label, group, category, hint, tags, accent, needsReference, coverPath(code));
        }
    }

    public record View(String code, String label, String group, String category, String hint,
                       List<String> tags, List<String> accent, boolean needsReference, String cover) {
    }

    /** 预览图路径：与前端 public/style-previews 下的静态资源一一对应（由脚本统一生成）。 */
    static String coverPath(String code) {
        return "/style-previews/" + code + ".jpg";
    }

    private static final List<Style> STYLES = List.of(
            // ---------------------------------------------------------------- 真人实拍
            new Style(PHOTO_MATCH, "跟随原片", GROUP_PHOTO, "参考片",
                    "读取参考照片的镜头、光线与调色，只改写画面内容，输出像同一套风格拍的续作",
                    List.of("参考图", "风格延续"), List.of("#c8a27a", "#6b5540"), true, ""),
            new Style(REALISTIC, "自然写实", GROUP_PHOTO, "通用",
                    "通用真实照片质感：自然光、真实皮肤与材质，不像插画",
                    List.of("写实", "自然光", "通用"), List.of("#b9c3cc", "#6d7b88"), false, ""),
            new Style(FILM_PORTRAIT, "胶片人像", GROUP_PHOTO, "人像",
                    "Portra 400 取向：柔和肤色过渡、低饱和暖调、细腻胶片颗粒",
                    List.of("人像", "胶片", "暖调"), List.of("#d8b48c", "#8a6746"), false,
                    "胶片人像：Kodak Portra 400 取向——宽容度高、柔和肤色过渡、高光轻微溢出、暗部保留细节；"
                            + "85mm f/1.8 浅景深，窗光或侧逆光；低饱和暖调，明显但细腻的胶片颗粒，不锐化过度。"),
            new Style(FASHION_EDITORIAL, "时尚大片", GROUP_PHOTO, "人像",
                    "硬光直打＋高反差，编辑级构图与留白，面料与妆面细节锐利",
                    List.of("时尚", "硬光", "高反差"), List.of("#d9d2c5", "#2f2b28"), false,
                    "时尚编辑大片：硬光或裸灯直打，制造清晰投影与高反差；色彩高级（低饱和＋一个强调色）；"
                            + "35mm 或 85mm 取中景/全身，构图刻意留白；肤质真实但精修，发丝与面料纹理锐利；"
                            + "避免甜美滤镜与塑料磨皮。"),
            new Style(URBAN_ROMANCE, "都市情感", GROUP_PHOTO, "都市",
                    "窗光与街灯暖调、浅景深城市光斑，隔着玻璃或门框取景",
                    List.of("都市", "暖调", "浅景深"), List.of("#e0b48e", "#5c6b84"), false,
                    "当代都市情感实拍：窗光或街灯暖光，浅景深把城市虚化成光斑；低饱和暖调或奶油色分级，"
                            + "肤色通透；35–85mm 中近景，隔着玻璃/门框取景增加层次；轻微颗粒与柔和反差，"
                            + "避免广告式的过度精修。"),
            new Style(DOCUMENTARY, "纪实街拍", GROUP_PHOTO, "纪实",
                    "28–35mm 抓拍、现场混合光、不摆拍，保留环境信息",
                    List.of("纪实", "抓拍", "现场光"), List.of("#9aa39a", "#4c5148"), false,
                    "纪实街拍：28–35mm 贴近抓拍，人物不摆拍、不看镜头；现场自然光与混合色温，高宽容度；"
                            + "轻微动态模糊与颗粒，构图保留环境信息与偶然性，避免广告片式的干净打光。"),
            new Style(CINEMATIC, "电影感", GROUP_PHOTO, "电影",
                    "宽银幕构图、低调光与实用光源、青橙或冷调分级",
                    List.of("电影", "宽银幕", "低调光"), List.of("#2c3a4a", "#c98a4b"), false,
                    "电影感：2.39:1 宽银幕构图，变形宽银幕眩光与椭圆散景；低调光与实用光源（窗、灯、霓虹），"
                            + "浅景深；青橙或冷调分级，高光柔和、暗部压深但留细节；画面像电影剧照，"
                            + "不出现字幕与片名。"),
            new Style(NOIR_THRILLER, "悬疑犯罪", GROUP_PHOTO, "悬疑",
                    "低调光＋单侧硬光切轮廓，冷青绿分级，雨雾与湿地面反光",
                    List.of("悬疑", "冷青绿", "低调光"), List.of("#3f5a57", "#12181a"), false,
                    "悬疑犯罪质感：低调光为主，单侧硬光或顶部光切出面部轮廓，大面积暗部带细节；"
                            + "冷青绿或去饱和分级，霓虹与湿地面做局部色点；35mm 手持、轻微倾斜构图，浅景深；"
                            + "雨雾、玻璃反光、颗粒与轻微色差，避免明亮均匀的商业光。"),
            new Style(WUXIA_REAL, "武侠古装", GROUP_PHOTO, "古风",
                    "侧逆光穿过烟尘形成体积光，低饱和青灰或土金，衣袂与发丝带风",
                    List.of("古风", "武侠", "体积光"), List.of("#7c8a83", "#3b3a33"), false,
                    "武侠古装实拍：侧逆光与顶部光束穿过烟尘/雾气形成体积光，暗部压深；低饱和青灰或土金色调；"
                            + "35–50mm 中远景，衣袂与发丝被风扬起，慢门保留动势；材质写实（麻布、皮革、金属、木纹），"
                            + "避免影楼打光与塑料质感。"),
            new Style(SCI_FI_REAL, "科幻写实", GROUP_PHOTO, "科幻",
                    "冷白青蓝主光＋琥珀补光的冷暖对撞，金属与雾面材质的反射层次",
                    List.of("科幻", "冷暖对比", "体积光"), List.of("#3d6d8f", "#e08a3c"), false,
                    "科幻写实：冷白与青蓝为主光，局部霓虹或琥珀色补光形成冷暖对比；金属拉丝、哑光复合材、"
                            + "玻璃与雾面塑料的反射层次分明；体积光、镜头眩光与轻微色差；24–35mm 广角强化空间纵深，"
                            + "构图对称或有强引导线。"),
            new Style(LANDSCAPE, "风光", GROUP_PHOTO, "风光",
                    "广角前后景层次、黄金时刻、高动态范围与细节",
                    List.of("风光", "广角", "黄金时刻"), List.of("#8fb3c9", "#2f4a3c"), false,
                    "风光摄影：16–24mm 广角，前中远景层次分明，用引导线组织视线；黄金时刻或蓝调时刻的光线与色温；"
                            + "必要时长曝光柔化水面与云层；高动态范围、细节锐利，避免 HDR 过度与塑料感草地。"),
            new Style(PRODUCT, "产品棚拍", GROUP_PHOTO, "商业",
                    "无缝背景、柔光箱＋轮廓光、材质纹理与色准清晰",
                    List.of("商业", "棚拍", "色准"), List.of("#e6e2da", "#8d9298"), false,
                    "产品棚拍：无缝背景或纯净台面，柔光箱主光＋轮廓光勾边，控制反光与高光形状；"
                            + "90–100mm 微距压缩变形，f/8–f/11 主体全清晰；金属、玻璃、织物的材质纹理明确，"
                            + "白平衡与色准中性。"),
            new Style(FOOD_PHOTO, "美食摄影", GROUP_PHOTO, "商业",
                    "45° 或正俯拍、侧逆光勾出食物质感与蒸汽，暖调有食欲",
                    List.of("美食", "俯拍", "暖调"), List.of("#d9a15f", "#6b3f2a"), false,
                    "美食摄影：45° 或正俯拍，侧逆光或窗边柔光勾出食物质感与蒸汽；浅景深只保留主体清晰，"
                            + "背景餐具与台面虚化；色彩温暖有食欲但不失真，酱汁高光与油脂反光自然；"
                            + "避免过度饱和与塑料感摆拍。"),
            new Style(RETRO_CCD, "复古 CCD", GROUP_PHOTO, "风格化",
                    "千禧数码：直闪、偏色、噪点与低保真随手拍感",
                    List.of("复古", "直闪", "低保真"), List.of("#c9a08f", "#5f4a4a"), false,
                    "千禧年 CCD 数码感：直闪或硬质机顶闪光，肤色轻微偏红黄、白平衡略失准；对比偏平、"
                            + "阴影发灰、噪点明显；构图随性像随手拍，边缘略带暗角与色散，避免现代 HDR 与过度锐化。"),
            new Style(JAPANESE_FRESH, "日系清新", GROUP_PHOTO, "风格化",
                    "高亮低对比、青绿偏色、逆光空气感与干净留白",
                    List.of("日系", "高亮", "青绿"), List.of("#cfe0d6", "#8fb3a8"), false,
                    "日系清新：高亮度、低对比、略过曝的柔和影调，青绿色偏色与空气感；50mm 平视，逆光或窗边柔光，"
                            + "浅景深；肤色白皙通透，画面干净留白，避免浓重阴影与高饱和。"),
            new Style(MOODY_NIGHT, "夜色氛围", GROUP_PHOTO, "风格化",
                    "蓝调时刻与霓虹混合光、湿地面反光、冷暖对撞",
                    List.of("夜景", "霓虹", "氛围"), List.of("#2b3f63", "#d0567a"), false,
                    "夜色氛围：蓝调时刻或夜晚霓虹混合光，大面积暗部与局部高光，湿地面、玻璃与水面反光"
                            + "形成色彩层次；35mm 手持感、浅景深，冷暖对撞但整体克制，暗部保留细节不糊成死黑。"),
            new Style(MACRO, "微距质感", GROUP_PHOTO, "风格化",
                    "100mm 微距 1:1、极浅景深、纹理与露珠清晰",
                    List.of("微距", "纹理", "浅景深"), List.of("#a8c0a0", "#3f4d38"), false,
                    "微距质感：100mm 微距 1:1 放大，极浅景深只保留一条清晰焦点带；环形光或侧逆光突出纹理、"
                            + "绒毛、露珠与纤维；背景化为柔滑散景，色彩自然不过饱和。"),
            new Style(BLACK_WHITE, "黑白摄影", GROUP_PHOTO, "风格化",
                    "银盐黑白：深黑有层次、侧硬光塑造结构、颗粒与暗角",
                    List.of("黑白", "银盐", "硬光"), List.of("#cfcfcf", "#1c1c1c"), false,
                    "黑白摄影：银盐质感，深黑有层次、高光不糊、中间调丰富；85mm 或 35mm，侧硬光塑造面部与"
                            + "形体结构；适度颗粒与轻微暗角，全靠明暗与形状表达，不出现彩噪。"),

            // ---------------------------------------------------------------- 3D 渲染
            new Style(PIXAR_3D, "3D 卡通", GROUP_CG, "卡通",
                    "圆润造型与大眼比例、次表面散射皮肤，明亮通透的三点光",
                    List.of("3D", "卡通", "明亮"), List.of("#f2b64c", "#4c8fd1"), false,
                    "3D 卡通（Pixar/Disney 取向）：圆润夸张的造型比例与大眼睛，光滑但有次表面散射的皮肤，"
                            + "布料与毛发蓬松可信；三点柔光加环境光，明亮通透的配色，浅景深与轻微辉光；"
                            + "画面干净、体积感强，避免写实照片质感与脏旧纹理。"),
            new Style(STYLIZED_3D, "3D 动漫", GROUP_CG, "动漫",
                    "国漫电影级：高精度毛发布料、体积光与大气纵深",
                    List.of("3D", "国漫", "电影级"), List.of("#6d5bd0", "#1f2a44"), false,
                    "3D 动漫（国漫电影级）：高精度建模与毛发/布料解算，材质带次表面散射与清晰高光；"
                            + "体积光、丁达尔与景深虚化，冷蓝紫或暖金的分级；构图有镜头感与大气纵深；"
                            + "避免低模感、塑料感与卡通化比例。"),
            new Style(CLAY_STOPMOTION, "黏土定格", GROUP_CG, "手工",
                    "手工黏土的指纹与不对称、微缩布景、棚拍硬光投影",
                    List.of("黏土", "定格", "手工感"), List.of("#e0a882", "#8a6b4f"), false,
                    "黏土定格动画：手工黏土的指纹、刮痕与轻微不对称，羊毛毡与纸板道具；微缩布景，"
                            + "棚拍硬光制造明确投影；色彩质朴温暖，边缘有手工感；画面像逐帧拍摄的定格，"
                            + "避免数字光滑渲染。"),
            new Style(TOY_FIGURE, "手办模型", GROUP_CG, "产品",
                    "PVC/树脂半哑光涂装、接缝细节、棚拍微距浅景深",
                    List.of("手办", "微距", "棚拍"), List.of("#9fb4c9", "#3c4652"), false,
                    "手办模型质感：PVC/树脂表面细腻半哑光，接缝与涂装细节清晰，底座与透明支架合理；"
                            + "棚拍柔光箱加反光板，纯色或渐变背景，浅景深微距感；轻微反射与高光，"
                            + "避免真实人体皮肤的毛孔质感。"),

            // ---------------------------------------------------------------- 插画与绘画
            new Style(COMIC, "漫画插画", GROUP_ILLUSTRATION, "动漫",
                    "定子风格后用墨线描边＋赛璐璐平涂，网点与速度线",
                    List.of("漫画", "赛璐璐", "墨线"), List.of("#f0d9a8", "#2b2b2b"), false, ""),
            new Style(ANIME_2D, "日系动画", GROUP_ILLUSTRATION, "动漫",
                    "干净勾线＋硬边块面阴影，明亮通透配色与逆光光晕",
                    List.of("日漫", "赛璐璐", "逆光"), List.of("#9ec6e8", "#f3c8d2"), false,
                    "日系动画赛璐璐：干净勾线与均匀线宽，块面阴影硬边缘，双色高光；背景可带手绘水彩感与光斑，"
                            + "天空与云层丰富；色彩明亮通透，逆光与镜头光晕；避免写实皮肤与 3D 渲染感。"),
            new Style(GUOMAN_2D, "国漫古风", GROUP_ILLUSTRATION, "古风",
                    "工笔白描或水墨晕染结合岩彩，青绿赭石朱砂的克制配色",
                    List.of("国风", "岩彩", "工笔"), List.of("#7f9e86", "#a8563f"), false,
                    "国风 2D 插画：工笔白描的细线或水墨晕染结合岩彩矿物色，留白与远景层次；"
                            + "青绿、赭石、朱砂的克制配色，金箔点缀；构图借鉴卷轴与屏风，云气与衣袂流动；"
                            + "避免日漫大眼与赛璐璐网点。"),
            new Style(WATERCOLOR, "水彩", GROUP_ILLUSTRATION, "手绘",
                    "湿画法晕染与纸面沉淀、可见纸纹与轻淡线稿",
                    List.of("水彩", "晕染", "纸纹"), List.of("#cfe0e8", "#a9c4b4"), false,
                    "水彩：湿画法晕染与颜料在纸上的自然沉淀，边缘有水的扩散痕；留白纸纹可见，颜色透明叠加；"
                            + "线稿轻淡不抢主体，画面松弛有呼吸感；避免矢量式纯色块与锐利渐变。"),
            new Style(OIL_PAINTING, "油画", GROUP_ILLUSTRATION, "手绘",
                    "厚涂笔触与颜料堆叠、古典明暗或印象派冷暖并置",
                    List.of("油画", "厚涂", "笔触"), List.of("#8a6a3f", "#2e3b4e"), false,
                    "油画：厚涂笔触与 impasto 的立体颜料堆叠，可见方向性笔触；古典明暗对比或印象派的冷暖并置，"
                            + "色彩层次丰富；画布纹理隐约可见；避免数码平涂与过度锐化。"),
            new Style(INK_WASH, "水墨", GROUP_ILLUSTRATION, "古风",
                    "墨分五色、干笔飞白与湿笔渗化，大面积留白虚实相生",
                    List.of("水墨", "留白", "宣纸"), List.of("#d7d3c8", "#26241f"), false,
                    "水墨：宣纸与墨色晕散，墨分五色（焦/浓/重/淡/清），干笔飞白与湿笔渗化；"
                            + "大面积留白与虚实相生，少量朱红或石青点染；构图疏密有致；避免均匀灰度与硬边色块。"),
            new Style(PIXEL_ART, "像素艺术", GROUP_ILLUSTRATION, "图形",
                    "限定色板与 1px 像素边缘、抖动过渡、复古 16-bit 质感",
                    List.of("像素", "复古", "游戏"), List.of("#5b8dd9", "#20243a"), false,
                    "像素艺术：限定色板与 1px 精度的像素边缘，抖动（dithering）过渡；等距或正侧视角，"
                            + "清晰的轮廓与高光/阴影分层；复古 16-bit 游戏质感，背景用平涂与重复图案；"
                            + "避免抗锯齿模糊与照片纹理。"),
            new Style(LINE_ART, "线稿素描", GROUP_ILLUSTRATION, "手绘",
                    "铅笔或针管笔线条、排线塑体积、纸张纹理与留白",
                    List.of("线稿", "素描", "单色"), List.of("#dedbd4", "#3a3833"), false,
                    "线稿/素描：单色铅笔或针管笔线条，线条有轻重与疏密变化；排线塑造体积与阴影，局部留白；"
                            + "纸张纹理与轻微污迹感；画面以结构为主，避免大面积色块与写实渲染。")
    );

    private static final Map<String, Style> BY_CODE;

    static {
        Map<String, Style> byCode = new LinkedHashMap<>();
        for (Style style : STYLES) byCode.put(style.code(), style);
        BY_CODE = Map.copyOf(byCode);
    }

    private ImageStyleCatalog() {
    }

    public static List<View> views() {
        return STYLES.stream().map(Style::view).toList();
    }

    /** 含 rubric 的完整目录：仅供服务端与单测使用，不通过接口暴露。 */
    public static List<Style> styles() {
        return STYLES;
    }

    public static Style require(String code) {
        Style style = code == null ? null : BY_CODE.get(code);
        if (style == null) throw new IllegalArgumentException("不支持的图片风格：" + code);
        return style;
    }

    /** 查询风格；未注册或为空返回 null（视频等场景允许「不指定风格」）。 */
    public static Style optional(String code) {
        if (code == null || code.isBlank()) return null;
        return BY_CODE.get(code.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * 视频提示词用的紧凑风格注记（2026-11-05）：一句话交代画风，供提示词优化与提交前拼接共用。
     * 锚点较长时由调用方按提示词剩余预算降级或截断，不挤占用户原文。
     */
    public static String videoNote(String code) {
        Style style = optional(code);
        if (style == null) return "";
        if (PHOTO_MATCH.equals(style.code())) {
            return "沿用首帧画面的镜头、光线与调色，保持同一套风格。";
        }
        // 锚点本身已以风格名开头（如「电影感：2.39:1 …」），不再重复前缀
        if (!style.rubric().isBlank()) return style.rubric();
        return style.label() + "：" + style.hint();
    }

    public static boolean needsReference(String code) {
        Style style = BY_CODE.get(code);
        return style != null && style.needsReference();
    }

    /**
     * 把画风注记拼进视频提示词：用户正文优先，注记按剩余预算降级；未指定/未知风格原样返回。
     * 视频（画布节点与分镜镜头）共用，保证「界面选了画风、生成链路不漏传」。
     */
    public static String applyToPrompt(String prompt, String styleCode) {
        if (prompt == null) return "";
        String note = videoNote(styleCode);
        if (note.isBlank()) return prompt;
        // 跟随原片需要有效首帧，无首帧时不注入空指令（调用方先剔除）
        int budget = 500 - prompt.length() - 1;
        if (budget <= 0) return prompt;
        if (note.length() > budget) {
            Style style = optional(styleCode);
            String shortNote = style == null ? "" : style.label() + "画风。";
            note = shortNote.length() <= budget ? shortNote : "";
        }
        return note.isBlank() ? prompt : prompt + " " + note;
    }

    /** 归一化：去空白、转大写；缺省为自然写实。 */
    public static String normalize(String code) {
        String value = code == null || code.isBlank() ? REALISTIC : code.trim().toUpperCase(Locale.ROOT);
        require(value);
        return value;
    }
}
