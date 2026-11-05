package com.zwx.zwxagent.creative.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 图片提示词优化的风格目录（摄影 + 插画），前后端共用一份口径。
 * 新增风格只改这里：前端通过 GET /ai/creative/prompts/image-styles 读取分组与提示语，不再硬编码选项。
 *
 * <p>rubric 是注入系统提示词的「风格锚点」（光线/镜头/色彩/质感/避免项），只写风格特征，
 * 不写具体主体，保证风格与用户描述的内容解耦。</p>
 */
public final class ImageStyleCatalog {

    public static final String GROUP_PHOTO = "photo";
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

    /** 网络描述：code/label/group/hint/needsReference 会返回给前端，rubric 只在服务端使用。 */
    public record Style(String code, String label, String group, String hint, boolean needsReference, String rubric) {
        public View view() {
            return new View(code, label, group, hint, needsReference);
        }
    }

    public record View(String code, String label, String group, String hint, boolean needsReference) {
    }

    private static final List<Style> STYLES = List.of(
            new Style(PHOTO_MATCH, "跟随原片", GROUP_PHOTO,
                    "读取参考照片的镜头、光线与调色，只改写画面内容，输出像同一套风格拍的续作", true,
                    ""),
            new Style(REALISTIC, "自然写实", GROUP_PHOTO,
                    "通用真实照片质感：自然光、真实皮肤与材质，不像插画", false,
                    "通用写实：自然光或现场混合光，等效 35–85mm，平视或微俯拍，肤色通透有质感，轻微颗粒，整体像一次真实拍摄。"),
            new Style(FILM_PORTRAIT, "胶片人像", GROUP_PHOTO,
                    "Portra 400 取向：柔和肤色过渡、低饱和暖调、细腻胶片颗粒", false,
                    "胶片人像：Kodak Portra 400 取向——宽容度高、柔和肤色过渡、高光轻微溢出、暗部保留细节；"
                            + "85mm f/1.8 浅景深，窗光或侧逆光；低饱和暖调，明显但细腻的胶片颗粒，不锐化过度。"),
            new Style(BLACK_WHITE, "黑白摄影", GROUP_PHOTO,
                    "银盐黑白：深黑有层次、侧硬光塑造结构、颗粒与暗角", false,
                    "黑白摄影：银盐质感，深黑有层次、高光不糊、中间调丰富；85mm 或 35mm，侧硬光塑造面部与形体结构；"
                            + "适度颗粒与轻微暗角，全靠明暗与形状表达，不出现彩噪。"),
            new Style(DOCUMENTARY, "纪实街拍", GROUP_PHOTO,
                    "28–35mm 抓拍、现场混合光、不摆拍，保留环境信息", false,
                    "纪实街拍：28–35mm 贴近抓拍，人物不摆拍、不看镜头；现场自然光与混合色温，高宽容度；"
                            + "轻微动态模糊与颗粒，构图保留环境信息与偶然性，避免广告片式的干净打光。"),
            new Style(CINEMATIC, "电影感", GROUP_PHOTO,
                    "宽银幕构图、低调光与实用光源、青橙或冷调分级", false,
                    "电影感：2.39:1 宽银幕构图，变形宽银幕眩光与椭圆散景；低调光与实用光源（窗、灯、霓虹），浅景深；"
                            + "青橙或冷调分级，高光柔和、暗部压深但留细节；画面像电影剧照，不出现字幕与片名。"),
            new Style(LANDSCAPE, "风光", GROUP_PHOTO,
                    "广角前后景层次、黄金时刻、高动态范围与细节", false,
                    "风光摄影：16–24mm 广角，前中远景层次分明，用引导线组织视线；黄金时刻或蓝调时刻的光线与色温；"
                            + "必要时长曝光柔化水面与云层；高动态范围、细节锐利，避免 HDR 过度与塑料感草地。"),
            new Style(PRODUCT, "产品棚拍", GROUP_PHOTO,
                    "无缝背景、柔光箱＋轮廓光、材质纹理与色准清晰", false,
                    "产品棚拍：无缝背景或纯净台面，柔光箱主光＋轮廓光勾边，控制反光与高光形状；"
                            + "90–100mm 微距压缩变形，f/8–f/11 主体全清晰；金属、玻璃、织物的材质纹理明确，白平衡与色准中性。"),
            new Style(RETRO_CCD, "复古 CCD", GROUP_PHOTO,
                    "千禧数码：直闪、偏色、噪点与低保真随手拍感", false,
                    "千禧年 CCD 数码感：直闪或硬质机顶闪光，肤色轻微偏红黄、白平衡略失准；对比偏平、阴影发灰、噪点明显；"
                            + "构图随性像随手拍，边缘略带暗角与色散，避免现代 HDR 与过度锐化。"),
            new Style(JAPANESE_FRESH, "日系清新", GROUP_PHOTO,
                    "高亮低对比、青绿偏色、逆光空气感与干净留白", false,
                    "日系清新：高亮度、低对比、略过曝的柔和影调，青绿色偏色与空气感；50mm 平视，逆光或窗边柔光，浅景深；"
                            + "肤色白皙通透，画面干净留白，避免浓重阴影与高饱和。"),
            new Style(MOODY_NIGHT, "夜色氛围", GROUP_PHOTO,
                    "蓝调时刻与霓虹混合光、湿地面反光、冷暖对撞", false,
                    "夜色氛围：蓝调时刻或夜晚霓虹混合光，大面积暗部与局部高光，湿地面、玻璃与水面反光形成色彩层次；"
                            + "35mm 手持感、浅景深，冷暖对撞但整体克制，暗部保留细节不糊成死黑。"),
            new Style(MACRO, "微距质感", GROUP_PHOTO,
                    "100mm 微距 1:1、极浅景深、纹理与露珠清晰", false,
                    "微距质感：100mm 微距 1:1 放大，极浅景深只保留一条清晰焦点带；环形光或侧逆光突出纹理、绒毛、露珠与纤维；"
                            + "背景化为柔滑散景，色彩自然不过饱和。"),
            new Style(COMIC, "漫画插画", GROUP_ILLUSTRATION,
                    "定子风格后用墨线描边＋赛璐璐平涂，网点与速度线", false,
                    "")
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

    public static boolean needsReference(String code) {
        Style style = BY_CODE.get(code);
        return style != null && style.needsReference();
    }

    /** 归一化：去空白、转大写；缺省为自然写实。 */
    public static String normalize(String code) {
        String value = code == null || code.isBlank() ? REALISTIC : code.trim().toUpperCase(Locale.ROOT);
        require(value);
        return value;
    }
}
