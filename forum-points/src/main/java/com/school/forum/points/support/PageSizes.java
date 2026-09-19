package com.school.forum.points.support;

/**
 * 分页参数的收敛。
 *
 * <p>{@code size} 来自请求参数，是**用户可以控制的值**，因此必须被约束：
 * 不约束的话，{@code size = Integer.MAX_VALUE} 会让 {@code size + 1}
 * 溢出成负数，生成一条 {@code LIMIT -2147483648} 的 SQL；
 * 而 {@code size = 100000} 则是一次真实的慢查询——「多查几条」的代价
 * 由服务端承担，攻击者只花一个数字。
 *
 * <p>收敛而不是报错：用户传 {@code size=1000} 多半只是想要「尽可能多」，
 * 给他 100 条比给他一个参数错误更有用。
 */
public final class PageSizes {

    /** 未指定时的每页条数 */
    public static final int DEFAULT = 20;

    /** 单页上限。100 条已足够任何一屏列表，再大就只是在放大单次请求的成本 */
    public static final int MAX = 100;

    private PageSizes() {
    }

    /** 收敛到 {@code [1, MAX]}，非正数按 {@link #DEFAULT} 处理 */
    public static int clamp(int size) {
        if (size <= 0) {
            return DEFAULT;
        }
        return Math.min(size, MAX);
    }
}
