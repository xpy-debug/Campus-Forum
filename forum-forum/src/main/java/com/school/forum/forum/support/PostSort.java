package com.school.forum.forum.support;

/**
 * 帖子列表的排序方式。
 *
 * <p>当前只落地 {@code latest} 与 {@code hot}。{@code reply}（最新回复）与
 * {@code essence}（精华）在设计文档中有索引支撑，但需要在评论写入时同步维护
 * {@code last_comment_time} 的排序缓存，留待后续迭代——<b>先不写进枚举</b>，
 * 免得前端以为它可用。
 *
 * <p>未知取值一律退回 {@link #LATEST}，而不是报错：排序参数来自 URL，
 * 用户在地址栏里改坏一个字符不该得到一个错误页，退回默认排序是更合理的降级。
 */
public enum PostSort {

    /** 按发布时间倒序 */
    LATEST("latest"),

    /** 按热度分倒序 */
    HOT("hot");

    private final String value;

    PostSort(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static PostSort of(String sort) {
        if (sort != null) {
            for (PostSort candidate : values()) {
                if (candidate.value.equalsIgnoreCase(sort.trim())) {
                    return candidate;
                }
            }
        }
        return LATEST;
    }
}
