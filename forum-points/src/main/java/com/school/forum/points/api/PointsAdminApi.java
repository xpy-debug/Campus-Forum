package com.school.forum.points.api;

/**
 * 积分管理端对 {@code forum-admin} 暴露的契约。
 *
 * <p>目前只有「手动结算签到奖励」一件事，但它值得单独存在：
 * 定时任务漏跑、或运营在月中改了全勤阈值需要补算历史月份时，
 * 都必须有一个**可以手动触发**的入口。自动任务 + 手动补跑是
 * 「任务漏跑」这一故障模式的完整解法——只有自动任务的话，
 * 漏了就只能改数据库，而那是不该被鼓励的操作。
 */
public interface PointsAdminApi {

    /**
     * 结算指定月份的月度全勤奖励。
     *
     * <p><b>幂等。</b>重复调用同一个月不会重复发放：已发放的用户会被
     * {@code uk_user_biz} 与「先查流水」两重判断挡掉。因此这个接口
     * 可以安全地重试，管理员不需要先确认「上次跑没跑过」。
     *
     * <p>传入当前月份或未来月份时返回 0，不做任何发放——
     * 那个月的签到数据还没定型，结算出来的是错数。
     *
     * @param yearMonth {@code yyyy-MM}
     * @return 本次实际发放奖励的用户数
     */
    int settleSigninBonus(String yearMonth);
}
