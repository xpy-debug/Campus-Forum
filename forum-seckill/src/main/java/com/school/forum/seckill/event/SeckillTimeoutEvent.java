package com.school.forum.seckill.event;

import com.school.forum.infrastructure.mq.core.BaseEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 秒杀订单超时事件：订单创建时以 {@code payTimeoutSec} 的延迟投递，
 * 到期后由消费者关闭订单并回补库存。
 *
 * <p><b>这是超时取消的「及时路径」，不是唯一路径。</b>每 5 分钟的定时扫描
 * （{@link com.school.forum.seckill.task.SeckillTimeoutScanTask}）是兜底：
 * 延迟消息可能因 outbox 积压、进程重启、消息丢失而失效，
 * 而「库存被一笔未支付的订单占着不放」是用户可感知的问题。
 * 延迟消息保证及时性，定时扫描保证可靠性，两者缺一不可。
 *
 * <p>Kafka 无原生延迟能力，{@code publishDelay} 会把本事件写进
 * {@code t_mq_message} 并设置 {@code next_retry_time = now + delay}，
 * 由调度器到期投递（崩溃安全）。这是本项目「一套业务跑两家 MQ」的差异点之一。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SeckillTimeoutEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    private Long userId;

    private Long activityId;

    private String orderNo;

    /**
     * 分区键用订单号：同一张单的超时消息只会有一条，不需要与同活动的其他消息保序。
     * 用订单号能让消息在分区上的分布更均匀，避免大活动把单个分区压满。
     */
    @Override
    public String routingKey() {
        return orderNo == null ? super.routingKey() : orderNo;
    }
}
