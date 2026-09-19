package com.school.forum.infrastructure.mq.metrics;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 消息消费明细，对应 {@code t_mq_consume_log}。
 *
 * <p><b>这张表的核心用途是选型对比</b>，不是业务审计。{@code provider} + {@code topic}
 * + {@code cost_ms} 三个字段让同一份业务逻辑在 Kafka 和 RabbitMQ 下的消费耗时
 * 落在同一张表里，可以直接 SQL 聚合对比：
 *
 * <pre>
 * SELECT provider,
 *        COUNT(*)                                   AS msg_count,
 *        ROUND(AVG(cost_ms), 2)                     AS avg_ms,
 *        MAX(cost_ms)                               AS max_ms,
 *        SUM(status = 2) / COUNT(*)                 AS fail_rate
 * FROM t_mq_consume_log
 * WHERE topic = 'forum.like' AND create_time &gt;= ?
 * GROUP BY provider;
 * </pre>
 *
 * <p><b>唯一键 {@code (message_id, consumer_group)} 决定了写入方式：</b>
 * 一条消息在一个消费组里只能有一行。因此重试时不能简单 INSERT，
 * 必须 {@code ON DUPLICATE KEY UPDATE}（见 {@link MqConsumeLogMapper#upsertBatch}）。
 * 这同时也保证了聚合统计不会把重试的同一批消息重复计数——
 * 否则「QPS」会被重试放大，两个产品的对比就失真了。
 */
@Data
@TableName("t_mq_consume_log")
public class MqConsumeLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 消息全局 ID，即 {@code BaseEvent.eventId} */
    private String messageId;

    /** 消费者组（Kafka）或队列名（RabbitMQ） */
    private String consumerGroup;

    /** 消息中间件实现：{@code kafka} / {@code rabbit} */
    private String provider;

    /** Topic 或 Exchange 名 */
    private String topic;

    /** 业务类型，取事件类的简单名，如 {@code LikeEvent} */
    private String bizType;

    /** 状态：0 处理中 1 成功 2 失败 */
    private Integer status;

    /** 业务重试次数 */
    private Integer retryCount;

    /**
     * 消费处理耗时（毫秒）。重试的情况下为<b>各次尝试的累计值</b>——
     * 关心的是「这条消息最终被处理成功一共花了多少时间」，而不是最后一次的耗时。
     */
    private Integer costMs;

    /** 失败原因 */
    private String errorMsg;

    /** 首次消费时间。重试不会更新本字段，保证按时间聚合时不会重复计数 */
    private LocalDateTime createTime;

    public static final int STATUS_PROCESSING = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAILED = 2;
}
