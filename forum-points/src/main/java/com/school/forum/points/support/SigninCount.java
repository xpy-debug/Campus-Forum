package com.school.forum.points.support;

import lombok.Data;

/**
 * 某用户在某月的签到天数：{@code (userId, days)}。
 *
 * <p>只是月度全勤查询的投影结果，不是一张表的映射，因此放在 {@code support} 而非
 * {@code entity}——放进 {@code entity} 会让人以为它对应某张表。
 *
 * <p><b>为什么是普通类而不是 record：</b>MyBatis 把结果集的列映射到对象时走的是
 * JavaBean 的 setter 约定（无参构造 + setter）。record 没有无参构造，
 * 只能用全参构造映射，而全参构造要求列顺序与参数顺序严格一致——
 * 改一下 SQL 里 SELECT 的列顺序，映射就静默错位。这与
 * {@code TargetDelta} 用普通类的原因是同一类：<b>出现在 Mapper 边界的类型必须遵循 JavaBean 约定。</b>
 */
@Data
public class SigninCount {

    private Long userId;

    /** 当月签到天数 */
    private Integer days;
}
