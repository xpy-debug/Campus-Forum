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
 * 超时订单的兜底扫描。
 *
 * <p><b>为什么已经有了延迟消息还需要它：</b>延迟消息在 Kafka 侧依赖 outbox 调度器，
 * 而 outbox 可能因为数据库慢、进程重启、消息重试超限而长时间投递不出去甚至丢失。
 * 一旦如此，「用户抢到了但没付款」的那件库存就会一直被占在 {@code locked_stock} 里，
 * 既卖不出去、也不会自己回来。延迟消息保证及时性（几秒内），
 * 本扫描保证可靠性（最多晚一个周期）。
 *
 * <p>反过来也不能只留扫描：5 分钟的周期意味着库存至少被占 5 分钟，
 * 而秒杀活动的整个时长可能只有几分钟。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillTimeoutScanTask {

    private final SeckillService seckillService;
    private final RedissonClient redissonClient;

    @Scheduled(fixedDelayString = "${forum.seckill.timeout-scan-interval-ms:300000}")
    public void scan() {
        RLock lock = redissonClient.getLock(RedisKey.jobLock("seckill-timeout-scan"));
        boolean locked = false;
        try {
            locked = lock.tryLock(0, 30, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            int closed = seckillService.scanTimeoutOrders();
            if (closed > 0) {
                log.info("秒杀超时订单扫描完成，关闭 {} 笔", closed);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("秒杀超时订单扫描异常", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
