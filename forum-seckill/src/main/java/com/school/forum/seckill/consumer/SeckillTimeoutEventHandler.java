package com.school.forum.seckill.consumer;

import com.school.forum.common.constant.MqTopic;
import com.school.forum.infrastructure.mq.core.ConsumeResult;
import com.school.forum.infrastructure.mq.core.EventHandler;
import com.school.forum.seckill.event.SeckillTimeoutEvent;
import com.school.forum.seckill.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 秒杀订单超时消费者：关闭未支付的订单并把库存放回去。
 *
 * <p>它是超时取消的<b>主路径</b>，但不是唯一路径——每 5 分钟的定时扫描
 * （{@code SeckillTimeoutScanTask}）是兜底。两者互补的理由见
 * {@link SeckillTimeoutEvent} 的类注释。
 *
 * <p>用户已经支付完成时，关闭动作会因 {@code WHERE status = 0} 影响 0 行而静默返回。
 * 这是<b>预期行为而不是异常</b>：延迟消息在投递过程中可能与支付并发，
 * 谁先到都可能——而正确答案永远是「支付优先」。
 * 让这种正常的竞争走到 DISCARD / 死信队列，只会制造一堆无需处理的告警。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillTimeoutEventHandler implements EventHandler<SeckillTimeoutEvent> {

    private final SeckillService seckillService;

    @Override
    public String topic() {
        return MqTopic.SECKILL_TIMEOUT;
    }

    @Override
    public Class<SeckillTimeoutEvent> eventType() {
        return SeckillTimeoutEvent.class;
    }

    @Override
    public ConsumeResult handle(SeckillTimeoutEvent event) {
        seckillService.closeTimeout(event);
        return ConsumeResult.SUCCESS;
    }
}
