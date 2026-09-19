package com.school.forum.infrastructure.mq.core;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MQ 抽象层的配置。所有与产品相关的可调参数集中在此，
 * 业务模块不感知任何一项。
 *
 * <pre>
 * forum:
 *   mq:
 *     provider: rabbit          # kafka | rabbit —— 唯一的切换开关
 *     retry-backoff-ms: 1000
 *     metrics:
 *       enabled: true
 *       flush-interval-ms: 5000
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "forum.mq")
public class MqProperties {

    /**
     * 当前生效的 MQ 产品。可选值 {@code kafka} / {@code rabbit}。
     * <p>与 Spring Profile（{@code mq-kafka} / {@code mq-rabbit}）联动：
     * Profile 决定加载哪一套连接配置与自动装配排除项，本属性决定实例化哪一套实现类。
     * 两者必须一致，否则会出现「连的是 RabbitMQ 但发消息走 Kafka」这类难查的问题。
     */
    private String provider = "rabbit";

    /** 消费失败后的重试退避基数（毫秒）。第 n 次重试等待 n × 本值，避免失败瞬间打爆下游 */
    private long retryBackoffMs = 1000;

    /** 幂等去重记录的 TTL（小时）。超过这个时间的重复消息不再拦截 */
    private int idempotentTtlHours = 24;

    /** 消费失败（DISCARD）时告警的日志级别是否提升到 ERROR */
    private boolean alertOnDiscard = true;

    private final Kafka kafka = new Kafka();
    private final Rabbit rabbit = new Rabbit();
    private final Metrics metrics = new Metrics();
    private final Outbox outbox = new Outbox();

    /** Kafka 专属配置 */
    @Data
    public static class Kafka {
        /**
         * 消费者并发度。每个分区最多被一个线程消费，
         * 所以本值超过分区数不会带来额外吞吐，只会浪费线程。
         */
        private int concurrency = 3;

        /**
         * 单次拉取的最大记录数。
         * <p>调大可提升吞吐但会增加单批处理时间和 rebalance 时重复消费的量；
         * 压测时应作为可调变量记录在 {@code t_mq_benchmark_result} 中。
         */
        private int maxPollRecords = 200;

        /** 自动提交必须关闭——否则「业务还没处理完，offset 已提交」，宕机即丢消息 */
        private boolean autoCommit = false;

        /** 消费组前缀，便于同一套代码用不同 group 做多次压测互不干扰 */
        private String groupPrefix = "forum";
    }

    /** RabbitMQ 专属配置 */
    @Data
    public static class Rabbit {
        /** 每个消费者的并发消费者数（SimpleMessageListenerContainer 的 concurrentConsumers） */
        private int concurrentConsumers = 3;

        /** 最大并发消费者数。流量上涨时容器会在此范围内自动扩容 */
        private int maxConcurrentConsumers = 8;

        /** 每个消费者一次预取的消息数。调大提升吞吐但会加剧不均衡与重复消费量 */
        private int prefetchCount = 50;

        /** 消费者手动 ack。自动 ack 在消费者崩溃时会丢消息 */
        private boolean manualAck = true;

        /**
         * 队列是否持久化。
         * <p>压测吞吐时应固定为 true，否则测出来的是「内存队列」的性能，
         * 与生产环境的持久化写入开销差距可能达到数倍，结论不可用。
         */
        private boolean durable = true;
    }

    /**
     * 消费埋点配置。
     *
     * <p><b>为什么不让埋点同步落库：</b>每条消息写一行 {@code t_mq_consume_log}
     * 会让 MySQL 成为瓶颈，此时测出来的 QPS 是数据库的 QPS，不是 MQ 的 QPS——
     * 对比就失去了意义。因此埋点在内存中聚合，定时批量落库；
     * 做极限吞吐压测时可以把 {@code enabled} 设为 false，把埋点开销降到接近零。
     */
    @Data
    public static class Metrics {
        /** 是否开启消费埋点 */
        private boolean enabled = true;

        /** 批量落库间隔（毫秒） */
        private long flushIntervalMs = 5000;

        /** 内存缓冲区上限。达到上限后丢弃最早的数据，防止压测时 OOM */
        private int bufferSize = 10000;

        /** 是否记录每条消息的明细。关闭后只记录聚合指标（P99、QPS） */
        private boolean recordDetail = true;
    }

    /**
     * 本地消息表（outbox）配置。
     *
     * <p>它承担两个职责：可靠投递的兜底，以及 <b>Kafka 侧的延迟消息</b>
     * （Kafka 没有原生延迟能力，只能靠外部调度）。
     */
    @Data
    public static class Outbox {
        /** 是否开启本地消息表调度。仅 Kafka 的延迟消息依赖它 */
        private boolean enabled = true;

        /** 扫描间隔（毫秒）。越短延迟越准，但空扫越频繁 */
        private long scanIntervalMs = 1000;

        /** 单次扫描的最大条数，防止积压时一次性捞空表 */
        private int batchSize = 200;

        /** 投递失败后的最大重试次数，超过则标记为发送失败等待人工处理 */
        private int maxRetry = 5;
    }
}
