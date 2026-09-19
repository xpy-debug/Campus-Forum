package com.school.forum.infrastructure.mq.kafka;

import com.school.forum.common.constant.MqTopic;
import com.school.forum.infrastructure.mq.core.MqProperties;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka 相关 Bean。仅当 {@code forum.mq.provider=kafka} 时生效。
 *
 * <p><b>这里刻意手写 ProducerFactory / ConsumerFactory，而不用 Spring Boot 的
 * {@code KafkaAutoConfiguration} 自动配置。</b>原因有两个：
 *
 * <ol>
 *   <li>自动配置会把 {@code spring.kafka.*} 下的几十个参数全部暴露出去，
 *       两个产品的配置项就完全不对称了，压测时很难说清「到底哪些参数被改过」。</li>
 *   <li>自动配置的 {@code KafkaAdmin} 会在启动时主动连接 Broker 建 topic。
 *       当 provider 是 rabbit 时，这个连接会一直失败并刷错误日志，
 *       掩盖真正的问题。</li>
 * </ol>
 *
 * <p>序列化统一用 String，消息体是 JSON 文本。不用
 * {@code JsonSerializer}/{@code JsonDeserializer} 是为了避免
 * {@code __TypeId__} 消息头带来的反序列化安全风险——那个头允许发送方指定目标类名，
 * 一旦 Broker 被投毒就可能触发任意类加载。类型信息由消费侧的
 * {@link com.school.forum.infrastructure.mq.core.EventHandler#eventType()} 显式声明，
 * 不信任外部输入。
 */
@Configuration
@ConditionalOnProperty(prefix = "forum.mq", name = "provider", havingValue = "kafka")
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, String> kafkaProducerFactory(MqProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // acks=1：只等 leader 写入，不等 ISR 全部同步。
        // 这是吞吐与可靠性的折中点，也是压测时需要固定并记录的参数
        // （t_mq_benchmark_result.ack_mode 字段）。
        // 改成 all 会显著降低吞吐，两者差异能达到数倍，不记录就没有可比性。
        config.put(ProducerConfig.ACKS_CONFIG, "1");
        config.put(ProducerConfig.RETRIES_CONFIG, 3);

        // linger.ms=0：消息立即发送，不等待凑批。
        // 这是「低延迟」配置；吞吐压测时应调大到 5~50ms 换取更大的批次，
        // 两种配置的结论完全不同，必须作为变量记录。
        config.put(ProducerConfig.LINGER_MS_CONFIG, 0);

        // 默认不压缩。压缩能显著提升吞吐但会消耗 CPU 并增加延迟，
        // 属于压测变量而非固定项。
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "none");

        // 发送缓冲区。达到上限时 send() 会阻塞最多 max.block.ms，
        // 压测打满时这里是第一个瓶颈点，调大可提升突发吞吐。
        config.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 64 * 1024 * 1024L);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public ConsumerFactory<String, String> kafkaConsumerFactory(MqProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        // 必须关闭自动提交。开启后 offset 会按时间间隔提交，
        // 与「业务是否处理成功」完全无关——消费者崩溃时这批消息就永久丢了。
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, properties.getKafka().isAutoCommit());

        // 没有 offset 时从最早开始读，保证压测时不会漏掉启动前就已发出的消息
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, properties.getKafka().getMaxPollRecords());

        // 压测时消费逻辑很快，适当放宽会话超时可以减少误判 rebalance。
        // 默认 45s 在 GC 停顿较长的压测场景下容易触发不必要的 rebalance，
        // 而 rebalance 期间整个消费组是停摆的，会直接拉低测出来的 QPS。
        config.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30_000);
        config.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10_000);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    // ==================== Topic 声明 ====================

    /**
     * 显式声明 Topic，而不是依赖 Broker 的 {@code auto.create.topics.enable}。
     *
     * <p>自动创建出来的 Topic 用的是 Broker 的默认分区数，
     * 而<b>分区数直接决定消费并行度的上限</b>——每个分区最多被消费组内一个线程消费。
     * 分区数不同，压测出来的 QPS 差好几倍，而且事后无法区分
     * 「是 MQ 的差异还是分区配置的差异」。
     *
     * <p>分区数会写进 {@code t_mq_benchmark_result.partition_count} 字段，
     * 与 RabbitMQ 侧的队列数/消费者数对照记录。
     */
    /**
     * Broker 不可用时是否让应用启动失败。
     *
     * <p>默认 {@code false}（启动不失败）：开发时经常先起应用再起 Kafka，
     * 或者只想跑通接口而不关心消息链路，此时因为连不上 Broker 就整个起不来
     * 会非常影响效率。
     *
     * <p><b>生产环境应当设为 {@code true}。</b>Topic 建不出来意味着
     * 「消息发出去可能没有对应的主题」，而 Kafka 在自动创建关闭时
     * 会直接丢弃发往不存在主题的消息——这种问题上线后极难发现，
     * 不如启动时就失败。
     */
    @Value("${forum.mq.kafka.fatal-if-broker-unavailable:false}")
    private boolean fatalIfBrokerUnavailable;

    @Bean
    public KafkaAdmin kafkaAdmin() {
        KafkaAdmin admin = new KafkaAdmin(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
        admin.setFatalIfBrokerNotAvailable(fatalIfBrokerUnavailable);
        return admin;
    }

    /** 默认分区数。压测时作为变量调整，需与 RabbitMQ 侧的并发消费者数对齐才有可比性 */
    private static final int PARTITIONS = 3;

    @Bean
    public NewTopic likeTopic() {
        return TopicBuilder.name(MqTopic.LIKE).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic unlikeTopic() {
        return TopicBuilder.name(MqTopic.UNLIKE).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic commentTopic() {
        return TopicBuilder.name(MqTopic.COMMENT).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic notificationTopic() {
        return TopicBuilder.name(MqTopic.NOTIFICATION).partitions(PARTITIONS).replicas(1).build();
    }

    /**
     * 秒杀下单 Topic。<b>分区数刻意给到 6，比其他主题多一倍。</b>
     *
     * <p>秒杀是全系统瞬时吞吐最高的链路（目标 10000 QPS），
     * 而「生产端能打多快」并不取决于 Topic 有多少分区——
     * Kafka 写入是按分区并行的，但真正决定消费速度的是分区数
     * （每分区一个消费线程）。3 个分区意味着秒杀消息的消费上限被卡在
     * 3 个线程的处理能力上，会先于 RabbitMQ 成为瓶颈，
     * 那样测出来的差距反映的是配置差异而不是产品差异。
     */
    @Bean
    public NewTopic seckillOrderTopic() {
        return TopicBuilder.name(MqTopic.SECKILL_ORDER).partitions(PARTITIONS * 2).replicas(1).build();
    }

    @Bean
    public NewTopic countSyncTopic() {
        return TopicBuilder.name(MqTopic.COUNT_SYNC).partitions(PARTITIONS).replicas(1).build();
    }
}
