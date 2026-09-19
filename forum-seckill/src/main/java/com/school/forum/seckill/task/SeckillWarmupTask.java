package com.school.forum.seckill.task;

import com.school.forum.common.constant.RedisKey;
import com.school.forum.seckill.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 秒杀活动的两个周期性维护动作：<b>状态刷新</b>与<b>库存预热</b>。
 *
 * <p>放在同一个类里是因为它们的扫描对象完全相同、周期也一致（每分钟），
 * 拆成两个 @Scheduled 只会让同一份查询跑两遍。
 *
 * <p><b>为什么都要抢分布式锁：</b>多实例部署下，每个实例的调度器都会触发。
 * 状态刷新是幂等的 UPDATE（重复执行同一条也无害），但预热会写 Redis、
 * 还要打日志，多实例同时做纯属浪费；锁的作用是省掉这些无用功，
 * 真正的正确性由 {@code SETNX} 与条件更新保证——<b>锁用来减少冲突，不用来保证正确</b>。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillWarmupTask {

    private final SeckillService seckillService;
    private final RedissonClient redissonClient;

    /**
     * 每分钟刷新活动状态（未开始 / 进行中 / 已结束）。
     *
     * <p>不刷的话，数据库里的状态会一直停在创建时的值，
     * 用户侧列表就会把「早就结束」的活动显示成「进行中」。
     */
    @Scheduled(cron = "${forum.seckill.warmup-cron:0 * * * * ?}")
    public void refreshStatus() {
        RLock lock = redissonClient.getLock(RedisKey.jobLock("seckill-status-refresh"));
        withLock(lock, () -> {
            int updated = seckillService.refreshActivityStatus();
            if (updated > 0) {
                log.info("秒杀活动状态刷新完成，更新 {} 场", updated);
            }
        });
    }

    /**
     * 预热即将开始的活动：写库存与活动快照。
     *
     * <p>预热窗口一直延伸到活动结束，因此它同时承担「补预热」的职责：
     * 应用在活动开始后才启动、或预热连续失败时，下一次扫描会把活动救回来。
     * 用户侧因此不会出现「活动明明在进行中，点进去却提示活动太火爆」的死局。
     */
    @Scheduled(cron = "${forum.seckill.warmup-cron:0 * * * * ?}")
    public void warmup() {
        withLock(redissonClient.getLock(RedisKey.jobLock("seckill-warmup")), () -> {
            int warmed = seckillService.warmupActivities();
            if (warmed > 0) {
                log.info("秒杀活动预热完成 {} 场", warmed);
            }
        });
    }

    private void withLock(RLock lock, Runnable action) {
        boolean locked = false;
        try {
            // tryLock 不等待：抢不到说明别的实例正在做，本轮直接跳过，
            // 下一个调度周期很快就到，排队等待反而会白占一个调度线程
            locked = lock.tryLock(0, 30, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            action.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 定时任务里的异常必须自己吞掉并记录：抛出去会被调度器当成
            // 「本次执行失败」，而这个任务本来就是周期性的，下一轮自然会重试
            log.error("秒杀定时任务执行异常", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
