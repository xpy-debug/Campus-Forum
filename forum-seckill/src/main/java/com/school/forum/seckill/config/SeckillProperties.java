package com.school.forum.seckill.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 秒杀的可调参数，对应配置前缀 {@code forum.seckill}。
 *
 * <p>与 {@code PointsProperties} 同样的取舍：把调度周期、TTL 这类运营与运维参数
 * 放在配置里，调它们不该触发一次发版；并在启动时校验自洽性——
 * 一个填错的周期不会报错，只会安静地让某个任务跑得比预期频繁或稀疏，
 * 那类问题在测试环境极难发现。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "forum.seckill")
public class SeckillProperties {

    /** 库存预热的扫描周期。默认每分钟——活动的预热窗口是按分钟计的，比它更密没有意义 */
    private String warmupCron = "0 * * * * ?";

    /** 库存对账周期（毫秒）。默认 5 分钟，与《04》第 8 节的表格一致 */
    private long reconcileIntervalMs = 300_000;

    /** 超时订单的兜底扫描周期（毫秒）。默认 5 分钟 */
    private long timeoutScanIntervalMs = 300_000;

    /** 单次超时扫描处理的订单数上限，避免积压时一次性捞空表 */
    private int timeoutScanBatchSize = 500;

    /** 抢购结果键（{@code seckill:result}）的 TTL（秒）。默认 60 秒，够前端轮询到结果 */
    private int resultTtlSeconds = 60;

    /** 抢购结果在「已成功」之后的保留时长（小时）。默认 24 小时，够用户回来支付或查看 */
    private int resultSettledTtlHours = 24;

    /** 库存键的 TTL（小时）。默认 24 小时，覆盖活动时长 + 结算窗口 */
    private int stockTtlHours = 24;

    /** 订单号日内序号键的 TTL（秒）。默认 2 天，覆盖跨天即可 */
    private int orderNoSeqTtlSeconds = 172_800;

    /** 单个用户在一个时间窗内的抢购请求次数上限（由 Redisson 限流器执行） */
    private int grabRateLimitPerTenSeconds = 5;

    @PostConstruct
    void validate() {
        if (reconcileIntervalMs < 60_000) {
            // 对账比 1 分钟还频繁，说明配置写错了单位（毫秒当成了秒）
            throw new IllegalStateException(
                    "forum.seckill.reconcile-interval-ms 不应小于 60000（毫秒），当前=" + reconcileIntervalMs);
        }
        if (timeoutScanIntervalMs < 60_000) {
            throw new IllegalStateException(
                    "forum.seckill.timeout-scan-interval-ms 不应小于 60000（毫秒），当前=" + timeoutScanIntervalMs);
        }
        if (resultTtlSeconds <= 0) {
            throw new IllegalStateException(
                    "forum.seckill.result-ttl-seconds 必须为正数，当前=" + resultTtlSeconds);
        }
        if (stockTtlHours <= 0) {
            // 为 0 会让库存键写入即过期，活动一预热就等于没预热
            throw new IllegalStateException(
                    "forum.seckill.stock-ttl-hours 必须为正数，当前=" + stockTtlHours);
        }
        log.info("秒杀配置：预热={}，对账={}ms，超时扫描={}ms，结果 TTL={}s",
                warmupCron, reconcileIntervalMs, timeoutScanIntervalMs, resultTtlSeconds);
    }
}
