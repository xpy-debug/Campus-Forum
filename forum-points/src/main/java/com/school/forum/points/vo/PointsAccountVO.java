package com.school.forum.points.vo;

/**
 * 积分账户概览。
 *
 * <p>三个字段是 {@code t_user_points} 一行的全部可读内容，没有做任何加工——
 * 它们之间的关系（{@code balance = totalEarned - totalSpent}）由账户表的
 * 两个条件更新维护，这里如实返回即可。
 *
 * <p>每个字段都值得展示：只看余额看不出「这个人活跃不活跃」，
 * 而累计获得正是运营侧关心的量；累计消耗则解释了「攒了那么多分怎么没了」。
 *
 * @param balance     可用余额
 * @param totalEarned 累计获得（签到 + 全勤奖励 + 兑换退回 + 管理员调整）
 * @param totalSpent  累计消耗。只增不减，退款不算作冲减
 */
public record PointsAccountVO(int balance,
                              int totalEarned,
                              int totalSpent) {
}
