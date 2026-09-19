package com.school.forum.points.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 积分与商城的可调参数，对应配置前缀 {@code forum.points}。
 *
 * <p><b>为什么要做成配置而不是常量：</b>签到积分与全勤阈值属于运营策略，
 * 调整它们不应触发一次发版。同时，把它们集中在这里也便于一眼看清
 * 「这个系统当前的积分规则是什么」——散落在 Service 里的字面量做不到这一点。
 *
 * <p>注意 {@code signinPoints} 只在**新写入**的签到记录上生效。
 * 历史记录存的是当次发放的数值（{@code t_user_signin.points}），
 * 调参不会改写过去——这与「累计消耗过 100 分不会因为退款消失」是同一条原则：
 * <b>已经发生的事不该被后来的配置改写。</b>
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "forum.points")
public class PointsProperties {

    /** 每日签到获得的积分 */
    private int signinPoints = 5;

    /**
     * 月度全勤的判定阈值：当月签到天数**超过**这个值才有奖励。
     * <p>需求是「超过 25 天」，故默认 25 表示 26 天及以上，
     * 与 SQL 里的 {@code HAVING COUNT(*) > threshold} 严格对应。
     */
    private int bonusThreshold = 25;

    /** 月度全勤奖励积分 */
    private int bonusPoints = 100;

    /** 单批结算的用户数上限。分批是为了让任务保持短事务（见《04》6.1 节） */
    private int bonusBatchSize = 2000;

    /**
     * 签到位图的 TTL（天）。
     *
     * <p>取 70 天是因为位图要跨越「当月统计」与「次月 1 日结算上月」两个时间点：
     * 31 天 + 结算窗口 + 容错余量。再长就是纯粹的内存浪费——
     * 按每用户每月 4 字节算，100 万用户的 70 天存量也只有约 30 MB。
     */
    private int bitmapTtlDays = 70;

    /**
     * 启动时校验配置的自洽性。
     *
     * <p>配错这些值不会有任何运行期报错，只会安静地算错：阈值为 0 会让所有人拿全勤，
     * 为负则永远拿不到；批大小为 0 会让任务悄悄地什么都不做。
     * 这类错误在测试环境很容易漏过，所以在启动时就拦下来。
     */
    @PostConstruct
    void validate() {
        if (signinPoints <= 0) {
            throw new IllegalStateException("forum.points.signin-points 必须为正数，当前=" + signinPoints);
        }
        if (bonusPoints <= 0) {
            throw new IllegalStateException("forum.points.bonus-points 必须为正数，当前=" + bonusPoints);
        }
        if (bonusThreshold < 1 || bonusThreshold > 30) {
            throw new IllegalStateException(
                    "forum.points.bonus-threshold 应在 1~30 之间（含义为「超过多少天」），当前=" + bonusThreshold);
        }
        if (bonusBatchSize <= 0) {
            throw new IllegalStateException("forum.points.bonus-batch-size 必须为正数，当前=" + bonusBatchSize);
        }
        if (bitmapTtlDays < 32) {
            // 小于 32 天时位图会在当月就过期，月度统计直接归零，且不会报任何错
            throw new IllegalStateException(
                    "forum.points.bitmap-ttl-days 至少为 32（需覆盖一个完整月份），当前=" + bitmapTtlDays);
        }
        log.info("积分规则：每日 {} 分，当月签到超过 {} 天额外奖励 {} 分",
                signinPoints, bonusThreshold, bonusPoints);
    }
}
