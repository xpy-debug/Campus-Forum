package com.school.forum.seckill.event;

import com.school.forum.infrastructure.mq.core.BaseEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 秒杀下单事件：Redis 预扣成功后投递，由消费者落库生成订单。
 *
 * <p><b>消息是自包含的。</b>{@code userId / activityId / orderNo} 全在消息体里，
 * 消费者不需要回头去 Redis 取任何东西。这一点不是随手写的——
 * 如果消费者要靠「读 Redis 里的 orderNo，读不到就当用户已取消」来决定是否落库，
 * 那么它与用户的取消操作之间就存在必然的竞态：消费者读到了、
 * 用户取消了、消费者再把订单写进去，用户就看到了一个「已取消却存在」的订单。
 * 把信息放进消息里，这个窗口根本不存在。
 *
 * <p>Redis 里的 {@code seckill:result} 与 {@code seckill:order} 只服务于
 * 「前端轮询结果」与「重复点击的幂等重放」，不参与落库决策。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SeckillOrderEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    private Long userId;

    private Long activityId;

    /** 抢购时由 Lua 生成的订单号。消费者的落库幂等键之一 */
    private String orderNo;

    /**
     * 分区键用活动 ID。
     *
     * <p>同一活动的消息进同一分区，顺序因此可预测（先抢到的先落库）。
     * 但要注意：<b>顺序不是正确性的依赖</b>——DB 的条件更新与唯一索引才是。
     * 保序的价值在于让结果符合直觉、让排查时的时间线读得通。
     */
    @Override
    public String routingKey() {
        return activityId == null ? super.routingKey() : String.valueOf(activityId);
    }
}
