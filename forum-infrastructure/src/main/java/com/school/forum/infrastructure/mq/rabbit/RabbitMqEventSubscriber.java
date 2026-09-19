package com.school.forum.infrastructure.mq.rabbit;

import com.school.forum.common.constant.MqTopic;
import com.school.forum.infrastructure.mq.core.ConsumeResult;
import com.school.forum.infrastructure.mq.core.EventHandler;
import com.school.forum.infrastructure.mq.core.MessageDispatcher;
import com.school.forum.infrastructure.mq.core.MqProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RabbitMQ 消费端。与 {@code KafkaEventSubscriber} 完全对称：
 * 启动时按 {@link EventHandler} 建监听容器，业务代码里没有任何
 * {@code @RabbitListener} 注解。
 *
 * <p>每个消费者组对应一个队列，队列在启动时声明并绑定到 {@link MqTopic#EXCHANGE}。
 * 组内多个实例共享同一个队列 → 竞争消费；不同组各自一个队列 → 各消费一次（广播）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "forum.mq", name = "provider", havingValue = "rabbit")
public class RabbitMqEventSubscriber implements SmartLifecycle {

    /** 消息头：已重试次数。由本类在转入重试队列时写入 */
    private static final String HEADER_RETRY_COUNT = "x-retry-count";

    private final ConnectionFactory connectionFactory;
    private final AmqpAdmin amqpAdmin;
    private final RabbitTemplate rabbitTemplate;
    private final TopicExchange forumExchange;
    private final List<EventHandler<?>> handlers;
    private final MessageDispatcher dispatcher;
    private final MqProperties properties;

    private final List<SimpleMessageListenerContainer> containers = new ArrayList<>();

    /** 本次启动已声明过的 (topic, group)，防止多个 Handler 重复声明同一队列 */
    private final Map<String, Boolean> declared = new HashMap<>();

    private volatile boolean running = false;

    @Override
    public void start() {
        if (handlers.isEmpty()) {
            log.warn("没有发现任何 EventHandler，RabbitMQ 消费者未启动");
            running = true;
            return;
        }
        for (EventHandler<?> handler : handlers) {
            try {
                declareTopology(handler);
                containers.add(createContainer(handler));
            } catch (Exception e) {
                log.error("创建 RabbitMQ 监听容器失败。handler={}, topic={}",
                        handler.getClass().getName(), handler.topic(), e);
            }
        }
        containers.forEach(SimpleMessageListenerContainer::start);
        running = true;
        log.info("RabbitMQ 消费者已启动，共 {} 个容器，每容器并发 {}~{}",
                containers.size(),
                properties.getRabbit().getConcurrentConsumers(),
                properties.getRabbit().getMaxConcurrentConsumers());
    }

    /**
     * 声明队列、死信队列、各级重试队列与绑定。
     *
     * <p><b>必须在启动时声明完整拓扑</b>，而不是等消息来了再建：
     * 消息一旦先于队列到达，会被直接丢弃（发到不存在的交换机/路由键上），
     * 且不会有任何报错——这是 RabbitMQ 最容易踩的坑之一。
     * 生产者已经通过 {@code mandatory=true} + ReturnsCallback 兜底记录日志，
     * 但根治办法还是保证拓扑先于消息存在。
     */
    private void declareTopology(EventHandler<?> handler) {
        String topic = handler.topic();
        String group = handler.consumerGroup();
        String key = topic + "|" + group;
        if (declared.containsKey(key)) {
            return;
        }
        declared.put(key, Boolean.TRUE);

        // 业务队列 + 绑定
        Queue business = RabbitMqConfig.businessQueue(topic, group);
        amqpAdmin.declareQueue(business);
        amqpAdmin.declareBinding(RabbitMqConfig.bind(business, topic, forumExchange));

        // 死信队列 + 绑定（routing key 用死信队列名，与业务队列的 x-dead-letter-routing-key 对应）
        Queue dlq = RabbitMqConfig.deadLetterQueue(topic, group);
        amqpAdmin.declareQueue(dlq);
        amqpAdmin.declareBinding(RabbitMqConfig.bind(dlq, RabbitMqConfig.dlqName(topic, group),
                dlxExchange()));

        // 各级重试队列。TTL 到点后经主交换机以 topic 为 routing key 回到业务队列
        for (int level = 1; level <= ConsumeResult.MAX_RETRY; level++) {
            Queue retry = RabbitMqConfig.retryQueue(topic, group, level, properties.getRetryBackoffMs());
            amqpAdmin.declareQueue(retry);
            amqpAdmin.declareBinding(RabbitMqConfig.bind(retry,
                    RabbitMqConfig.retryQueueName(topic, group, level), forumExchange));
        }

        log.info("已声明 RabbitMQ 拓扑。topic={}, group={}, queue={}",
                topic, group, RabbitMqConfig.queueName(topic, group));
    }

