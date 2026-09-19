package com.school.forum.points.task;

import com.school.forum.points.api.PointsAdminApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/**
 * 月度全勤奖励结算任务：每天凌晨结算**上个月**。
 *
 * <p><b>为什么是「每天结算上月」而不是「月底结算当月」：</b>
 * 月底那一次是单点，重启、发版、机器故障任何一个原因都会导致整月漏发，
 * 而漏发之后只能靠人工发现——用户不会为了 100 积分来找客服。
 * 改成每天重跑上月之后，任何一天的失败都会在第二天自动补上，
 * 也不需要处理「23:59 与 00:01 之间跨月」的边界。
 *
 * <p>重复执行不会重复发放：幂等由 {@code t_points_record} 的
 * {@code uk_user_biz} 保证，见 {@link PointsAdminApi#settleSigninBonus}。
 * 因此本任务**刻意不做任何「跑过了就跳过」的记录**——
 * 进度状态本身会丢，而唯一索引不会。
 *
 * <p>结算逻辑复用管理端的接口而不是另写一份：管理端需要「手动补算历史月份」的能力
 * （运营改了阈值要重算），如果任务自己维护一套实现，两边的规则迟早会不一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlySigninBonusTask {

    private final PointsAdminApi pointsAdminApi;

    /**
     * 每月 1 日跑的是「上个月」，其余日子跑的还是「上个月」——
     * 也就是说 9 月 1 日跑 8 月，9 月 2 日仍然跑 8 月，直到 10 月 1 日才开始跑 9 月。
     */
    /**
     * 默认每日 00:10（《04》7.3 节）。做成配置项的理由同
     * {@code SigninBitmapRepairTask#repair()}：时间可挪动，且可被测试触发。
     */
    @Scheduled(cron = "${forum.points.bonus-cron:0 10 0 * * ?}")
    public void settleLastMonth() {
        String yearMonth = YearMonth.now().minusMonths(1).toString();
        try {
            int users = pointsAdminApi.settleSigninBonus(yearMonth);
            log.info("月度全勤奖励结算任务结束：month={}, 本次发放用户数={}", yearMonth, users);
        } catch (Exception e) {
            // 任务失败不抛出：抛出只会被调度线程记一行日志，而明天的重跑
            // 才是真正的兜底。这里明确记下来，便于排查「为什么连着几天都没发」
            log.error("月度全勤奖励结算任务失败，将在下次调度时重试。month={}", yearMonth, e);
        }
    }
}
