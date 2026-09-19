package com.school.forum.seckill.consumer;

import com.school.forum.common.constant.MqTopic;
import com.school.forum.infrastructure.mq.core.ConsumeResult;
import com.school.forum.infrastructure.mq.core.EventHandler;
import com.school.forum.seckill.event.SeckillOrderEvent;
import com.school.forum.seckill.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 秒杀下单事件消费者：把 Redis 预扣过的请求落成真实订单。
 *
 * <p><b>它只做三件事：调用服务、吞掉唯一键冲突、返回成功。</b>
 * 所有业务规则都在 {@link SeckillService#createOrder} 里——
 * 消费者一旦开始做判断，判断就会和「异步」这个前提纠缠在一起，
 * 而异步链路上最难排查的正是「消息到底有没有生效」。
 *
 * <p><b>为什么必须自己捕获 {@code DuplicateKeyException}：</b>
 * {@code MessageDispatcher} 会把未捕获的非业务异常判为系统异常并返回 RETRY，
 * 于是同一条消息被反复重试 3 次。但这条消息的「冲突」恰恰说明它<em>已经成功了</em>
 * ——{@code uk_order_no} 或 {@code uk_user_activity} 拦下的是一次重复投递。
 * 不捕获的话，每次重复投递都要白跑三次数据库，洪峰场景下这些无效重试
 * 会直接吃掉消费线程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderEventHandler implements EventHandler<SeckillOrderEvent> {

    private final SeckillService seckillService;

    @Override
    public String topic() {
        return MqTopic.SECKILL_ORDER;
    }

    @Override
    public Class<SeckillOrderEvent> eventType() {
        return SeckillOrderEvent.class;
    }

    @Override
    public ConsumeResult handle(SeckillOrderEvent event) {
        try {
            seckillService.createOrder(event);
        } catch (DuplicateKeyException e) {
            // 唯一键冲突 = 这张单已经在库里了。对用户而言与成功没有区别，
            // 结果键也必须置为 SUCCESS，否则前端会一直轮询一个「永远排队中」的订单
            log.warn("秒杀订单已存在（消息重复投递或 Redis 判重失效），按成功处理。orderNo={}",
                    event.getOrderNo());
            seckillService.markOrderCreated(event.getActivityId(), event.getUserId(), event.getOrderNo());
        }
        return ConsumeResult.SUCCESS;
    }
}
