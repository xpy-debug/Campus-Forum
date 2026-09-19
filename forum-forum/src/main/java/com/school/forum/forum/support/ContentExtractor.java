package com.school.forum.forum.support;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Markdown 正文里抽取列表页需要的派生信息：摘要、封面图、字数。
 *
 * <p><b>都在写入时算一次，而不是查询时实时算。</b>列表页一页 20 条帖子，
 * 若每条都在渲染时解析一遍 Markdown，等于把 20 次正则扫描加到每次列表请求上；
 * 而摘要一旦生成就不再变化，算一次存下来是显然更划算的做法。
 *
 * <p>用正则而不是完整的 Markdown 解析器：这里只需要「去掉标记后取前 120 字」，
 * 引入一个解析器意味着多一个依赖、多一份配置，而换来的只是更精确的边界情况处理——
 * 摘要本身就不要求精确。真正的渲染交给前端。
 */
public final class ContentExtractor {

    /** 摘要长度，与 {@code t_post.summary} 的列宽（255）留有充分余量 */
    private static final int SUMMARY_LENGTH = 120;

    /** 代码块 ```lang ... ``` —— 摘要里出现大段代码毫无意义，整块去掉 */
    private static final Pattern CODE_BLOCK = Pattern.compile("```[\\s\\S]*?```");

    /** 图片 ![alt](url) */
    private static final Pattern IMAGE = Pattern.compile("!\\[[^]]*]\\s*\\(([^)\\s]+)[^)]*\\)");

    /** 链接 [text](url) —— 只保留 text */
    private static final Pattern LINK = Pattern.compile("\\[([^]]*)]\\s*\\([^)]*\\)");

    /** 行首标记：# 标题、> 引用、- / * / + 列表、1. 有序列表 */
    private static final Pattern LINE_PREFIX = Pattern.compile("(?m)^\\s*(#{1,6}\\s+|>\\s*|[-*+]\\s+|\\d+\\.\\s+)");

    /** 行内标记：**粗体**、*斜体*、`行内代码`、~~删除线~~ */
    private static final Pattern INLINE_MARK = Pattern.compile("(\\*{1,3}|`{1,3}|~~)(.*?)\\1");

    /** HTML 标签。正文允许内嵌 HTML，但摘要里不该出现 */
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    private static final Pattern BLANK = Pattern.compile("\\s+");

    private ContentExtractor() {
    }

    /**
     * 生成摘要：去标记 → 去图片 → 压缩空白 → 截断到 120 字。
     *
     * <p>截断处补省略号。没被截断时不补——一个 30 字的帖子摘要后面跟着「…」，
     * 会让人以为还有更多内容。
     */
    public static String summary(String markdown) {
        String plain = toPlainText(markdown);
        if (plain.length() <= SUMMARY_LENGTH) {
            return plain;
        }
        return plain.substring(0, SUMMARY_LENGTH) + "…";
    }

    /** 去 Markdown 标记后的纯文本，用于摘要与字数统计 */
    public static String toPlainText(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String text = CODE_BLOCK.matcher(markdown).replaceAll(" ");
        text = IMAGE.matcher(text).replaceAll(" ");
        // 链接保留可见文字：读者在摘要里看到的是「考研数学规划」而不是一个 URL
        text = LINK.matcher(text).replaceAll("$1");
        text = LINE_PREFIX.matcher(text).replaceAll("");
        text = INLINE_MARK.matcher(text).replaceAll("$2");
        text = HTML_TAG.matcher(text).replaceAll(" ");
        return BLANK.matcher(text).replaceAll(" ").trim();
    }

    /** 取正文中的第一个图片链接作为封面，没有则返回空串 */
    public static String coverImage(String markdown) {
        List<String> images = imageUrls(markdown);
        return images.isEmpty() ? "" : images.get(0);
    }

    /** 按出现顺序提取正文中的全部图片链接 */
    public static List<String> imageUrls(String markdown) {
        List<String> urls = new ArrayList<>();
        if (markdown == null) {
            return urls;
        }
        Matcher matcher = IMAGE.matcher(markdown);
        while (matcher.find()) {
            urls.add(matcher.group(1));
        }
        return urls;
    }

    /**
     * 字数。按「去掉所有空白后的字符数」统计。
     *
     * <p>对中英文混排，这是最接近用户直觉的近似：中文一字算一个，
     * 英文一个单词会被算成好几个字符。要精确统计需要按语种分别处理，
     * 而这个数字唯一的用途是提示「大约要读几分钟」，不值得那份复杂度。
     */
    public static int wordCount(String markdown) {
        return toPlainText(markdown).replace(" ", "").length();
    }
}
