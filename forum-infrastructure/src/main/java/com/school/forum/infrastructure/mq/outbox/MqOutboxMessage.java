package com.school.forum.infrastructure.mq.outbox;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 本地消息表，对应 {@code t_mq_message}。
 *
 * <p><b>它解决什么问题：</b>「业务写库」和「消息发送」是两个独立的系统，
 * 无法放进同一个事务里。典型故障是：数据库提交成功、进程在发消息前崩溃，
 * 消息就永久丢了。本地消息表把「待发的消息」和业务数据放在同一个本地事务里落库，
 * 再由调度器扫描补发，把「两个系统的一致性」降级为「一个数据库的事务 + 一个可重试的投递」。
 *
 * <p><b>本项目中它的第二个职责是承载 Kafka 的延迟消息。</b>
 * Kafka 没有原生的延迟投递能力，所以 {@code publishDelay} 把消息连同
 * {@code next_retry_time = now + delay} 写进本表，由
 * {@link MqOutboxDispatcher} 到期后投递。相比「投到中间 topic + 内存定时器」的做法，
 * 这种实现是<b>崩溃安全</b>的：进程重启后消息仍在表里，不会丢。
 * RabbitMQ 侧则走原生的 TTL + 死信队列，完全不落这张表——
 * 这个差异本身就是选型时值得记录的一项。
 */
@Data
@TableName("t_mq_message")
public class MqOutboxMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 全局唯一消息 ID。与 {@code BaseEvent.eventId} 一致，消费端据此幂等 */
    private String messageId;

    /** 发送时使用的 MQ 实现：{@code kafka} / {@code rabbit} */
    private String provider;

    /** Topic 或 Exchange */
    private String topic;

    /** RabbitMQ routingKey / Kafka partition key */
    private String routingKey;

    /** 业务类型，如 {@code LikeEvent} */
    private String bizType;

    /** 业务幂等键，如 {@code like:1:1:100}。用于排查与去重 */
    private String bizKey;

    /** 消息体（JSON 字符串，对应 JSON 列） */
    private String payload;

    /** 附加消息头（JSON 字符串） */
    private String headers;

    /** 状态：0 待发送 1 已发送 2 发送失败 3 已消费 4 消费失败 */
    private Integer status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 最大重试次数 */
    private Integer maxRetry;

    /** 下次投递时间。延迟消息就是靠这个字段实现的 */
    private LocalDateTime nextRetryTime;

    /** 最近一次错误信息 */
    private String errorMsg;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SENT = 1;
    public static final int STATUS_SEND_FAILED = 2;
    public static final int STATUS_CONSUMED = 3;
    public static final int STATUS_CONSUME_FAILED = 4;
}
