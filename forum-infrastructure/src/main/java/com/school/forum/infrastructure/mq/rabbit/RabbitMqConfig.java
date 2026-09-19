package com.school.forum.infrastructure.mq.rabbit;

import com.school.forum.common.constant.MqTopic;
import com.school.forum.infrastructure.mq.core.MqProperties;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 相关 Bean。仅当 {@code forum.mq.provider=rabbit} 时生效。
 *
 * <p>与 {@code KafkaConfig} 一样手写连接工厂而不依赖自动配置，
 * 保证两个产品的配置项对称、可逐项对照。
 */
@Configuration
@ConditionalOnProperty(prefix = "forum.mq", name = "provider", havingValue = "rabbit")
public class RabbitMqConfig {

    @Value("${spring.rabbitmq.host:localhost}")
    private String host;

    @Value("${spring.rabbitmq.port:5672}")
    private int port;

    @Value("${spring.rabbitmq.username:guest}")
    private String username;

    @Value("${spring.rabbitmq.password:guest}")
    private String password;

    @Value("${spring.rabbitmq.virtual-host:/}")
    private String virtualHost;

    @Bean
    public ConnectionFactory rabbitConnectionFactory(MqProperties properties) {
        CachingConnectionFactory factory = new CachingConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(username);
        factory.setPassword(password);
        factory.setVirtualHost(virtualHost);

        // 开启 publisher confirm 会显著降低发送吞吐（每条消息都要等 Broker 确认），
        // 因此默认关闭。可靠投递由本地消息表兜底，而不是靠同步确认——
        // 这一点两个产品的设计是统一的，压测对比才有意义。
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.NONE);

        // Channel 缓存数。压测时并发发送线程多，缓存太小会导致频繁创建/销毁 channel，
        // 而创建 channel 是一次网络往返，会直接拉低测出来的生产 QPS。
        factory.setChannelCacheSize(50);
        return factory;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        // 发送失败必须抛异常而不是静默返回 null，否则消息丢了都不知道
        template.setReturnsCallback(returned -> {
            org.slf4j.LoggerFactory.getLogger(RabbitMqConfig.class).error(
                    "RabbitMQ 消息无法路由，已被退回。exchange={}, routingKey={}, replyText={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyText());
        });
        return template;
    }

    @Bean
    public AmqpAdmin amqpAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    // ==================== 交换机 ====================

    /**
     * 业务主交换机。用 topic 类型而非 direct：
     * topic 支持 {@code forum.like.*} 这样的通配绑定，将来要加「监听某个域下所有事件」
     * 的消费者时不用改交换机类型和已有绑定。
     */
    @Bean
    public TopicExchange forumExchange() {
        return new TopicExchange(MqTopic.EXCHANGE, true, false);
    }

    /** 死信交换机。消费失败且不可重试的消息最终进这里 */
    @Bean
    public TopicExchange forumDlxExchange() {
        return new TopicExchange(MqTopic.DLX_EXCHANGE, true, false);
    }

    // ==================== 静态工具方法 ====================

    /**
     * 构造消费者队列名。
     *
     * <p>命名规则 {@code forum.q.<topic>.<group>}：<b>同一个消费者组共享一个队列</b>，
     * 因此组内是竞争消费（一条消息只被组内一个实例处理）；
     * 不同组各有各的队列，同一条消息会被每个组各消费一次——这就是广播。
     */
    public static String queueName(String topic, String consumerGroup) {
        return "forum.q." + topic + "." + consumerGroup;
    }

    /**
     * 重试队列名。消息在这里停留一段退避时间后自动回到主队列。
     *
     * <p><b>为什么要按 level 分队列：</b>TTL 是队列属性而不是消息属性，
     * 同一个队列里所有消息的延迟时长必须一样。要做「第 1 次失败退避 1 秒、
     * 第 2 次退避 2 秒」这种递增退避，就只能每个退避档位建一个队列。
     *
     * <p>档位数 = {@code ConsumeResult.MAX_RETRY}，超过该次数的消息直接进死信，
     * 所以队列数量是可控的（主题数 × 消费者组数 × 3），不会无限增长。
     */
    public static String retryQueueName(String topic, String consumerGroup, int level) {
        return "forum.retry." + topic + "." + consumerGroup + "." + level;
    }

    /** 死信队列名 */
    public static String dlqName(String topic, String consumerGroup) {
        return "forum.dlq." + topic + "." + consumerGroup;
    }

    /**
     * 延迟队列名。<b>按「主题 + 延迟秒数」建队列，而不是只按主题建一个。</b>
     *
     * <p>原因：TTL 队列是严格的 FIFO，只有队首消息过期才会被投递。
     * 如果 30 秒和 30 分钟的消息混在同一个队列里，
     * 一条 30 分钟的消息排在队首会把后面所有 30 秒的消息一起堵住，
     * 直到它自己过期为止——这就是 TTL + DLX 方案最著名的「队头阻塞」陷阱。
     * 按延迟时长分队列后，每个队列内所有消息的 TTL 相同，堵塞问题自然消失。
     */
    public static String delayQueueName(String topic, int delaySeconds) {
        return "forum.delay." + topic + "." + delaySeconds;
    }

    /**
     * 业务队列：持久化 + 死信路由。
     *
     * <p>{@code x-dead-letter-routing-key} 设成死信队列名，配合死信交换机（topic 类型）
     * 即可精确路由。这样 {@code basicNack(requeue=false)} 的消息会自动落到对应的死信队列，
     * 不需要消费者自己转发。
     */
    public static Queue businessQueue(String topic, String consumerGroup) {
        return QueueBuilder.durable(queueName(topic, consumerGroup))
                .deadLetterExchange(MqTopic.DLX_EXCHANGE)
                .deadLetterRoutingKey(dlqName(topic, consumerGroup))
                .build();
    }

    /** 死信队列：只收不主动消费，留给人工排查或专门的补偿程序 */
    public static Queue deadLetterQueue(String topic, String consumerGroup) {
        return QueueBuilder.durable(dlqName(topic, consumerGroup)).build();
    }

    /**
     * 重试队列：设置 TTL，过期后经死信交换机回到原业务队列。
     *
     * <p>TTL 取 {@code baseMillis × level}，与 Kafka 侧
     * {@code ack.nack(Duration.ofMillis(backoff * next))} 的退避节奏保持一致。
     * 两边退避策略不同的话，压测时测出来的重试相关指标就没有可比性了。
     */
    public static Queue retryQueue(String topic, String consumerGroup, int level, long baseMillis) {
        return QueueBuilder.durable(retryQueueName(topic, consumerGroup, level))
                .ttl((int) (baseMillis * level))
                .deadLetterExchange(MqTopic.EXCHANGE)
                .deadLetterRoutingKey(topic)
                .build();
    }

    /** 延迟队列：TTL 到点后经死信交换机以原主题的 routing key 回到业务队列 */
    public static Queue delayQueue(String topic, int delaySeconds) {
        return QueueBuilder.durable(delayQueueName(topic, delaySeconds))
                .ttl(delaySeconds * 1000)
                .deadLetterExchange(MqTopic.EXCHANGE)
                .deadLetterRoutingKey(topic)
                .build();
    }

    public static Binding bind(Queue queue, String routingKey, TopicExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }
}
