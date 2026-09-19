package com.school.forum.infrastructure.mq.core;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 所有领域事件的基类。
 *
 * <p><b>为什么每个事件都必须带 eventId：</b>抽象层对外的语义承诺只有
 * <b>at-least-once（至少一次）</b>，不承诺 exactly-once。Kafka 在
 * {@code enable.auto.commit=false} + 手动提交的模型下，若消费者处理完消息、
 * 提交 offset 之前进程崩溃，重启后这条消息会被重新投递；RabbitMQ 的
 * {@code basicNack(requeue=true)} 同理。因此下游必须能识别重复消息，
 * {@code eventId} 就是去重的依据。
 *
 * <p><b>不要把 eventId 用成业务主键。</b>它是「这一次投递」的标识，不是「这个业务对象」
 * 的标识。例如点赞事件里既有 {@code eventId}（消息去重）也有 {@code userId + postId}
 * （业务唯一键，用于 {@code t_user_like} 的唯一索引冲突检测），两者缺一不可：
 * 前者防重复消费，后者防重复点赞。
 *
 * <p>{@code occurredAt} 记录的是<b>事件在生产者侧产生的时刻</b>，不是消息入队时刻，
 * 更不是消费时刻。做端到端延迟压测时用它和消费时刻相减，能把「MQ 自身的传输 +
 * 排队延迟」从「生产者业务逻辑耗时」里剥离出来。
 */
@Data
public abstract class BaseEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件唯一标识，默认由生产者生成 UUID。消费者用它做幂等去重 */
    private String eventId;

    /** 事件发生时刻（生产者本地时钟）。用于端到端延迟统计 */
    private LocalDateTime occurredAt;

    /**
     * 生产者标识，形如 {@code forum-forum@192.168.1.10:8080}。
     * 排查「消息是从哪个实例发出来的」时非常有用，尤其是压测时多个生产端并存的情况。
     */
    private String producer;

    protected BaseEvent() {
        this.eventId = java.util.UUID.randomUUID().toString().replace("-", "");
        this.occurredAt = LocalDateTime.now();
    }

    /**
     * 分区键 / 路由键。<b>子类应当重写本方法返回业务主键</b>，例如
     * {@code LikeEvent} 返回 {@code "post:" + postId}。
     *
     * <p>默认实现返回 {@code eventId}——这等于放弃保序，因为每条消息的 eventId 都不同，
     * 会被散列到不同分区（Kafka）或不同消费者（RabbitMQ），
     * 同一个业务对象的两个操作就可能被并发处理而乱序。
     * 之所以还给这个默认值，是因为有些事件（如站内通知）确实不关心顺序。
     *
     * <p><b>注意：</b>分区键的选择会直接影响压测结果。全部用同一个键
     * 会把所有消息压到一个分区上，吞吐受单分区上限约束，测出来的不是集群能力；
     * 全部用随机键则完全并行。两种模式衡量的是不同指标，压测时应分别记录。
     */
    public String routingKey() {
        return this.eventId;
    }
}