    /**
     * 死信交换机的引用。
     *
     * <p>不直接注入 {@code TopicExchange} Bean 是因为 {@code RabbitMqConfig} 里
     * 声明了两个 TopicExchange，按类型注入会有歧义。这里直接按名字构造一个等价对象——
     * 声明交换机只需要名字、类型、持久化标志一致，不需要是同一个 Java 实例。
     */
    private TopicExchange dlxExchange() {
        return new TopicExchange(MqTopic.DLX_EXCHANGE, true, false);
    }

    private SimpleMessageListenerContainer createContainer(EventHandler<?> handler) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(RabbitMqConfig.queueName(handler.topic(), handler.consumerGroup()));
        container.setConcurrentConsumers(properties.getRabbit().getConcurrentConsumers());
        container.setMaxConcurrentConsumers(properties.getRabbit().getMaxConcurrentConsumers());
        container.setPrefetchCount(properties.getRabbit().getPrefetchCount());

        // 手动 ack。自动 ack 在消费者崩溃时会丢消息——
        // Broker 一发出去就认为送达，不管消费者有没有处理成功。
        if (properties.getRabbit().isManualAck()) {
            container.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        }

        container.setMessageListener(
                (org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener)
                        (message, channel) -> handleMessage(handler, message, channel));

        container.setBeanName("rabbit-listener-" + handler.consumerGroup());
        return container;
    }

    private void handleMessage(EventHandler<?> handler, Message message,
                               com.rabbitmq.client.Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        int retryCount = readRetryCount(message);

        ConsumeResult result = dispatcher.dispatch(handler, payload, retryCount);

        switch (result) {
            case SUCCESS -> channel.basicAck(deliveryTag, false);

            case RETRY -> {
                // 不用 basicNack(requeue=true) 直接退回原队列，而是转到带 TTL 的重试队列。
                // 原因是 requeue 会把消息立刻放回队首，消费者马上又拿到它，
                // 形成「失败→重入队→立刻再失败」的热循环，既没有退避效果，
                // 又会把消费线程全部占满（毒消息场景下表现为消费者假死）。
                // 转重试队列则天然带上了退避时间，且重试次数可以随消息一起带走。
                int nextLevel = Math.min(retryCount + 1, ConsumeResult.MAX_RETRY);
                channel.basicAck(deliveryTag, false);
                republishToRetryQueue(handler, message, payload, nextLevel);
            }

            case DISCARD -> {
                // requeue=false → 走业务队列自身的死信路由配置进死信队列。
                // 不要在消费者里自己转发：requeue=false 由 Broker 保证
                // 「要么进死信队列、要么留在原队列」，不存在中间态丢消息。
                channel.basicNack(deliveryTag, false, false);
                log.error("消息进入死信队列。queue={}, retry={}",
                        RabbitMqConfig.queueName(handler.topic(), handler.consumerGroup()), retryCount);
            }
        }
    }

    private void republishToRetryQueue(EventHandler<?> handler, Message original,
                                       String payload, int nextLevel) {
        String queue = RabbitMqConfig.retryQueueName(
                handler.topic(), handler.consumerGroup(), nextLevel);
        rabbitTemplate.convertAndSend(MqTopic.EXCHANGE, queue, payload, message -> {
            MessageProperties props = message.getMessageProperties();
            props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            // 重试次数随消息一起走。RabbitMQ 没有类似 Kafka offset 的稳定标识，
            // 只能靠消息头把计数带过去，否则重试几次之后就无从判断了。
            props.setHeader(HEADER_RETRY_COUNT, nextLevel);
            // 业务键也要带上，否则重试之后这条消息的业务归属就丢了
            Object bizKey = original.getMessageProperties().getHeaders()
                    .get(RabbitMqEventPublisher.HEADER_BIZ_KEY);
            if (bizKey != null) {
                props.setHeader(RabbitMqEventPublisher.HEADER_BIZ_KEY, bizKey);
            }
            return message;
        });
        log.warn("消息转入第 {} 级重试队列。queue={}", nextLevel, queue);
    }

    private int readRetryCount(Message message) {
        Object value = message.getMessageProperties().getHeaders().get(HEADER_RETRY_COUNT);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    @Override
    public void stop() {
        containers.forEach(container -> {
            try {
                container.stop();
            } catch (Exception e) {
                log.warn("停止 RabbitMQ 监听容器失败", e);
            }
        });
        containers.clear();
        running = false;
        log.info("RabbitMQ 消费者已停止");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
