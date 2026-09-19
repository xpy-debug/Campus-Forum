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
 * 秒杀库存对账：以数据库为准修正 Redis 的库存键。
 *
 * <p><b>口径：{@code Redis 剩余 == available_stock}。</b>
 * 不含 {@code locked_stock}——那部分库存早已被 Redis 扣减过，
 * 不属于「Redis 侧还能被抢的量」。把 {@code locked} 也算进来是这道题最经典的一个坑：
 * 写错之后对账任务会<em>每一次</em>都报不一致，然后一边修正一边继续误报。
 *
 * <p><b>只对不在时间窗内的活动执行。</b>洪峰期间 DB 的 {@code available_stock}
 * 落后于 Redis（消费还没来得及落库），此时用 DB 覆盖 Redis 等于把库存调高——
 * 那不是修数据，那是制造超卖。管理员仍有 {@code /admin/seckill/activities/{id}/reconcile}
 * 这个强制入口，用于人工判断后的应急处理。
 *
 * <p>修正方向一律是「DB → Redis」：Redis 偏低（泄漏）与 Redis 偏高（虚增）
 * 都向 DB 收敛。宽严相济的方向是刻意的——宁可少卖，不可超卖。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillStockReconcileTask {

    private final SeckillService seckillService;
    private final RedissonClient redissonClient;

    @Scheduled(fixedDelayString = "${forum.seckill.reconcile-interval-ms:300000}")
    public void reconcile() {
        RLock lock = redissonClient.getLock(RedisKey.jobLock("seckill-stock-reconcile"));
        boolean locked = false;
        try {
            locked = lock.tryLock(0, 30, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            int fixed = seckillService.reconcileAll();
            if (fixed > 0) {
                log.warn("秒杀库存对账完成，修正了 {} 场活动的 Redis 库存", fixed);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("秒杀库存对账异常", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
