package com.school.forum.points.convert;

import com.school.forum.points.entity.PointsRecord;
import com.school.forum.points.entity.UserPoints;
import com.school.forum.points.vo.PointsAccountVO;
import com.school.forum.points.vo.PointsRecordVO;
import com.school.forum.points.vo.SigninStatusVO;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 积分账户、流水、签到状态到出参的转换。
 *
 * <p>保持无依赖的静态方法，参数在调用处显式写清楚。换算规则（全勤还差几天、
 * 连续签到怎么数）集中在这里，Service 只负责取数。
 *
 * <p>不引入 MapStruct：这里需要的是**计算**而不是字段搬运——
 * 「本月还有没有可能拿全勤」这类判断用注解映射表达不出来。
 */
public final class PointsConverter {

    private PointsConverter() {
    }

    /**
     * 账户概览。
     *
     * <p>{@code null} 账户按全 0 处理：积分页在用户从未签到过时也会被打开，
     * 而那时 {@code t_user_points} 里还没有这一行。让前端去处理 null
     * 只会把同一段判空逻辑复制到每个页面。
     */
    public static PointsAccountVO toAccountVO(UserPoints account) {
        if (account == null) {
            return new PointsAccountVO(0, 0, 0);
        }
        return new PointsAccountVO(
                value(account.getBalance()),
                value(account.getTotalEarned()),
                value(account.getTotalSpent()));
    }

    public static PointsRecordVO toRecordVO(PointsRecord record) {
        return PointsRecordVO.of(record);
    }

    /**
     * 签到页状态。
     *
     * <p><b>{@code signedDays} 直接取 {@code dates} 的长度</b>，而不是另外算一个数：
     * 天数与月历必须同源，否则位图落后时页面会自相矛盾（详见 {@link SigninStatusVO}）。
     *
     * @param today          服务器时区的今天，由调用方传入而不是在这里取——
     *                       转换器里取 {@code LocalDate.now()} 会让这个方法无法被测试
     * @param dates          本月已签到的日期
     * @param continuousDays 连续签到天数，需要跨月查询，因此由 Service 算好
     */
    public static SigninStatusVO toStatusVO(String yearMonth,
                                            LocalDate today,
                                            List<LocalDate> dates,
                                            int continuousDays,
                                            int signinPoints,
                                            int bonusThreshold,
                                            int bonusPoints) {
        List<LocalDate> safeDates = dates == null ? List.of() : dates;
        int signedDays = safeDates.size();

        return new SigninStatusVO(
                yearMonth,
                today,
                safeDates.contains(today),
                safeDates,
                signedDays,
                continuousDays,
                signinPoints,
                bonusThreshold,
                bonusPoints,
                canStillGetBonus(today, signedDays, bonusThreshold));
    }

    /**
     * 本月是否还有可能拿到全勤。
     *
     * <p>算法是「把剩下的日子全部签满能不能超过阈值」，含今天（今天还没签的话
     * 还能补上）。注意用 {@code + 1}：{@code lengthOfMonth - dayOfMonth}
     * 是不含今天的剩余天数，漏掉它会少算一天，让用户在最后一天被判「没希望了」
     * 从而放弃签到——而这正是这个字段存在的意义（给用户一个继续签的理由）。
     */
    private static boolean canStillGetBonus(LocalDate today, int signedDays, int bonusThreshold) {
        int daysLeftIncludingToday = YearMonth.from(today).lengthOfMonth() - today.getDayOfMonth() + 1;
        return signedDays + daysLeftIncludingToday > bonusThreshold;
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }
}
