package com.school.forum.forum.support;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 计数增量：{@code (目标ID, 增量)}。
 *
 * <p>用于「一次 SQL 更新多条记录」的场景。点赞落库时同一批可能有几千次点赞、
 * 分布在几百个帖子上，逐条 {@code UPDATE ... WHERE id = ?} 就是几百次往返。
 * 把增量先按目标聚合成这个结构，再用 {@code CASE WHEN} 一条 SQL 发出去。
 *
 * <p><b>用普通类而不是 record：</b>MyBatis 读取 {@code #{item.targetId}} 时走的是
 * JavaBean 的 getter 约定，而 record 的访问器是 {@code targetId()} 没有 {@code get} 前缀，
 * 会导致「找不到属性」的绑定错误。在 Mapper 参数里出现的类型必须遵循 JavaBean 约定。
 */
@Data
@AllArgsConstructor
public class TargetDelta {

    private final Long targetId;

    private final int delta;
}
