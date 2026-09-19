package com.school.forum.points.vo;

import java.time.LocalDate;
import java.util.List;

/**
 * 签到页的状态。
 *
 * <p>一次返回整月的签到情况，而不是「今天签没签」一个布尔值。
 * 签到页要渲染月历，用户点进来就会需要整月数据，而多查的那几十行
 * 走的是同一棵索引树；反过来按天查则是 31 次往返。
 *
 * <p><b>{@code signedDays} 与 {@code signedDates} 同源。</b>两个字段都来自
 * {@code t_user_signin}，因此「已签到 6 天」与月历上点亮的 6 个格子永远一致。
 * 若天数改用位图的 {@code BITCOUNT}，位图一旦落后于数据库，
 * 页面就会出现「日历亮了 6 格、文字写着 5 天」这种自相矛盾的显示——
 * 而位图落后恰恰是设计上预期会发生的事（Redis 可能被清）。
 * 位图的廉价计数因此只用于**发现不一致并触发修复**，不用来对外报数。
 *
 * @param yearMonth      查询的月份 {@code yyyy-MM}
 * @param today          服务器时区的今天。前端不再自己算日期——
 *                       客户端时钟不准时，会让用户看到「今天已签到」却点不动按钮
 * @param signedToday    今天是否已签到
 * @param signedDates    本月已签到的日期列表，前端据此渲染月历
 * @param signedDays     本月已签到天数
 * @param continuousDays 连续签到天数（不限于本月，见 {@code SigninService}）
 * @param signinPoints   每次签到可获得的积分，供界面显示「签到 +5」
 * @param bonusThreshold 全勤阈值，含义为「超过多少天」
 * @param bonusPoints    达到全勤可获得的奖励积分
 * @param canGetBonus    按当前进度，本月是否还有可能拿到全勤
 */
public record SigninStatusVO(String yearMonth,
                             LocalDate today,
                             boolean signedToday,
                             List<LocalDate> signedDates,
                             int signedDays,
                             int continuousDays,
                             int signinPoints,
                             int bonusThreshold,
                             int bonusPoints,
                             boolean canGetBonus) {
}
